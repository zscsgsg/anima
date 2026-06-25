package com.anima.terminal;

import com.anima.agent.AgentLoop;
import com.anima.agent.AgentLoop.LoopCallback;
import com.anima.agent.PermissionGate;
import com.anima.llm.DeepSeekProvider;
import com.anima.tool.*;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;

/**
 * JLine3-based terminal REPL — Claude Code 1:1 experience.
 * No HTTP, no browser. Direct AgentLoop invocation.
 */
public class TerminalUI {

    private static final String SYSTEM_PROMPT = """
        You are Anima, a terminal AI coding agent.
        You are running on Windows (cmd.exe). Use Windows commands in bash: dir, type, findstr.
        Use ls, glob, grep for cross-platform tasks — they work everywhere without confirmation.
        Use read_file to inspect file contents — never guess.
        When the user asks you to do something, use the appropriate tool.
        After seeing tool results, synthesize a final answer.
        Respond in the same language as the user.
        """;

    public void start() throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .jna(true)
                .encoding(System.getProperty("native.encoding", "UTF-8"))
                .build();

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();

        var tw = new TerminalWriter(terminal);

        // Banner
        tw.banner("⬡ Anima v0.5.0 — terminal AI coding agent");

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

        // Permission gate
        PermissionGate gate = new TerminalPermissionGate(reader, tw);

        // Provider (recreated per session for fresh API key)
        DeepSeekProvider provider;
        try {
            provider = new DeepSeekProvider();
        } catch (IllegalStateException e) {
            tw.error(e.getMessage());
            tw.plain("Set DEEPSEEK_API_KEY environment variable.");
            terminal.close();
            return;
        }

        var agent = new AgentLoop(provider, tools, 10, SYSTEM_PROMPT, gate);

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
            if (input.equalsIgnoreCase("/exit") || input.equalsIgnoreCase("/quit")) break;

            // JLine already showed the input, don't echo it
            var stats = new Object() { int toolUses = 0; int promptT = 0; int compT = 0; long start = System.currentTimeMillis(); boolean thinkingDone = false; };

            tw.thinking();

            agent.run(input, new LoopCallback() {
                @Override public void onUserMessage(String t) { /* already shown */ }
                @Override public void onThinking(String t) {
                    if (!stats.thinkingDone) { stats.thinkingDone = true; }
                    // thinking tokens are streamed internally; we just show "thinking…" once
                }
                @Override public void onResponse(String t) {
                    tw.respondStream(t);
                }
                @Override public void onToolStart(String name, String args) {
                    tw.toolCall(name, args);
                }
                @Override public void onToolPermissionDenied(String name, String args) {
                    tw.dim("  ⎿  Denied: " + name);
                }
                @Override public void onToolResult(String name, String result) {
                    stats.toolUses++;
                    tw.toolResult(result);
                }
                @Override public void onUsage(DeepSeekProvider.Usage u) {
                    stats.promptT += u.promptTokens();
                    stats.compT += u.completionTokens();
                }
                @Override public void onComplete(String text) {
                    tw.br();
                    tw.done(stats.toolUses, stats.promptT, stats.compT,
                        (System.currentTimeMillis() - stats.start) / 1000.0);
                    tw.statusLine("default", "deepseek-v4-flash", tools.list().size(), stats.promptT + stats.compT);
                }
                @Override public void onError(Throwable e) {
                    tw.error(e.getMessage() != null ? e.getMessage() : "Unknown error");
                }
            });
        }

        tw.plain("bye.");
        terminal.close();
    }
}
