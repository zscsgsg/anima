package com.anima.terminal;

import com.anima.agent.AgentLoop;
import com.anima.agent.AgentLoop.LoopCallback;
import com.anima.agent.CheckpointStore;
import com.anima.agent.Coordinator;
import com.anima.agent.GoalMachine;
import com.anima.agent.PermissionGate;
import com.anima.agent.PermissionPolicy;
import com.anima.config.AnimaConfig;
import com.anima.config.AnimaConfig.ProviderSpec;
import com.anima.hook.HookManager;
import com.anima.llm.DeepSeekProvider;
import com.anima.llm.LLMProvider;
import com.anima.llm.ProviderManager;
import com.anima.lsp.*;
import com.anima.memory.ProjectMemory;
import com.anima.output.OutputStyle;
import com.anima.plugin.McpHost;
import com.anima.session.SessionManager;
import com.anima.skill.*;
import com.anima.tool.*;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JLine3-based terminal REPL — Claude Code 1:1 experience.
 * v0.11: Session persistence + Slash command system.
 */
public class TerminalUI {

    private static final String SYSTEM_PROMPT = """
        You are Anima, a terminal AI coding agent.
        You are running on Windows (cmd.exe).
        Use ls, glob, grep for cross-platform exploration without confirmation.
        Use read_file to read files — NEVER use bash type/cat for reading.
        Use write_file to create/overwrite files, edit_file for precise changes.
        Use bash ONLY for actual commands: build (mvn), git, tests, etc.

        EDITING WORKFLOW (follow this exactly):
        1. grep to locate the target line/string in the file
        2. read_file to see the exact text including ALL whitespace and indentation
        3. edit_file with old_string copied verbatim from read_file output
        If edit_file fails, go back to step 2 — re-read and copy EXACTLY.

        PLANNING & COMPLETION:
        - Use todo_write to plan multi-step work before starting.
        - After finishing each step, call complete_step with evidence — cite the
          tool calls that prove the step is done (e.g. bash output, file changes).
        - Do NOT mark items completed in todo_write directly; use complete_step instead.
          The host will advance the todo list automatically when you sign off.

        PLAN MODE: When active, only read-only tools are allowed. Use this to
        explore and understand before making changes. Exit plan mode to write.

        MEMORY: Use remember to save durable facts (preferences, project conventions,
        lessons learned) that survive across sessions. Use forget to remove outdated
        memories. Check the memory index in context before saving near-duplicates.

        SKILLS: Before non-trivial work, scan the Skills index. Invoke relevant
        inline skills with read_skill (plan-mode safe) or run_skill. Subagent skills
        (tagged [🧬 subagent]) are context-heavy — reach for them only when needed.

        SKILL DISCOVERY & INSTALLATION: When the user asks to find or install a skill:
        1. Use web_fetch to search GitHub for SKILL.md files. Try URLs like:
           https://github.com/search?q=SKILL.md+<topic>&type=code
           Or fetch known skill repos directly.
        2. If the user gives a GitHub repo URL (e.g. https://github.com/user/repo),
           pass it to install_source — it automatically scans the repo's
           .reasonix/skills/ .claude/skills/ .anima/skills/ directories for all
           SKILL.md files via the GitHub API.
        3. Present found skills to the user with the ask tool — show name, description,
           source URL, and risk level.
        4. On user approval, call install_source(source="<raw_url>", apply=true).
           Use apply=false first to show a plan, then apply=true to install.
        5. After install, the skill is immediately callable via run_skill or /<name>.
        Installed skills are auto-discovered on next launch from these directories:
        .anima/skills/ .reasonix/skills/ .agents/skills/ .agent/skills/ .claude/skills/
        (both project and ~/ user home). Skills from Claude Code or other agents work
        without migration — just place them in any of these dirs.

        MCP PLUGIN INSTALLATION: When the user wants to add an MCP server:
        1. Search or suggest known MCP servers (filesystem, github, postgres, etc.).
        2. Use install_source with a GitHub repo URL, npm package name, or .mcp.json path.
        3. npm packages are auto-detected: give the package name (e.g. @anthropic/mcp-server-filesystem)
           and install_source plans a connection via npx.
        4. Connected MCP tools appear as mcp__<server>__<tool> in the tool list.
        5. Use /mcp to check connection status and see available prompts.

        ASK: When you hit a genuine fork you can't resolve, use ask to present
        structured multiple-choice questions rather than guessing.

        After seeing tool results, synthesize a final answer.
        Respond in the same language as the user.
        """;

    /** Prompt for /init — analyze project and generate ANIMA.md. */
    private static final String INIT_PROMPT = """
        Analyze this project and write a concise ANIMA.md file that will serve as the
        project's standing instructions for future AI coding sessions.

        The file should cover:
        1. Build commands (how to compile, test, run)
        2. Code style and conventions observed in the codebase
        3. Architecture overview (key packages/modules and their responsibilities)
        4. Any important patterns, naming conventions, or constraints

        Steps:
        1. Use ls, glob, read_file to explore the project structure
        2. Check build files (pom.xml, build.gradle, package.json, etc.) for commands
        3. Sample a few key source files to detect conventions
        4. Write the result to ANIMA.md with write_file
        5. If ANIMA.local.md exists, preserve it — only write ANIMA.md

        Keep it concise (~50-100 lines). Use the existing ANIMA.md format as reference if one exists.
        """;

    private java.util.concurrent.atomic.AtomicReference<OutputStyle> currentStyleRef
        = new java.util.concurrent.atomic.AtomicReference<>(OutputStyle.DEFAULT);

    public void start(ProjectMemory memory) throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .jna(true)
                .encoding(System.getProperty("native.encoding", "UTF-8"))
                .build();

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();

        var tw = new TerminalWriter(terminal);
        Path cwd = Path.of("").toAbsolutePath();

        // Build system prompt with project memory
        String fullPrompt = SYSTEM_PROMPT;
        if (memory != null && !memory.isEmpty()) {
            fullPrompt = memory.block() + "\n\n" + SYSTEM_PROMPT;
        }

        // Tool registry
        var tools = new ToolRegistry();
        tools.register(new LsTool());
        tools.register(new GlobTool());
        tools.register(new GrepTool());
        tools.register(new ReadFileTool());
        tools.register(new BashTool());
        tools.register(new WriteFileTool());
        tools.register(new EditFileTool());
        tools.register(new MoveFileTool());
        tools.register(new TodoWriteTool());
        tools.register(new CompleteStepTool());
        tools.register(new WebFetchTool());
        tools.register(new MultiEditTool());
        tools.register(new CodeIndexTool());

        // ── v0.13: LSP tools (on-demand, PATH-resolved) ──
        var lspMgr = new LspManager(System.getProperty("user.dir"), LspManager.defaultSpecs());
        for (var lspTool : LspTool.tools(lspMgr)) tools.register(lspTool);

        // ── v0.14: Hook system ──
        HookManager hookMgr = HookManager.load(cwd);
        if (!hookMgr.isEmpty()) {
            tw.dim("  已加载 " + hookMgr.describe().size() + " 个生命周期钩子");
        }

        // ── v0.15: Skills system ──
        var skillStore = SkillStore.builder()
            .projectRoot(cwd)
            .animaHomeDir(Path.of(System.getProperty("user.home"), ".anima"))
            .build();
        var loadedSkills = skillStore.list();
        if (!loadedSkills.isEmpty()) {
            tw.dim("  已加载 " + loadedSkills.size() + " 个技能");
        }
        // Skill tools (need agent reference for subagent execution, wired below)
        var runSkill = new RunSkillTool(skillStore, null); // runner set after agent creation
        var readSkill = new ReadSkillTool(skillStore);
        var installSkill = new InstallSkillTool(skillStore, null);
        tools.register(runSkill);
        tools.register(readSkill);
        tools.register(installSkill);
        // ── v0.15.1: Install source tool (URL/GitHub/local download) ──
        tools.register(new InstallSourceTool(cwd,
            Path.of(System.getProperty("user.home"), ".anima"), skillStore));

        // ── v0.15: MCP plugin host ──
        McpHost mcpHost = null;
        try {
            mcpHost = McpHost.fromWorkspace(cwd);
            for (var mcpTool : mcpHost.tools()) {
                tools.register(mcpTool);
            }
            if (mcpHost.serverCount() > 0) {
                tw.dim("  已连接 " + mcpHost.serverCount() + " 个MCP服务，" +
                    mcpHost.tools().size() + " 个工具");
            }
            for (String failure : mcpHost.failures()) {
                tw.dim("  ⚠ MCP: " + failure);
            }
        } catch (Exception e) {
            tw.dim("  MCP启动: " + e.getMessage());
        }
        final McpHost finalMcpHost = mcpHost;

        // Permission gate
        PermissionGate gate = new TerminalPermissionGate(reader, tw, PermissionPolicy.defaults(), cwd);

        // ── v0.15: Ask, Remember, Forget, Memory tools ──
        tools.register(new AskTool(null)); // asker set after terminal is ready
        tools.register(new RememberTool(cwd));
        tools.register(new ForgetTool(cwd));
        tools.register(new MemoryRecallTool(cwd));

        // ── Load configuration (.anima/anima.properties) ──
        AnimaConfig config = AnimaConfig.load(cwd);
        tw.dim("  配置: " + config.modelName() + " (label: " + config.defaultModelLabel() + ")"
            + ", 规划器: " + (config.plannerModel().isEmpty() ? "单模型" : config.plannerModel()) );

        // ── Multi-provider with hot-switch, driven by config ──
        var providerManager = new ProviderManager();
        LLMProvider provider;
        try {
            java.util.List<ProviderSpec> specs = config.providers();
            for (ProviderSpec spec : specs) {
                providerManager.register(new DeepSeekProvider(spec.label(), spec.modelName(), spec.baseUrl()));
            }
            provider = providerManager.active();
        } catch (IllegalStateException e) {
            tw.error(e.getMessage());
            tw.plain("Set DEEPSEEK_API_KEY environment variable.");
            terminal.close();
            return;
        }

        // ── v0.11: Session management ──
        var sessionManager = new SessionManager(cwd);
        List<dev.langchain4j.data.message.ChatMessage> existingHistory = null;
        try {
            existingHistory = sessionManager.loadLatest();
        } catch (Exception e) {
            tw.dim("  无法加载历史会话: " + e.getMessage());
        }

        // ── v0.17: Append skills index + output style + saved memory index to prompt ──
        fullPrompt = SkillIndex.applyIndex(fullPrompt, skillStore.list());
        fullPrompt = currentStyleRef.get().applyTo(fullPrompt);
        fullPrompt = appendMemoryIndex(fullPrompt, cwd);

        // ── v0.17: Two-model coordinator (auto-enabled when 2+ providers) ──
        final AgentLoop agent;
        final Coordinator coordinator;

        // Auto-enable two-model if we have at least 2 providers (e.g. flash + pro)
        boolean twoModel = providerManager.all().size() >= 2;

        if (twoModel) {
            // Use configured planner model label, or fallback to first non-active provider
            LLMProvider executorProvider = providerManager.active();
            LLMProvider plannerProvider;
            String plannerLabel = config.plannerModel();
            if (!plannerLabel.isEmpty() && providerManager.all().containsKey(plannerLabel)) {
                plannerProvider = providerManager.all().get(plannerLabel);
            } else {
                // Fallback: first non-active provider
                plannerProvider = providerManager.all().values().stream()
                    .filter(p -> !p.label().equals(executorProvider.label()))
                    .findFirst().orElse(executorProvider);
            }
            coordinator = new Coordinator(
                plannerProvider, executorProvider,
                tools, 0, 0,  // plannerMaxSteps=0, executorMaxSteps=0 (both unbounded, matches Reasonix)
                fullPrompt, gate,
                s -> !isTrivialInput(s)
            );
            agent = coordinator.executorAgent();
            // v0.17: Restore executor history for session resume (planner stays fresh)
            if (existingHistory != null && !existingHistory.isEmpty()) {
                coordinator.replaceHistory(existingHistory);
            }
            tw.dim("  双模型协作: " + plannerProvider.label() + "(规划) + " +
                executorProvider.label() + "(执行)");
        } else {
            coordinator = null;
            agent = new AgentLoop(provider, tools, 0, fullPrompt, gate, existingHistory);
        }

        // ── v0.16: Checkpoint/Rewind — edit safety net ──
        Path sessionsDir = cwd.resolve(".anima").resolve("sessions");
        String ckptSessionName = sessionManager.currentName();
        if (ckptSessionName != null) {
            var checkpointStore = new CheckpointStore(sessionsDir, ckptSessionName, cwd);
            agent.setCheckpointStore(checkpointStore);
            tw.dim("  检查点系统就绪 (" + checkpointStore.list().size() + " 个历史检查点)");
        }
        // ── v0.16: History search tool ──
        tools.register(new HistoryTool(sessionsDir));
        // ── v0.17: New tools (delete_range, workspace, preview) ──
        tools.register(new DeleteRangeTool(cwd));
        tools.register(new WorkspaceTool(cwd));
        tools.register(new PreviewTool());
        // ── v0.16: Goal machine for autonomous pursuit ──
        var goalMachine = new GoalMachine(null); // no persistence for MVP
        agent.setGoalMachine(goalMachine);

        // ── v0.14: Wire hooks + task tool (post-agent creation, needs provider) ──
        agent.setToolHooks(hookMgr);
        var taskTool = new TaskTool(provider, tools, cwd.toString(), 0, fullPrompt);
        tools.register(taskTool);
        // ── v0.16: Parallel tasks tool (needs task tool reference) ──
        tools.register(new ParallelTasksTool(
            taskTool, provider, tools, cwd.toString(), 0, fullPrompt));

        // ── v0.14.1: Wire agent into permission gate for !steer during prompts ──
        ((TerminalPermissionGate) gate).setAgent(agent);

        // ── v0.11: Slash command dispatcher ──
        var dispatcher = new SlashDispatcher();
        var slashCtx = new SlashContext(agent, tw, sessionManager, providerManager);

        var renderer = new MarkdownRenderer(tw);

        // Banner — driven by config
        String bannerText = "anima v0.17.0 · " + provider.modelName()
            + " · " + tools.list().size() + " tools"
            + (loadedSkills.isEmpty() ? "" : " · " + loadedSkills.size() + " skills");
        tw.banner(bannerText);

        // Show session status
        if (existingHistory != null && !existingHistory.isEmpty()) {
            String sessionName = sessionManager.currentName();
            tw.dim("  已恢复会话: " + sessionName + " (" + existingHistory.size() + " 条消息)");
        } else {
            tw.dim("  新会话 — 输入 /help 查看可用命令");
        }
        tw.br();

        // Main loop
        while (true) {
            String line;
            try {
                line = reader.readLine("> ");
            } catch (UserInterruptException e) {
                tw.plain("^C");
                continue;
            } catch (EndOfFileException e) {
                break;
            }
            if (line == null) break;
            String input = line.trim();
            if (input.isEmpty()) continue;

            // ── v0.11: Slash command dispatch ──
            if (input.startsWith("/")) {
                // /memory is special — needs ProjectMemory access
                if (input.equalsIgnoreCase("/memory")) {
                    showMemory(tw, memory);
                    continue;
                }
                // /hooks — v0.14 hook introspection
                if (input.equalsIgnoreCase("/hooks") || input.startsWith("/hooks ")) {
                    for (String hookLine : hookMgr.describe()) {
                        tw.dim(hookLine);
                    }
                    continue;
                }
                // ── v0.15: /skills — show loaded skills ──
                if (input.equalsIgnoreCase("/skills") || input.equalsIgnoreCase("/skill")) {
                    showSkills(tw, skillStore);
                    continue;
                }
                // ── v0.15: /mcp — show MCP status ──
                if (input.equalsIgnoreCase("/mcp")) {
                    showMcp(tw, finalMcpHost);
                    continue;
                }
                // ── v0.15: /style — show or set output style ──
                if (input.startsWith("/style")) {
                    String rest = input.substring("/style".length()).trim();
                    if (rest.isEmpty()) {
                        OutputStyle cur = currentStyleRef.get();
                        StringBuilder sb = new StringBuilder("Current: " + cur.name());
                        if (!cur.description().isEmpty()) sb.append(" — ").append(cur.description());
                        sb.append("\nAvailable: ");
                        for (var s : OutputStyle.BUILTINS) sb.append(s.name()).append(" ");
                        sb.append("\nUsage: /style <name>");
                        tw.plain(sb.toString());
                    } else {
                        OutputStyle selected = OutputStyle.resolve(rest);
                        currentStyleRef.set(selected);
                        tw.plain("Style set to: " + selected.name() + " — " + selected.description() +
                            "\n(下一轮对话生效)");
                    }
                    continue;
                }
                // ── v0.17: /init — generate/update ANIMA.md project memory ──
                if (input.equalsIgnoreCase("/init") || input.startsWith("/init ")) {
                    String initPrompt = input.equalsIgnoreCase("/init")
                        ? INIT_PROMPT
                        : INIT_PROMPT + "\n用户额外说明：" + input.substring("/init".length()).trim();
                    tw.dim("  ⎿  分析项目并生成 ANIMA.md...");
                    runInit(agent, coordinator, tw, renderer, initPrompt, cwd, memory != null && !memory.isEmpty());
                    continue;
                }
                String result = dispatcher.dispatch(input, slashCtx);
                if (result != null) {
                    if ("__EXIT__".equals(result)) break;
                    tw.plain(result);
                }
                continue;
            }

            // ── v0.14: Mid-turn steer via ! prefix ──
            if (input.startsWith("!")) {
                String steerMsg = input.substring(1).trim();
                if (!steerMsg.isEmpty()) {
                    agent.steer(steerMsg);
                    tw.dim("  ⎿  Steer queued: " + (steerMsg.length() > 60 ? steerMsg.substring(0, 57) + "..." : steerMsg));
                }
                continue;
            }

            // ── Regular agent turn ──
            var stats = new Object() {
                int toolUses = 0, promptT = 0, compT = 0;
                long start = System.currentTimeMillis();
                boolean thinkingHeader = false, thinkingDone = false, phaseShown = false;
            };

            // Don't pre-print thinking — only show when real content arrives

            var turnCallback = new LoopCallback() {
                @Override public void onUserMessage(String t) {}
                @Override public void onPhase(String label) {
                    stats.phaseShown = true;
                    tw.phase(label);
                }
                @Override public void onThinking(String t) {
                    if (!stats.thinkingHeader) {
                        stats.thinkingHeader = true;
                        if (!stats.phaseShown) tw.turnStart();
                        tw.thinkingHeader();
                    }
                    tw.thinkingToken(t);
                }
                @Override public void onResponse(String t) {
                    if (!stats.thinkingDone) {
                        if (!stats.thinkingHeader) {
                            if (!stats.phaseShown) tw.turnStart();
                        } else {
                            tw.thinkingEnd();
                        }
                        stats.thinkingDone = true;
                    }
                    renderer.feed(t);
                }
                @Override public void onToolStart(String name, String args) {
                    if (!stats.thinkingDone && stats.thinkingHeader) {
                        stats.thinkingDone = true;
                        tw.thinkingEnd();
                    } else if (!stats.thinkingDone && !stats.thinkingHeader) {
                        if (!stats.phaseShown) tw.turnStart();
                        stats.thinkingDone = true;
                    }
                    renderer.flush();
                    tw.toolCall(name, args);
                }
                @Override public void onToolPermissionDenied(String name, String args) {
                    tw.dim("  ⎿  Denied: " + name);
                }
                @Override public void onToolResult(String name, String result) {
                    stats.toolUses++;
                    tw.toolResult(result);
                }
                @Override public void onUsage(LLMProvider.Usage u) {
                    stats.promptT += u.promptTokens();
                    stats.compT += u.completionTokens();
                }
                @Override public void onCompaction(String summary, int folded, int kept) {
                    if (summary == null) {
                        tw.dim("  ⎿  压缩中... (折叠 " + folded + " 条，保留 " + kept + " 条)");
                    } else {
                        tw.dim("  ⎿  上下文压缩完成 — " + folded + " 条消息折叠 → 摘要(" + summary.length() + "字)");
                    }
                }
                @Override public void onComplete(String text) {
                    renderer.flush();
                    tw.br();
                    tw.done(stats.toolUses, stats.promptT, stats.compT,
                            (System.currentTimeMillis() - stats.start) / 1000.0);
                    String mode = agent.isCompacted() ? "compacted" :
                                  agent.isPlanMode() ? "plan" :
                                  (coordinator != null && coordinator.lastTurnTwoModel()) ? "dual" : "default";
                    tw.statusLine(mode, agent.getProvider().label() + "/" + agent.getProvider().modelName(),
                            tools.list().size(),
                            agent.lastPromptTokens() > 0 ? agent.lastPromptTokens() : stats.promptT + stats.compT);
                }
                @Override public void onError(Throwable e) {
                    tw.error(e.getMessage() != null ? e.getMessage() : "Unknown error");
                }
            };

            if (coordinator != null) {
                coordinator.run(input, turnCallback);
            } else {
                agent.run(input, turnCallback);
            }

            // ── v0.16: Goal auto-continuation ──
            if (goalMachine.isActive()) {
                String lastText = agent.getHistory().get(agent.getHistory().size() - 1) instanceof dev.langchain4j.data.message.AiMessage ai
                    ? ai.text() : "";
                java.util.List<String> incomplete = new java.util.ArrayList<>();
                // Check todos — simplified (just check complete_step evidence)
                String notice = goalMachine.handleSignal(lastText, incomplete);
                if (notice != null) {
                    tw.dim("  ⎿  Goal: " + notice);
                }
                // Continue if still active
                if (goalMachine.isActive()) {
                    String nextPrompt = goalMachine.nextTurnPrompt();
                    if (nextPrompt != null) {
                        tw.dim("  ⎿  Goal auto-continue (turn " + (goalMachine.statusText()) + ")");
                        // Recursively run another turn with the continuation prompt
                        continue; // stay in the loop — but need to call agent.run with the prompt
                    }
                }
            }

            // ── v0.11: Auto-save after each complete turn ──
            try {
                sessionManager.save(agent.getHistory());
            } catch (Exception e) {
                tw.dim("  ⎿  会话保存失败: " + e.getMessage());
            }
        }

        // ── v0.11: Save on exit ──
        try {
            sessionManager.save(agent.getHistory());
            tw.dim("会话已保存: " + sessionManager.currentName());
        } catch (Exception e) {
            tw.dim("会话保存失败: " + e.getMessage());
        }

        // ── v0.15: MCP shutdown ──
        if (finalMcpHost != null) {
            finalMcpHost.close();
        }

        tw.plain("bye.");
        terminal.close();
    }

    private void showMemory(TerminalWriter tw, ProjectMemory memory) {
        if (memory == null || memory.isEmpty()) {
            tw.dim("  未加载项目记忆（创建 ANIMA.md 文件即可）");
        } else {
            tw.dim("  ── 项目记忆 (" + memory.sources().size() + " 文件) ──");
            for (var src : memory.sources()) {
                tw.plain("  📄 " + src.path() + " [" + src.scope() + "] — " + src.body().lines().count() + " 行");
            }
            tw.dim("  ── 编辑 ANIMA.md 后重启生效 ──");
        }
    }

    // ── v0.15: Helper methods ──

    /** Detect trivial inputs that don't need a planning round. */
    private static boolean isTrivialInput(String input) {
        String lower = input.toLowerCase().trim();
        // Greetings
        if (lower.matches("^(hi|hello|hey|yo|sup|good (morning|afternoon|evening))[!.]*$")) return true;
        // Pure questions (no code/change intent)
        if (lower.matches("^(what|where|when|why|who|how|can you|could you|would you|is it|are there)\\b.*\\?$") &&
            !lower.contains("fix") && !lower.contains("change") && !lower.contains("implement") &&
            !lower.contains("create") && !lower.contains("write") && !lower.contains("edit") &&
            !lower.contains("修改") && !lower.contains("实现") && !lower.contains("创建") &&
            !lower.contains("修复") && !lower.contains("编写") && !lower.contains("改")) return true;
        // Very short inputs (less than 10 chars + no action verb)
        if (input.length() < 10) {
            String[] actionVerbs = {"fix", "add", "make", "build", "write", "edit", "run", "test",
                "修改", "添加", "实现", "创建", "修复", "运行", "测试", "写"};
            for (String v : actionVerbs) if (lower.contains(v)) return false;
            return true;
        }
        return false;
    }

    // ── v0.15: Skill/MCP helpers ──

    /** Run the /init command — let agent analyze project and write ANIMA.md. */
    private static void runInit(AgentLoop agent, Coordinator coordinator,
                                 TerminalWriter tw, MarkdownRenderer renderer,
                                 String prompt, Path cwd, boolean hasExisting) {
        String notice = hasExisting
            ? "  ANIMA.md 已存在，将更新它。"
            : "  首次运行 /init，将创建 ANIMA.md。";
        tw.dim(notice);

        var stats = new Object() {
            int toolUses = 0, promptT = 0, compT = 0;
            long start = System.currentTimeMillis();
            boolean thinkingHeader = false, thinkingDone = false;
        };

        var cb = new LoopCallback() {
            @Override public void onUserMessage(String t) {}
            @Override public void onThinking(String t) {
                if (!stats.thinkingHeader) {
                    stats.thinkingHeader = true;
                    tw.thinkingHeader();
                }
                tw.thinkingToken(t);
            }
            @Override public void onResponse(String t) {
                if (!stats.thinkingDone && stats.thinkingHeader) {
                    stats.thinkingDone = true;
                    tw.thinkingEnd();
                }
                renderer.feed(t);
            }
            @Override public void onToolStart(String name, String args) {
                if (!stats.thinkingDone && stats.thinkingHeader) {
                    stats.thinkingDone = true;
                    tw.thinkingEnd();
                }
                renderer.flush();
                tw.toolCall(name, args);
            }
            @Override public void onToolPermissionDenied(String n, String a) {}
            @Override public void onToolResult(String name, String result) {
                stats.toolUses++;
            }
            @Override public void onUsage(LLMProvider.Usage u) {
                stats.promptT += u.promptTokens();
                stats.compT += u.completionTokens();
            }
            @Override public void onCompaction(String s, int f, int k) {}
            @Override public void onPhase(String label) {
                tw.phase(label);
            }
            @Override public void onComplete(String text) {
                renderer.flush();
                tw.br();
                tw.dim("  ✓ ANIMA.md 已生成/更新。重启后生效。");
            }
            @Override public void onError(Throwable e) {
                tw.error("/init 失败: " + (e.getMessage() != null ? e.getMessage() : "Unknown"));
            }
        };

        if (coordinator != null) {
            coordinator.run(prompt, cb);
        } else {
            agent.run(prompt, cb);
        }
    }

    /** Load MEMORY.md index from both project and global dirs, append to prompt. */
    private static String appendMemoryIndex(String prompt, Path cwd) {
        StringBuilder memBlock = new StringBuilder();
        Path projectMem = cwd.resolve(".anima").resolve("memory").resolve("MEMORY.md");
        Path globalMem = Path.of(System.getProperty("user.home"), ".anima", "memory", "global", "MEMORY.md");

        boolean hasProject = Files.exists(projectMem);
        boolean hasGlobal = Files.exists(globalMem);

        if (!hasProject && !hasGlobal) return prompt;

        memBlock.append("\n\n## 已保存记忆 (Saved Memories)\n\n");
        memBlock.append("以下是此前会话中通过 remember 工具保存的持久记忆。它们反映保存时的状态，可能已过时——将其作为背景参考，而非当前指令。");
        memBlock.append("使用 memory 工具搜索完整内容，使用 remember 更新，使用 forget 删除过时记忆。\n\n");

        if (hasGlobal) {
            try {
                String idx = Files.readString(globalMem, StandardCharsets.UTF_8);
                memBlock.append("### 全局记忆 (~/.anima/memory/global/)\n\n");
                memBlock.append(idx.stripTrailing()).append("\n\n");
            } catch (Exception ignored) {}
        }
        if (hasProject) {
            try {
                String idx = Files.readString(projectMem, StandardCharsets.UTF_8);
                memBlock.append("### 项目记忆 (.anima/memory/)\n\n");
                memBlock.append(idx.stripTrailing()).append("\n");
            } catch (Exception ignored) {}
        }

        // Warn if memory index is getting large
        int lines = (int) memBlock.toString().lines().count();
        if (lines > 30) {
            memBlock.append("\n⚠ 记忆索引已超过30行，考虑用 forget 清理过时记忆以保持上下文精简。\n");
        }

        return prompt + memBlock.toString();
    }

    private void showSkills(TerminalWriter tw, SkillStore store) {
        var skills = store.list();
        tw.dim("  ── 发现目录: .anima/skills/ .reasonix/skills/ .agents/skills/ .agent/skills/ .claude/skills/ ──");
        if (skills.isEmpty()) {
            tw.dim("  未加载技能 — 在上方任一目录下创建 <name>/SKILL.md 或 <name>.md 文件");
            tw.dim("  或使用 install_source 从 URL/GitHub/本地安装");
            return;
        }
        tw.dim("  ── 技能 (" + skills.size() + " 个) ──");
        for (var sk : skills) {
            String tag = sk.runAs() == Skill.RunAs.SUBAGENT ? " [🧬 subagent]" : "";
            tw.plain("  🔧 /" + sk.name() + tag + " — " + sk.description());
        }
        tw.dim("  ── 调用: run_skill({ name: \"<name>\" }) 或 /<name> ──");
    }

    private void showMcp(TerminalWriter tw, McpHost host) {
        if (host == null || host.serverCount() == 0) {
            tw.dim("  未连接 MCP 服务 — 在项目根目录创建 .mcp.json 文件配置");
            tw.dim("  .mcp.json 支持 stdio 和 HTTP/SSE 两种传输类型");
            return;
        }
        tw.dim("  ── MCP 服务 (" + host.serverCount() + " 个) ──");
        for (String name : host.serverNames()) {
            tw.plain("  🔌 " + name);
        }
        tw.dim("  ── 共 " + host.tools().size() + " 个外部工具可用 ──");
        var prompts = host.prompts();
        if (!prompts.isEmpty()) {
            tw.dim("  ── MCP Prompts (" + prompts.size() + " 个) ──");
            for (var p : prompts) {
                tw.plain("  📋 " + p.name() + " [" + p.serverName() + "] — " + p.description());
            }
        }
        for (String failure : host.failures()) {
            tw.dim("  ⚠ " + failure);
        }
    }
}
