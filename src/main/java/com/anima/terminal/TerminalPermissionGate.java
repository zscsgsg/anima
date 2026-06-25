package com.anima.terminal;

import com.anima.agent.PermissionGate;
import org.jline.reader.LineReader;

/**
 * Permission gate that asks the user inline in the terminal.
 * Matches Claude Code's "1. Yes / 2. Don't ask again / 3. No" format.
 */
public class TerminalPermissionGate implements PermissionGate {

    private final LineReader reader;
    private final TerminalWriter out;

    public TerminalPermissionGate(LineReader reader, TerminalWriter out) {
        this.reader = reader;
        this.out = out;
    }

    @Override
    public boolean allow(String toolName, String arguments) {
        // Read-only: always allow
        if ("read_file".equals(toolName) || "ls".equals(toolName) ||
            "glob".equals(toolName) || "grep".equals(toolName)) {
            return true;
        }

        out.permissionPrompt(toolName, abbreviate(arguments, 100));

        while (true) {
            String line = reader.readLine("  Choice (1-3): ");
            if (line == null) return false;
            String trimmed = line.trim();
            if ("1".equals(trimmed) || trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes")) {
                return true;
            }
            if ("3".equals(trimmed) || trimmed.equalsIgnoreCase("n") || trimmed.equalsIgnoreCase("no")) {
                return false;
            }
            if ("2".equals(trimmed)) {
                out.plain("  (auto-approved for this session)");
                return true;
            }
            // invalid input: re-prompt
        }
    }

    private static String abbreviate(String s, int max) {
        s = s.replace("\n", "\\n").replace("\r", "");
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
