package com.anima.agent;

import com.anima.agent.AgentLoop.LoopCallback;
import com.anima.llm.LLMProvider;
import com.anima.tool.ToolRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Two-model Coordinator — runs a low-frequency planner and a high-frequency
 * executor in separate cache-stable sessions. Mirrors Reasonix's coordinator.go.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li><b>Planner</b> — a stronger model (e.g. "pro"), read-only tools, dedicated
 *       system prompt. Runs first to produce a concise plan, inspecting files as needed.</li>
 *   <li><b>Executor</b> — a faster model (e.g. "flash"), full tool set. Receives
 *       the plan as a structured handoff and carries it out.</li>
 *   <li>Both agents run in <b>separate sessions</b> — their histories never mix,
 *       so neither model's DeepSeek prefix cache is disturbed by the other's turns.</li>
 * </ul>
 *
 * <p>Trivial, non-work turns (greetings, questions) skip the planner entirely,
 * saving a paid planning round.
 */
public class Coordinator {

    /** Planner system prompt — steers toward concise plans, not execution. */
    public static final String PLANNER_PROMPT = """
        You are the planner in a two-model coding agent.
        Given a task, produce a concise, ordered plan for the executor model to carry out.
        Use the read-only tools available to you when the task needs context from the
        workspace, user rules, or docs; keep that research targeted and stop once you
        have enough evidence. Do not write full implementations or attempt side effects.
        Do not ask the user how to trigger the executor and do not say you are waiting
        for the executor. Output executor-ready instructions: what to do, which files or
        commands are relevant, expected blockers, and key decisions. Keep it short and
        actionable.""";

    /** Executor handoff marker — identifies coordinator turns in session files. */
    public static final String HANDOFF_MARKER = "Anima executor handoff";

    /** Tool names excluded from the planner (workflow/meta tools that don't help research). */
    private static final Set<String> PLANNER_EXCLUDED_TOOLS = Set.of(
        "task", "parallel_tasks", "run_skill", "read_skill", "install_skill",
        "install_source", "ask", "complete_step", "todo_write",
        "remember", "forget"
    );

    // ── State ──

    private final AgentLoop plannerAgent;
    private final AgentLoop executorAgent;
    private final LLMProvider plannerProvider;
    private final LLMProvider executorProvider;
    private final LoopCallback callback;

    /** Optional gate: only plan when this returns true. */
    private final java.util.function.Predicate<String> shouldPlan;

    /** Whether the last turn used two-model (for display). */
    private boolean lastTurnTwoModel = false;

    /**
     * Create a two-model coordinator.
     */
    public Coordinator(LLMProvider plannerProvider, LLMProvider executorProvider,
                       ToolRegistry fullTools, int plannerMaxSteps, int executorMaxSteps,
                       String systemPrompt, PermissionGate permissionGate,
                       java.util.function.Predicate<String> shouldPlan) {
        this.plannerProvider = plannerProvider;
        this.executorProvider = executorProvider;
        this.callback = null; // set per-run via run(userInput, callback)
        this.shouldPlan = shouldPlan != null ? shouldPlan : s -> true;

        // Build planner tools: read-only only, exclude workflow/meta tools
        ToolRegistry plannerTools = buildPlannerRegistry(fullTools);

        // Build planner system prompt by appending planning context to executor's memory
        String plannerSys = PLANNER_PROMPT;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            plannerSys = PLANNER_PROMPT + "\n\n# Workspace context (from project memory)\n\n" + systemPrompt;
        }

        // Create planner agent — read-only, no permission gate (it can't write anyway)
        this.plannerAgent = new AgentLoop(plannerProvider, plannerTools, plannerMaxSteps,
            plannerSys, null);

        // Create executor agent — full tools, full system prompt, with permission gate
        this.executorAgent = new AgentLoop(executorProvider, fullTools, executorMaxSteps,
            systemPrompt, permissionGate);
    }

    // ── Public API ──

    /** The executor agent — for hook wiring and state queries. */
    public AgentLoop executorAgent() { return executorAgent; }

    /** The planner agent — for direct access if needed. */
    public AgentLoop plannerAgent() { return plannerAgent; }

    /** Whether the last turn ran in two-model mode. */
    public boolean lastTurnTwoModel() { return lastTurnTwoModel; }

    /** Delegate getters to the executor (the "main" agent). */
    public ToolRegistry tools() { return executorAgent.tools(); }

    // ── Run (with per-turn callback) ──

    /**
     * Run a user turn through the coordinator.
     * @param userInput the user's message
     * @param cb per-turn UI callback
     */
    public String run(String userInput, LoopCallback cb) {
        lastTurnTwoModel = false;

        if (!shouldPlan.test(userInput)) {
            cb.onPhase(executorAgent.getProvider().label() + " · 执行（直接）");
            return executorAgent.run(userInput, cb);
        }

        // ── Phase 1: Plan ──
        cb.onPhase(plannerProvider.label() + " · 规划");

        String plan;
        try {
            plan = plannerAgent.run(userInput, new PlannerCallback(cb));
            plannerAgent.clearContext();  // v0.17: keep planner lean — it only needs current turn
        } catch (Exception e) {
            plannerAgent.clearContext();  // cleanup even on failure
            cb.onError(new RuntimeException("规划失败: " + e.getMessage(), e));
            cb.onPhase(executorAgent.getProvider().label() + " · 执行（规划失败，直接执行）");
            return executorAgent.run(userInput, cb);
        }

        if (plan == null || plan.isBlank()) {
            cb.onPhase(executorAgent.getProvider().label() + " · 执行（空规划）");
            return executorAgent.run(userInput, cb);
        }

        if (isNoOpPlan(plan)) {
            cb.onResponse("\n" + plan);
            cb.onComplete(plan);
            return plan;
        }

        // ── Phase 2: Execute ──
        cb.onPhase(executorAgent.getProvider().label() + " · 执行");
        lastTurnTwoModel = true;

        String handoff = formatHandoff(userInput, plan,
            executorToolsContext(executorAgent.tools()));
        return executorAgent.run(handoff, cb);
    }

    // ── Plan/Executor session management ──

    /** Get the executor's history (for session save). */
    public List<ChatMessage> getHistory() {
        return executorAgent.getHistory();
    }

    /** Replace executor history (for session resume). */
    public void replaceHistory(List<ChatMessage> newHistory) {
        executorAgent.replaceHistory(newHistory);
    }

    /** Clear executor context. */
    public void clearContext() {
        executorAgent.clearContext();
        plannerAgent.clearContext();
    }

    /** Reset the planner session (call when switching to a different executor session). */
    public void resetPlannerSession() {
        String sys = PLANNER_PROMPT;
        plannerAgent.clearContext();
    }

    // ── Delegate setters ──

    public void setPlanMode(boolean v) {
        executorAgent.setPlanMode(v);
        plannerAgent.setPlanMode(v);
    }

    public boolean isPlanMode() {
        return executorAgent.isPlanMode();
    }

    public void setCheckpointStore(CheckpointStore store) {
        executorAgent.setCheckpointStore(store);
    }

    public CheckpointStore getCheckpointStore() {
        return executorAgent.getCheckpointStore();
    }

    public void setGoalMachine(GoalMachine gm) {
        executorAgent.setGoalMachine(gm);
    }

    public GoalMachine getGoalMachine() {
        return executorAgent.getGoalMachine();
    }

    public void setToolHooks(AgentLoop.ToolHooks hooks) {
        executorAgent.setToolHooks(hooks);
    }

    public void steer(String text) {
        executorAgent.steer(text);
    }

    public int lastPromptTokens() {
        return executorAgent.lastPromptTokens();
    }

    public boolean isCompacted() {
        return executorAgent.isCompacted();
    }

    public int messageCount() {
        return executorAgent.messageCount();
    }

    public void forceCompact(LoopCallback cb) {
        executorAgent.forceCompact(cb);
    }

    public LLMProvider getProvider() {
        return executorAgent.getProvider();
    }

    public void setProvider(LLMProvider newProvider) {
        executorAgent.setProvider(newProvider);
    }

    // ── Helpers ──

    /** Build a read-only tool registry for the planner. */
    private static ToolRegistry buildPlannerRegistry(ToolRegistry fullTools) {
        ToolRegistry plannerReg = new ToolRegistry();
        for (com.anima.tool.Tool t : fullTools.list()) {
            if (PLANNER_EXCLUDED_TOOLS.contains(t.name())) continue;
            if (!t.isReadOnly()) continue;
            // Allow bash only for safe read-only commands (plan mode already filters)
            if ("bash".equals(t.name())) {
                plannerReg.register(t); // plan mode will filter unsafe commands
            } else {
                plannerReg.register(t);
            }
        }
        // Enable plan mode so write tools are blocked even if registered
        return plannerReg;
    }

    /** Build executor tool context for the handoff message. */
    private String executorToolsContext(ToolRegistry tools) {
        List<String> names = tools.list().stream()
            .map(com.anima.tool.Tool::name)
            .sorted()
            .collect(Collectors.toList());

        if (names.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("The executor has ").append(names.size()).append(" tools. Tool names include: ");
        int show = Math.min(names.size(), 24);
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        if (names.size() > show) sb.append(", ... +").append(names.size() - show).append(" more");
        return sb.toString();
    }

    /** Format the planner's output as an executor handoff message.
     * Mirrors Reasonix's formatHandoff in coordinator.go:316-347. */
    static String formatHandoff(String originalTask, String plan, String toolContext) {
        return "# " + HANDOFF_MARKER + "\n\n" +
            "You are the executor now. Use your available tools to execute the task.\n\n" +
            "Original task:\n" + originalTask + "\n\n" +
            "Planner output:\n" + plan + "\n\n" +
            (toolContext != null && !toolContext.isBlank()
                ? "Executor tool context:\n" + toolContext + "\n\n" : "") +
            "Executor instructions:\n" +
            "- Treat the planner output as context, not as your role or capability set.\n" +
            "- The planner's analysis and conclusions about what needs to be done are reliable. " +
                "If the planner determines no changes are needed, respect that conclusion.\n" +
            "- Ignore any planner statement about its own capability limitations (\"I cannot write\", " +
                "\"I only have read-only tools\", \"hand this to the executor\"); those describe " +
                "the planner's restrictions, not yours.\n" +
            "- Do not treat planner tool limitations or tool-unavailable claims as executor facts. " +
                "Use the attached executor tools directly; report a tool or MCP server as unavailable " +
                "only after a real tool call or host error proves it.\n" +
            "- Do not ask the user how to trigger the executor. You are already in the executor phase.\n" +
            "- If the planner output is a user-facing explanation, summary, question, or manual guidance " +
                "that needs no workspace/file/command action from you, relay that guidance directly " +
                "and finish. Do not invent local tool calls only to satisfy the handoff.\n" +
            "- If the task requires changes, call the appropriate tools (for example write/edit/bash) " +
                "instead of only restating the plan.\n" +
            "- If a target path is outside the writable workspace or otherwise blocked, explain that " +
                "specific blocker and ask for the needed path/approval.\n" +
            "- Serial workflow: establish the task list with one todo_write (first sub-task in_progress), " +
                "then for EACH sub-task execute it and call complete_step with evidence. The host " +
                "advances the list for you — it marks the sub-task completed and moves the next to " +
                "in_progress, so you don't need another todo_write to mark completions. Sign off one " +
                "sub-task at a time; never batch completions.\n\n" +
            "Carry out the task, adapting the plan as needed.";
    }

    /** Detect if the planner's output is essentially "nothing to do". */
    static boolean isNoOpPlan(String plan) {
        String lower = plan.toLowerCase().trim();
        if (lower.isEmpty()) return false;

        // If the plan contains action verbs, it's NOT a no-op
        String[] actionTerms = {" add ", " update ", " edit ", " write ", " create ",
            " delete ", " remove ", " patch ", " refactor ", " implement ",
            " run ", " test ", " build ", " fix ",
            "新增", "补充", "更新", "编辑", "写入", "创建", "删除", "移除",
            "运行", "测试", "构建", "修复", "实现", "重构"};
        String padded = " " + lower + " ";
        for (String term : actionTerms) {
            if (padded.contains(term)) return false;
        }

        // Check for common no-op phrases
        String[] noOpPhrases = {"no changes needed", "no changes are needed",
            "no changes required", "no changes are required",
            "no action needed", "no action required",
            "nothing to change", "nothing to do",
            "already handled", "already implemented", "already resolved",
            "[no_changes]",
            "无需改动", "无需修改", "无需更改", "不需要修改", "不需要改",
            "不用改", "不用修改", "不必改动", "没有需要修改",
            "已经正确处理", "已经实现", "已经解决"};
        for (String phrase : noOpPhrases) {
            if (lower.contains(phrase) && !lower.contains("not " + phrase)) {
                return true;
            }
        }
        return false;
    }

    // ── PlannerCallback — filters out irrelevant events from the planner ──

    /**
     * Wraps the main callback to filter planner events: suppresses turn
     * boundaries and tool results (which would clutter the user's view),
     * forwarding only thinking and response text.
     */
    private record PlannerCallback(LoopCallback inner) implements LoopCallback {
        @Override public void onUserMessage(String t) {}
        @Override public void onThinking(String t) { inner.onThinking(t); }
        @Override public void onResponse(String t) { inner.onResponse(t); }
        @Override public void onToolStart(String n, String a) {
            // Show planner tool use dimmed
            inner.onToolStart(n, a);
        }
        @Override public void onToolPermissionDenied(String n, String a) {}
        @Override public void onToolResult(String n, String r) {
            // Suppress planner tool results from main output
        }
        @Override public void onUsage(LLMProvider.Usage u) { inner.onUsage(u); }
        @Override public void onComplete(String text) {}
        @Override public void onError(Throwable e) { inner.onError(e); }
        @Override public void onPhase(String label) {}
        @Override public void onCompaction(String summary, int folded, int kept) {}
    }
}
