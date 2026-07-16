package com.anima.skill;

import java.util.List;

/**
 * A loaded playbook — either a user-authored SKILL.md or a built-in.
 * Mirrors Reasonix's {@code internal/skill/skill.go Skill} struct.
 */
public record Skill(
    String name,
    String description,
    String body,
    Scope scope,
    String path,
    List<String> allowedTools,
    RunAs runAs,
    String model,
    String effort
) {

    public enum Scope {
        PROJECT("project"),
        CUSTOM("custom"),
        GLOBAL("global"),
        BUILTIN("builtin");

        private final String label;

        Scope(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    public enum RunAs {
        INLINE("inline"),
        SUBAGENT("subagent");

        private final String label;

        RunAs(String label) { this.label = label; }

        @Override
        public String toString() { return label; }

        public static RunAs parse(String s) {
            if (s == null) return INLINE;
            return "subagent".equalsIgnoreCase(s.trim()) ? SUBAGENT : INLINE;
        }
    }

    /** Canonical name validation: letters/digits/_/-/., 1-64 chars, starts alphanumeric. */
    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        char first = name.charAt(0);
        if (!Character.isLetterOrDigit(first)) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') return false;
        }
        return true;
    }

    /** Render the full inline invocation text for this skill. */
    public String render(String arguments) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Skill: ").append(name).append("\n");
        if (description != null && !description.isEmpty()) {
            sb.append("> ").append(description).append("\n");
        }
        sb.append("(scope: ").append(scope).append(" · ").append(path).append(")\n\n");
        sb.append(body);
        if (arguments != null && !arguments.isEmpty()) {
            sb.append("\n\nArguments: ").append(arguments);
        }
        return sb.toString();
    }

    /** Render wrapped in skill-pin sentinel for context compaction preservation. */
    public String renderInline(String arguments) {
        return "<skill-pin name=\"" + name + "\">\n" + render(arguments) + "\n</skill-pin>";
    }
}
