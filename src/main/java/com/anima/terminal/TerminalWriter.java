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
        dim("  " + appDesc);
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

    /** Start of turn — Claude-style "●" indicator, NO newline. */
    public void turnStart() {
        terminal.writer().print(new AttributedString("  ●",
            AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8)).toAnsi());
        terminal.writer().flush();
    }

    /** Phase heading — dim ⎿ prefix for metadata (coordinator phase, etc.). */
    public void phase(String label) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8), "  ⎿  " + label);
        br();
    }

    /** Start of reasoning — gray italic, continues from ●. */
    public void thinkingHeader() {
        terminal.writer().print(new AttributedString(" ",
            AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8).italic()).toAnsi());
        terminal.writer().flush();
    }

    /** Stream a reasoning token — gray italic, no newline. */
    public void thinkingToken(String token) {
        terminal.writer().print(new AttributedString(token,
            AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8).italic()).toAnsi());
        terminal.writer().flush();
    }

    /** End reasoning — newline before response. */
    public void thinkingEnd() {
        terminal.writer().println();
        terminal.writer().flush();
    }

    /** ⏺ Tool(args) — cyan, with smart arg formatting (Claude Code style). */
    public void toolCall(String name, String args) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN),
            "  ⏺ " + name + "(" + abbreviate(args, 100) + ")");
        br();
    }

    /** ⎿ result — gray */
    public void toolResult(String result) {
        styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8), "  ⎿  " + abbreviate(result, 200));
        br();
    }

    /** ⎿ Done (X tool uses · Y tokens · Z.Zs) */
    public void done(int toolUses, int promptTokens, int completionTokens, double elapsedSec) {
        String line = "\u001B[38;5;244m  ⎿  Done (" + toolUses + " tool use" + (toolUses != 1 ? "s" : "") + " · " +
            formatTokens(promptTokens + completionTokens) + " tokens · " + String.format("%.1f", elapsedSec) + "s)\u001B[0m";
        terminal.writer().println(line);
        terminal.writer().flush();
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
        String line = "\u001B[1;36m" + mode + "\u001B[0m mode · " +
                      "\u001B[36m" + model + "\u001B[0m · " +
                      "\u001B[36m" + toolCount + "\u001B[0m tools · " +
                      "\u001B[36m" + formatTokens(tokens) + "\u001B[0m tokens used";
        terminal.writer().println(line);
        terminal.writer().flush();
    }

    // ── Primitive helpers ──

    public void plain(String text) {
        terminal.writer().println(text);
        terminal.writer().flush();
    }

    /** Print styled text without newline — used by MarkdownRenderer for inline highlighting. */
    public void printStyled(String text, AttributedStyle style) {
        terminal.writer().print(new AttributedString(text, style).toAnsi());
    }

    /** Print raw text without newline — used by MarkdownRenderer. */
    public void printRaw(String text) {
        terminal.writer().print(text);
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
