package com.anima.agent;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.List;

/**
 * A single permission rule: a tool name plus an optional subject constraint.
 * Mirrors Reasonix's Rule struct and ParseRule function.
 *
 * <p>Rule syntax:
 * <ul>
 *   <li>{@code "ToolName"} — matches every call to ToolName</li>
 *   <li>{@code "ToolName(subject)"} — matches when the call's subject matches the glob</li>
 *   <li>{@code "ToolName=literal"} — legacy form, exact string match (no glob)</li>
 * </ul>
 */
public record PermissionRule(String tool, String subject, boolean literal) {

    public static PermissionRule parse(String s) {
        s = s.strip();
        if (s.isEmpty()) return null;

        int eq = s.indexOf('=');
        int paren = s.indexOf('(');
        if (eq > 0 && (paren < 0 || eq < paren)) {
            String tool = s.substring(0, eq).strip();
            if (tool.isEmpty()) return null;
            return new PermissionRule(tool, s.substring(eq + 1), true);
        }

        if (paren >= 0 && s.endsWith(")")) {
            String tool = s.substring(0, paren).strip();
            if (tool.isEmpty()) return null;
            return new PermissionRule(tool, s.substring(paren + 1, s.length() - 1), false);
        }

        return new PermissionRule(s, "", false);
    }

    public boolean matches(String toolName, String callSubject) {
        if (!ruleToolMatches(this.tool, toolName)) return false;
        if (this.subject.isEmpty()) return true;
        if (callSubject == null || callSubject.isEmpty()) return false;
        if (this.literal) return this.subject.equals(callSubject);
        return matchGlob(this.subject, callSubject);
    }

    private static boolean ruleToolMatches(String ruleTool, String callTool) {
        String a = canonicalName(ruleTool);
        String b = canonicalName(callTool);
        if (a.equals(b)) return true;
        if (a.equals("edit")) {
            return b.equals("write_file") || b.equals("edit_file")
                || b.equals("multi_edit") || b.equals("move_file");
        }
        return false;
    }

    private static String canonicalName(String name) { return name.toLowerCase().strip(); }

    private static boolean matchGlob(String glob, String subject) {
        try {
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob.replace("\\", "\\\\"));
            return m.matches(java.nio.file.Path.of(subject));
        } catch (Exception e) {
            return simpleMatch(glob, subject);
        }
    }

    static boolean simpleMatch(String pattern, String text) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        if (pattern.endsWith(":*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            if (!text.startsWith(prefix)) return false;
            return !containsShellOperators(text.substring(prefix.length()));
        }
        return text.matches(regex);
    }

    static boolean containsShellOperators(String s) {
        for (String op : List.of("&&", "||", ">>", "<<", "$(", "`", ";", "|", ">", "<", "&", "\n", "\r"))
            if (s.contains(op)) return true;
        return false;
    }

    @Override
    public String toString() {
        return subject.isEmpty() ? tool : tool + "(" + subject + ")";
    }
}
