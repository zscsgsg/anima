package com.anima.skill;

import java.util.List;

/**
 * Generates the pinned skills index block for the system prompt.
 * Mirrors Reasonix's {@code internal/skill/index.go}.
 *
 * <p>Only names + descriptions (+ subagent tag) enter the prompt prefix;
 * bodies are loaded on demand via run_skill. Max ~4000 chars to prevent
 * cache bloat.
 */
public final class SkillIndex {

    private static final int MAX_CHARS = 4000;
    private static final int MAX_LINE_WIDTH = 130;

    private static final String INDEX_HEADER = """
        # Skills — playbooks you can invoke

        One-liner index. Before non-trivial work, scan it: if an untagged (inline) \
        skill is even plausibly relevant to the task, invoke it before continuing \
        instead of pre-judging — loading one imperfect inline skill is cheap. Skills \
        tagged `[🧬 subagent]` are the heavy path; reach for them only when the task \
        genuinely needs context-heavy work, not on weak relevance. Each entry is a \
        built-in or a user-authored playbook. Call `run_skill({ name: "<skill-name>", \
        arguments: "<task>" })` — `name` is JUST the identifier (e.g. "explore"), NOT \
        the `[🧬 subagent]` tag that follows it. Prefer the dedicated top-level tool \
        when one exists for a built-in subagent skill. Entries tagged `[🧬 subagent]` \
        spawn an isolated subagent — its tool calls and reasoning never enter your \
        context, only its final answer does; use them for context-heavy work (deep \
        exploration, multi-step research) where you only need the conclusion. Untagged \
        skills are inlined: the body becomes a tool result you read and act on directly. \
        The user can also invoke a skill via `/<name>`.""";

    /**
     * Appends the skills index to the base system prompt, or returns it unchanged
     * when there are no skills.
     */
    public static String applyIndex(String basePrompt, List<Skill> skills) {
        String block = indexBlock(skills);
        if (block.isEmpty()) return basePrompt;
        return basePrompt + "\n\n" + block;
    }

    /**
     * Renders the skills index block (standalone).
     */
    public static String indexBlock(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (Skill sk : skills) {
            sb.append(indexLine(sk)).append("\n");
        }

        String joined = sb.toString();
        if (joined.codePointCount(0, joined.length()) > MAX_CHARS) {
            int truncateAt = offsetByCodePoints(joined, 0, MAX_CHARS);
            joined = joined.substring(0, truncateAt) +
                "\n… (truncated " +
                (joined.codePointCount(0, joined.length()) - MAX_CHARS) + " chars)";
        }

        return INDEX_HEADER + "\n\n```\n" + joined + "```";
    }

    /**
     * Renders one skill as "- name [tag] — description", clipped to stable width.
     * The subagent tag goes AFTER the name so copying into run_skill's name arg
     * still yields a clean identifier.
     */
    private static String indexLine(Skill sk) {
        String desc = sk.description() != null
            ? sk.description().replace("\n", " ").trim()
            : "(no description)";
        String tag = sk.runAs() == Skill.RunAs.SUBAGENT ? " [🧬 subagent]" : "";

        int maxDesc = MAX_LINE_WIDTH - sk.name().codePointCount(0, sk.name().length())
            - tag.codePointCount(0, tag.length());
        String clipped = clipCodePoints(desc, Math.max(maxDesc, 5));

        return "- " + sk.name() + tag + " — " + clipped;
    }

    private static String clipCodePoints(String s, int max) {
        if (max < 1) max = 1;
        int count = s.codePointCount(0, s.length());
        if (count <= max) return s;
        if (max - 1 < 1) return s.substring(0, s.offsetByCodePoints(0, 1));
        return s.substring(0, offsetByCodePoints(s, 0, max - 1)) + "…";
    }

    private static int offsetByCodePoints(String s, int start, int n) {
        int offset = s.offsetByCodePoints(start, Math.min(n, s.codePointCount(start, s.length())));
        return Math.min(offset, s.length());
    }
}
