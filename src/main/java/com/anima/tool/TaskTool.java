package com.anima.tool;

import com.anima.agent.AgentLoop;
import com.anima.agent.AgentLoop.LoopCallback;
import com.anima.llm.LLMProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * TaskTool — spawn a sub-agent for a focused sub-task.
 * Mirrors Reasonix's task tool: the sub-agent runs in its own isolated session
 * with a filtered tool registry; only its final answer is returned to the parent.
 *
 * <h3>Use cases:</h3>
 * <ul>
 *   <li>Keep long exploration sequences out of the parent's context budget</li>
 *   <li>Delegate self-contained work like "find every place that calls X"</li>
 *   <li>Parallel research across independent areas</li>
 * </ul>
 *
 * <h3>Sub-agent boundary:</h3>
 * Recursive agent tools (task, run_skill, etc.) are excluded from sub-agents
 * to prevent unbounded nesting. Bash runs as foreground-only inside sub-agents.
 *
 * <pre>{@code
 * {
 *   "prompt": "What the sub-agent should accomplish",
 *   "description": "Short label (3-7 words)",
 *   "tools": ["read_file", "grep"],    // optional whitelist
 *   "max_steps": 10,                   // optional step cap
 *   "model": "pro"                     // optional model override
 * }
 * }</pre>
 */
public class TaskTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default system prompt for sub-agents. */
    public static final String SUBAGENT_SYSTEM = """
        You are a sub-agent invoked by a parent coding agent to carry out one focused task.
        Use the provided tools to investigate or act. Return a single final answer that is concise
        and self-contained — the parent will see only that answer, not your tool calls or reasoning.
        If you need to ask for clarification, fail with a precise question instead of guessing.
        Prefer grep/glob for file search — they handle escaping correctly. Avoid raw bash findstr/ripgrep
        for pattern matching: the shell quoting on Windows is error-prone and often yields false zeros.""";

    /** Tool names excluded from sub-agents (recursive meta-tools). */
    private static final Set<String> SUBAGENT_EXCLUDED_TOOLS = Set.of(
        "task", "run_skill", "read_skill", "install_skill"
    );

    private final LLMProvider defaultProvider;
    private final ToolRegistry parentRegistry;
    private final String workspaceRoot;
    private final int defaultMaxSteps;
    private final String parentSystemPrompt;

    /**
     * Create a task tool wired to the parent agent's environment.
     *
     * @param defaultProvider   fallback LLM provider for sub-agents
     * @param parentRegistry    parent's full tool registry (will be filtered)
     * @param workspaceRoot     absolute path to workspace root
     * @param defaultMaxSteps   step cap for sub-agents (0 = unbounded)
     * @param parentSystemPrompt parent's system prompt (for context inheritance)
     */
    public TaskTool(LLMProvider defaultProvider, ToolRegistry parentRegistry,
                    String workspaceRoot, int defaultMaxSteps, String parentSystemPrompt) {
        this.defaultProvider = defaultProvider;
        this.parentRegistry = parentRegistry;
        this.workspaceRoot = workspaceRoot;
        this.defaultMaxSteps = defaultMaxSteps;
        this.parentSystemPrompt = parentSystemPrompt;
    }

    @Override
    public String name() { return "task"; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public String description() {
        return "Spawn a sub-agent for a focused sub-task. The sub-agent runs in its own session " +
               "with a filtered tool list. Only its final answer is returned. Use this to keep " +
               "long exploration sequences out of the parent's context budget, or delegate " +
               "self-contained work like 'find every place that calls X and summarise the patterns'.";
    }

    @Override
    public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "prompt": {
              "type": "string",
              "description": "What the sub-agent should accomplish. Be specific about the deliverable."
            },
            "description": {
              "type": "string",
              "description": "Short label for the sub-task (3-7 words)."
            },
            "tools": {
              "type": "array",
              "items": {"type": "string"},
              "description": "Optional tool whitelist. Defaults to all parent tools minus recursive agent tools."
            },
            "max_steps": {
              "type": "integer",
              "description": "Optional cap on tool-call rounds. Omit for best-fit (typically 10-20 for a useful sub-task)."
            },
            "model": {
              "type": "string",
              "description": "Optional model label override for the sub-agent (e.g. 'pro')."
            }
          },
          "required": ["prompt"]
        }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode root = MAPPER.readTree(arguments);

        String prompt = root.has("prompt") ? root.get("prompt").asText() : "";
        if (prompt.isBlank()) {
            return "Error: 'prompt' is required — describe what the sub-agent should accomplish.";
        }

        String description = root.has("description") ? root.get("description").asText() : "task";
        int maxSteps = root.has("max_steps") ? root.get("max_steps").asInt() : 0;
        if (maxSteps <= 0) {
            // Mirrors Reasonix: parent bounded → half (min 5); parent unbounded → unbounded.
            // Sub-agent shares parent's ctx, so cancellation + compaction are the real bounds.
            if (defaultMaxSteps > 0) {
                maxSteps = Math.max(defaultMaxSteps / 2, 5);
            }
            // else: maxSteps stays 0 (unbounded)
        }

        // Build filtered tool registry for the sub-agent
        List<String> whitelist = null;
        if (root.has("tools") && root.get("tools").isArray()) {
            whitelist = new ArrayList<>();
            for (JsonNode t : root.get("tools")) {
                whitelist.add(t.asText());
            }
        }
        ToolRegistry subReg = buildSubRegistry(whitelist);

        // Resolve provider (model override)
        LLMProvider provider = defaultProvider;
        if (root.has("model") && !root.get("model").asText().isBlank()) {
            // Model override — note: in this MVP, we use the same provider instance
            // with a different model if supported. For now, defaultProvider is used.
            // A future version can resolve via ProviderManager.
        }

        // Build system prompt for sub-agent
        String sysPrompt = parentSystemPrompt + "\n\n" + SUBAGENT_SYSTEM;

        // Run sub-agent
        try {
            String answer = runSubAgent(provider, subReg, prompt, maxSteps, sysPrompt, description);
            return "Sub-agent '" + description + "' completed.\n\n" + answer;
        } catch (Exception e) {
            return "Sub-agent '" + description + "' failed: " + e.getMessage();
        }
    }

    /**
     * Run a sub-agent in an isolated session and return its final answer.
     */
    private String runSubAgent(LLMProvider provider, ToolRegistry tools, String prompt,
                                int maxSteps, String sysPrompt, String label) {
        var subAgent = new AgentLoop(provider, tools, maxSteps, sysPrompt, null);

        // Capture sub-agent output
        var result = new Object() {
            String answer = null;
            Throwable error = null;
            final List<String> toolLog = new ArrayList<>();
        };

        subAgent.run(prompt, new LoopCallback() {
            @Override public void onUserMessage(String t) {}
            @Override public void onThinking(String t) {}
            @Override public void onResponse(String t) {}
            @Override public void onToolStart(String name, String args) {
                result.toolLog.add("[" + name + "]");
            }
            @Override public void onToolPermissionDenied(String name, String args) {}
            @Override public void onToolResult(String name, String toolResult) {}
            @Override public void onUsage(LLMProvider.Usage u) {}
            @Override public void onComplete(String text) { result.answer = text; }
            @Override public void onError(Throwable e) { result.error = e; }
        });

        if (result.error != null) {
            throw new RuntimeException(result.error.getMessage(), result.error);
        }
        if (result.answer == null || result.answer.isBlank()) {
            return "(sub-agent produced no final answer; " + result.toolLog.size() + " tool calls made)";
        }

        StringBuilder sb = new StringBuilder(result.answer);
        if (!result.toolLog.isEmpty()) {
            int show = Math.min(result.toolLog.size(), 8);
            sb.append("\n\n── ").append(result.toolLog.size()).append(" tool uses: ");
            for (int i = 0; i < show; i++) sb.append(result.toolLog.get(i)).append(" ");
            if (result.toolLog.size() > show) sb.append("...");
        }
        return sb.toString();
    }

    /**
     * Build a filtered tool registry for a sub-agent.
     * Excludes recursive meta-tools (task itself, skill tools).
     * Wraps bash as foreground-only (Reasonix parity).
     * Public for ParallelTasksTool reuse.
     */
    public ToolRegistry buildSubRegistry(List<String> whitelist) {
        ToolRegistry sub = new ToolRegistry();

        for (Tool t : parentRegistry.list()) {
            // Exclude recursive meta-tools
            if (SUBAGENT_EXCLUDED_TOOLS.contains(t.name())) continue;

            // Apply whitelist if specified
            if (whitelist != null && !whitelist.contains(t.name())) continue;

            // Wrap bash as foreground-only inside sub-agents (Reasonix parity)
            if ("bash".equals(t.name())) {
                sub.register(new ForegroundOnlyBash(t));
            } else {
                sub.register(t);
            }
        }
        return sub;
    }

    /**
     * Wraps bash to block background execution inside sub-agents.
     * Mirrors Reasonix's foregroundOnlyBash wrapper in task.go.
     */
    private static class ForegroundOnlyBash implements Tool {
        private final Tool inner;

        ForegroundOnlyBash(Tool inner) { this.inner = inner; }

        @Override public String name() { return "bash"; }
        @Override public boolean isReadOnly() { return inner.isReadOnly(); }

        @Override public String description() {
            String desc = inner.description();
            desc = desc.replace("Execute a command in the shell",
                               "Execute a foreground command in the shell");
            return desc + " Background execution is unavailable inside subagents.";
        }

        @Override
        public String schema() {
            return """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Shell command to execute in the foreground"
                }
              },
              "required": ["command"]
            }""";
        }

        @Override
        public String execute(String arguments) throws Exception {
            // Block background execution
            try {
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
                if (json.has("run_in_background") && json.get("run_in_background").asBoolean()) {
                    return "Error: background bash is unavailable in subagents; " +
                           "run a foreground command or ask the parent agent to start a background job.";
                }
            } catch (Exception ignored) {}
            return inner.execute(arguments);
        }
    }

    // ── Static helpers for TaskTool result formatting ──

    /** Format a sub-agent reference for continuation. */
    public static String formatReference(String subagentId) {
        return "Subagent reference: " + subagentId + "\n" +
               "To continue this subagent, pass this ref as continue_from in a later task call.";
    }
}
