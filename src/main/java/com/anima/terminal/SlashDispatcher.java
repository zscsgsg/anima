package com.anima.terminal;

import com.anima.agent.AgentLoop;
import com.anima.agent.GoalMachine;
import com.anima.session.SessionManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses "/command args" input and dispatches to registered slash commands.
 * Built-in commands are registered inline.
 */
public class SlashDispatcher {

    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();

    public SlashDispatcher() {
        registerBuiltins();
    }

    /** Register a command. */
    public void register(SlashCommand cmd) {
        commands.put(cmd.name().toLowerCase(), cmd);
    }

    /**
     * Try to handle input as a slash command. Returns null if it's not a command
     * (i.e., regular user input to send to the agent).
     */
    public String dispatch(String input, SlashContext ctx) {
        if (!input.startsWith("/")) return null;
        String rest = input.substring(1).strip();
        if (rest.isEmpty()) return null;

        // Split into command name and args
        int space = rest.indexOf(' ');
        String cmdName = space > 0 ? rest.substring(0, space).toLowerCase() : rest.toLowerCase();
        String args = space > 0 ? rest.substring(space + 1).strip() : "";

        SlashCommand cmd = commands.get(cmdName);
        if (cmd == null) {
            return "Unknown command: /" + cmdName + " — type /help for available commands.";
        }

        try {
            return cmd.execute(args, ctx);
        } catch (Exception e) {
            return "Command failed: " + e.getMessage();
        }
    }

    private void registerBuiltins() {
        register(new SlashCommand() {
            @Override public String name() { return "help"; }
            @Override public String description() { return "Show available commands"; }
            @Override public String execute(String args, SlashContext ctx) {
                StringBuilder sb = new StringBuilder("Available commands:\n\n");
                for (SlashCommand c : commands.values()) {
                    sb.append("  /").append(c.name());
                    // pad to align descriptions
                    int pad = 12 - c.name().length();
                    if (pad > 0) sb.append(" ".repeat(pad));
                    sb.append("  ").append(c.description()).append("\n");
                }
                return sb.toString().trim();
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "sessions"; }
            @Override public String description() { return "List saved sessions"; }
            @Override public String execute(String args, SlashContext ctx) {
                try {
                    var sessions = ctx.sessionManager().listSessions();
                    if (sessions.isEmpty()) return "No saved sessions.";
                    StringBuilder sb = new StringBuilder("Saved sessions (newest first):\n\n");
                    for (var s : sessions) {
                        sb.append(s.toString()).append("\n");
                    }
                    sb.append("\n  * = current session");
                    return sb.toString();
                } catch (IOException e) {
                    return "Error listing sessions: " + e.getMessage();
                }
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "resume"; }
            @Override public String description() { return "Resume a saved session by name"; }
            @Override public String execute(String args, SlashContext ctx) {
                if (args.isBlank()) return "Usage: /resume <session-name>\nUse /sessions to see available sessions.";
                try {
                    var history = ctx.sessionManager().loadByName(args);
                    ctx.agent().replaceHistory(history);
                    return "Resumed session: " + args + " (" + history.size() + " messages)";
                } catch (IOException e) {
                    return "Error: " + e.getMessage();
                }
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "new"; }
            @Override public String description() { return "Start a new session (saves current)"; }
            @Override public String execute(String args, SlashContext ctx) {
                try {
                    // Save current first
                    ctx.sessionManager().save(ctx.agent().getHistory());
                } catch (IOException e) {
                    return "Warning: could not save current session: " + e.getMessage() +
                           "\nStarting new session anyway.";
                }
                ctx.sessionManager().newSession();
                ctx.agent().replaceHistory(null); // AgentLoop handles null = fresh
                return "New session started. Previous session saved.";
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "clear"; }
            @Override public String description() { return "Clear conversation context"; }
            @Override public String execute(String args, SlashContext ctx) {
                ctx.agent().clearContext();
                return "Context cleared. System prompt preserved.";
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "model"; }
            @Override public String description() { return "Show or switch model"; }
            @Override public String execute(String args, SlashContext ctx) {
                var pm = ctx.providerManager();
                if (args.isBlank()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Current: ").append(pm.activeLabel())
                      .append(" (").append(pm.active().modelName()).append(")\n");
                    sb.append("Available: ");
                    var names = pm.all().keySet();
                    sb.append(String.join(", ", names));
                    sb.append("\nUsage: /model <name>");
                    return sb.toString();
                }
                if (pm.switchTo(args)) {
                    ctx.agent().setProvider(pm.active());
                    return "Switched to " + pm.activeLabel() + " (" + pm.active().modelName() + ")";
                }
                return "Unknown model: " + args + "\nAvailable: " + String.join(", ", pm.all().keySet());
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "plan"; }
            @Override public String description() { return "Toggle plan mode (read-only)"; }
            @Override public String execute(String args, SlashContext ctx) {
                boolean current = ctx.agent().isPlanMode();
                boolean next = !current;
                // If args are "on" or "off", use that
                if ("on".equalsIgnoreCase(args)) next = true;
                else if ("off".equalsIgnoreCase(args)) next = false;
                ctx.agent().setPlanMode(next);
                return "Plan mode: " + (next ? "ON (read-only)" : "OFF (can write)");
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "compact"; }
            @Override public String description() { return "Manually compact conversation context"; }
            @Override public String execute(String args, SlashContext ctx) {
                var agent = ctx.agent();
                int before = agent.messageCount();
                agent.forceCompact(new AgentLoop.LoopCallback() {
                    @Override public void onCompaction(String summary, int folded, int kept) {
                        if (summary == null) {
                            ctx.out().dim("  ⎿  压缩中... (" + folded + " 条可折叠消息)");
                        } else {
                            ctx.out().dim("  ⎿  压缩完成 — " + folded + " 条消息折叠 → 摘要(" +
                                    summary.length() + "字)，当前共 " + agent.messageCount() + " 条消息");
                        }
                    }
                    @Override public void onError(Throwable e) {
                        ctx.out().error("压缩失败: " + e.getMessage());
                    }
                    @Override public void onUserMessage(String t) {}
                    @Override public void onThinking(String t) {}
                    @Override public void onResponse(String t) {}
                    @Override public void onToolStart(String name, String args2) {}
                    @Override public void onToolPermissionDenied(String name, String args2) {}
                    @Override public void onToolResult(String name, String result) {}
                    @Override public void onUsage(com.anima.llm.LLMProvider.Usage u) {}
                    @Override public void onComplete(String text) {}
                });
                return null; // silent — compaction output handled by callback
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "rewind"; }
            @Override public String description() { return "Rewind workspace to a previous turn"; }
            @Override public String execute(String args, SlashContext ctx) {
                var agent = ctx.agent();
                var store = agent.getCheckpointStore();
                if (store == null) {
                    return "Checkpoint store is not available. Rewind requires session persistence.";
                }
                var checkpoints = store.list();
                if (checkpoints.isEmpty()) {
                    return "No checkpoints available. Make some edits first.";
                }

                if (args.isBlank()) {
                    StringBuilder sb = new StringBuilder("Checkpoints (use /rewind <turn> [code|conversation|both]):\n\n");
                    for (var cp : checkpoints) {
                        String prompt = cp.prompt().length() > 60
                            ? cp.prompt().substring(0, 57) + "..." : cp.prompt();
                        int fileCount = cp.paths().size();
                        sb.append("  turn-").append(cp.turn())
                          .append("  ").append(fileCount).append(" file(s)")
                          .append("  \"").append(prompt).append("\"\n");
                    }
                    sb.append("\nScope: code (files only), conversation (history only), both (default)");
                    return sb.toString();
                }

                String[] parts = args.strip().split("\\s+", 2);
                int turn;
                try { turn = Integer.parseInt(parts[0]); }
                catch (NumberFormatException e) { return "Invalid turn number: " + parts[0]; }
                String scope = parts.length > 1 ? parts[1].toLowerCase() : "both";

                Integer msgIndex = store.msgIndexFor(turn);
                if (msgIndex == null) {
                    return "No checkpoint for turn " + turn + ".\nAvailable: " +
                        checkpoints.stream().map(c -> String.valueOf(c.turn()))
                            .collect(java.util.stream.Collectors.joining(", "));
                }

                StringBuilder result = new StringBuilder();
                if ("code".equals(scope) || "both".equals(scope)) {
                    var rr = store.restoreCode(turn);
                    if (!rr.isEmpty()) {
                        result.append("Code rewind — ");
                        if (!rr.written().isEmpty()) result.append(rr.written().size()).append(" file(s) restored");
                        if (!rr.deleted().isEmpty()) {
                            if (!rr.written().isEmpty()) result.append(", ");
                            result.append(rr.deleted().size()).append(" file(s) deleted");
                        }
                        result.append(".\n");
                    } else {
                        result.append("Code rewind — no files affected.\n");
                    }
                }
                if ("conversation".equals(scope) || "both".equals(scope)) {
                    var history = agent.getHistory();
                    if (msgIndex < history.size()) {
                        java.util.List<dev.langchain4j.data.message.ChatMessage> truncated =
                            new ArrayList<>(history.subList(0, msgIndex));
                        agent.replaceHistory(truncated);
                        int removed = history.size() - truncated.size();
                        result.append("Conversation rewind — trimmed ")
                              .append(removed).append(" message(s).\n");
                    } else {
                        result.append("Conversation rewind — already at that point.\n");
                    }
                }
                return result.toString().trim();
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "goal"; }
            @Override public String description() { return "Set/check/clear long-running goal"; }
            @Override public String execute(String args, SlashContext ctx) {
                var agent = ctx.agent();
                var gm = agent.getGoalMachine();
                if (gm == null) {
                    return "Goal machine is not available.";
                }
                if (args.isBlank()) {
                    if (!gm.isActive()) {
                        return "No active goal. Usage: /goal <objective>\n/goal clear — stop current goal";
                    }
                    return "Goal (" + gm.statusText() + "): " + gm.goalText();
                }
                if ("clear".equalsIgnoreCase(args.strip())) {
                    gm.setGoal("");
                    return "Goal cleared.";
                }
                gm.setGoal(args.strip());
                return "Goal set: " + gm.goalText() +
                    "\nThe agent will pursue this autonomously across turns.";
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "exit"; }
            @Override public String description() { return "Exit Anima (saves session)"; }
            @Override public String execute(String args, SlashContext ctx) {
                return "__EXIT__"; // special marker for TerminalUI to break the loop
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "quit"; }
            @Override public String description() { return "Exit Anima (alias for /exit)"; }
            @Override public String execute(String args, SlashContext ctx) {
                return "__EXIT__";
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "memory"; }
            @Override public String description() { return "Show loaded project memory files"; }
            @Override public String execute(String args, SlashContext ctx) {
                // This needs access to ProjectMemory — handled in TerminalUI
                return null; // TerminalUI overrides this
            }
        });

        register(new SlashCommand() {
            @Override public String name() { return "init"; }
            @Override public String description() { return "Analyze project and generate/update ANIMA.md"; }
            @Override public String execute(String args, SlashContext ctx) {
                return null; // handled in TerminalUI directly
            }
        });
    }
}
