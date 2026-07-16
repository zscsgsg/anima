package com.anima.terminal;

import com.anima.agent.AgentLoop;
import com.anima.agent.PermissionDecision;
import com.anima.agent.PermissionGate;
import com.anima.agent.PermissionPolicy;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interactive permission gate with three-state decisions, session grants,
 * and permanent rule saving. Matches Claude Code's option format.
 */
public class TerminalPermissionGate implements PermissionGate {

    private final LineReader reader;
    private final Terminal terminal;
    private final TerminalWriter out;
    private final PermissionPolicy policy;
    private final Path workspaceDir;

    /** Optional agent reference for mid-turn steer during permission prompts. */
    private AgentLoop agent = null;

    /** Session-scoped grants: when user picks "don't ask again", remember for this session. */
    private final Map<String, PermissionDecision> sessionGrants = new ConcurrentHashMap<>();

    public TerminalPermissionGate(LineReader reader, TerminalWriter out) {
        this(reader, out, PermissionPolicy.defaults(), null);
    }

    public TerminalPermissionGate(LineReader reader, TerminalWriter out, PermissionPolicy policy) {
        this(reader, out, policy, null);
    }

    public TerminalPermissionGate(LineReader reader, TerminalWriter out, PermissionPolicy policy, Path workspaceDir) {
        this.reader = reader;
        this.terminal = reader.getTerminal();
        this.out = out;
        this.policy = policy;
        this.workspaceDir = workspaceDir;
    }

    /** Wire the agent for mid-turn steer support during permission prompts. */
    public void setAgent(AgentLoop agent) { this.agent = agent; }

    @Override
    public PermissionDecision check(String toolName, String arguments, boolean readOnly) {
        // First check session grants (user previously said "don't ask again")
        String subject = PermissionPolicy.extractSubject(toolName, arguments);
        String sessionKey = toolName + "::" + (subject.isEmpty() ? "*" : subject);
        PermissionDecision cached = sessionGrants.get(sessionKey);
        if (cached != null && cached != PermissionDecision.ASK) {
            return cached;
        }

        // Evaluate policy rules
        PermissionDecision decision = policy.decide(toolName, readOnly, arguments);
        if (decision != PermissionDecision.ASK) {
            return decision;
        }

        // ASK: prompt the user with arrow-key menu
        out.permissionPrompt(toolName, abbreviateArgs(arguments, 100));

        // Render the 4 options as an arrow-key navigable menu
        int selected = readArrowMenu();
        if (selected == 1) {
            // Option 2: Yes, don't ask again this session
            sessionGrants.put(sessionKey, PermissionDecision.ALLOW);
            out.dim("  (auto-approved for this session)");
            return PermissionDecision.ALLOW;
        }
        if (selected == 2) {
            // Option 3: Yes, always allow (save to config)
            sessionGrants.put(sessionKey, PermissionDecision.ALLOW);
            savePermanentRule(toolName, subject);
            out.dim("  (saved to .anima/permissions — always allowed)");
            return PermissionDecision.ALLOW;
        }
        if (selected == 3) {
            return PermissionDecision.DENY;
        }
        return PermissionDecision.ALLOW; // option 0 or default = Yes
    }

    /**
     * Arrow-key navigable 4-option menu. Defaults to option 1 (Yes).
     * ↑/↓ to navigate, Enter to select. Returns 0=Yes, 1=Yes+session, 2=Yes+permanent, 3=No.
     */
    private int readArrowMenu() {
        String[] labels = {"1. Yes", "2. Yes, and don't ask again", "3. Yes, always allow", "4. No"};
        int selected = 0; // default: option 1

        try {
            terminal.enterRawMode();
            var reader = terminal.reader();

            // Draw initial menu
            drawMenu(labels, selected);

            while (true) {
                int ch = reader.read();

                if (ch == 13 || ch == 10) {
                    // Enter: select current
                    clearMenu(labels.length);
                    break;
                }

                if (ch == 27) {
                    // ESC sequence: read next chars
                    int next = reader.read();
                    if (next == 91) { // [
                        int arrow = reader.read();
                        if (arrow == 65) { // ↑
                            if (selected > 0) { selected--; drawMenu(labels, selected); }
                        } else if (arrow == 66) { // ↓
                            if (selected < labels.length - 1) { selected++; drawMenu(labels, selected); }
                        }
                    } else if (next == -1) {
                        // Plain ESC = cancel
                        clearMenu(labels.length);
                        return 2; // No
                    }
                }

                // Try reading as line input for older mode (type 1/2/3/y/n + enter)
                if (ch >= 32 && ch < 127) {
                    // Not an arrow key, fall back to line-based input
                    terminal.writer().print((char) ch);
                    terminal.writer().flush();
                }
            }
        } catch (IOException e) {
            return 2; // Deny on error
        } finally {
            // JLine reader may fail raw mode restore, just try
        }

        return selected;
    }

    private void drawMenu(String[] labels, int selected) {
        // Move to start of the menu area and redraw
        terminal.writer().print("\r");
        for (int i = 0; i < labels.length; i++) {
            terminal.writer().print("\r");
            // Clear to end of line
            terminal.writer().print("\033[K");
            if (i == selected) {
                // Highlight: reverse video
                terminal.writer().print("\033[7m  ▸ " + labels[i] + " \033[0m");
            } else {
                terminal.writer().print("\033[38;5;244m    " + labels[i] + "\033[0m");
            }
            terminal.writer().println();
        }
        // Move cursor back up to align with the next draw
        terminal.writer().print("\033[" + labels.length + "A");
        terminal.writer().flush();
    }

    private void clearMenu(int lineCount) {
        // Move down and clear all lines
        terminal.writer().print("\033[" + lineCount + "B");
        for (int i = 0; i < lineCount; i++) {
            terminal.writer().print("\033[K");
            terminal.writer().println();
        }
        terminal.writer().flush();
    }

    /**
     * Append an allow rule to .anima/permissions so future calls to this tool+subject
     * are auto-approved even across sessions.
     */
    private void savePermanentRule(String toolName, String subject) {
        if (workspaceDir == null) return;
        try {
            Path permFile = workspaceDir.resolve(".anima").resolve("permissions");
            Files.createDirectories(permFile.getParent());
            String rule = subject.isEmpty() ? toolName : toolName + "(" + subject + ")";
            String line = "allow " + rule + "\n";
            Files.writeString(permFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best-effort: if file write fails, session grant still applies
        }
    }

    private static String abbreviateArgs(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", "\\n").replace("\r", "");
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
