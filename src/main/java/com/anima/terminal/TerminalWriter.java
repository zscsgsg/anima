package com.anima.terminal;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * ANSI-styled terminal output matching Claude Code's visual language.
 */
public class TerminalWriter {

    private final Terminal terminal;

    public TerminalWriter(Terminal terminal) {
        this.terminal = terminal;
    }

    // ── Claude Code output patterns ──

    public void banner(String appDesc) {
        dim("══════════════════════════════════════════════");
        bold(appDesc);
        dim("deepseek-v4-flash · 8 tools loaded");
        dim("══════════════════════════════════════════════");
        br();
    }

    public void userInput(String text) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE).bold(), "> " + text);
        br();
    }

    public void thinking() {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8).italic(), "  thinking…");
        br();
    }

    /** ⏺ Tool(args) — cyan bold */
    public void toolCall(String name, String args) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold(), "⏺ " + name + "(" + abbreviate(args, 80) + ")");
        br();
    }

    /** ⎿ result — gray */
    public void toolResult(String result) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8), "  ⎿  " + abbreviate(result, 200));
        br();
    }

    /** ⎿ Done (X tool uses · Y tokens · Z.Zs) */
    public void done(int toolUses, int promptTokens, int completionTokens, double elapsedSec) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8),
            "  ⎿  Done (" + toolUses + " tool use" + (toolUses != 1 ? "s" : "") + " · " +
            formatTokens(promptTokens + completionTokens) + " tokens · " + String.format("%.1f", elapsedSec) + "s)");
        br();
    }

    public void respond(String text) {
        plain(text);
    }

    public void respondStream(String text) {
        terminal.writer().print(text);
        terminal.writer().flush();
    }

    public void error(String msg) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED), "  ⎿  Error: " + msg);
        br();
    }

    // ── Permission (Claude format) ──

    public void permissionPrompt(String toolName, String detail) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW), "  " + toolName + " " + detail);
        plain("  Do you want to proceed?");
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold(), "  > 1. Yes");
        dim("    2. Yes, and don't ask again for this session");
        dim("    3. No");
    }

    // ── Status line ──

    public void statusLine(String mode, String model, int toolCount, int tokens) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold(), mode);
        terminal.writer().print(" mode · ");
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN), model);
        terminal.writer().print(" · ");
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN), String.valueOf(toolCount));
        terminal.writer().print(" tools · ");
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN), formatTokens(tokens));
        terminal.writer().print(" tokens used");
        terminal.writer().flush();
    }

    // ── Primitive helpers ──

    public void plain(String text) {
        terminal.writer().println(text);
        terminal.writer().flush();
    }

    public void dim(String text) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8), text);
        br();
    }

    public void bold(String text) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA).bold(), text);
        br();
    }

    public void br() {
        terminal.writer().println();
        terminal.writer().flush();
    }

    private void styled(AttributedStyle style, String text) {
        terminal.writer().println(new AttributedString(text, style).toAnsi());
        terminal.writer().flush();
    }

    private static String abbreviate(String s, int max) {
        s = s.replace("\n", "\\n").replace("\r", "");
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String formatTokens(int n) {
        if (n >= 1000) return String.format("%.1fk", n / 1000.0);
        return n + "";
    }
}
