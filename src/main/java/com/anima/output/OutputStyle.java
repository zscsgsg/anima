package com.anima.output;

/**
 * Output style — a selectable persona that shifts how the agent communicates.
 * Mirrors Reasonix's {@code internal/outputstyle/outputstyle.go}.
 *
 * <p>Built-in styles:
 * <ul>
 *   <li><b>default</b> — unmodified system prompt (no style)</li>
 *   <li><b>explanatory</b> — surface reasoning behind non-obvious choices</li>
 *   <li><b>learning</b> — collaborative mode with TODO(human) stubs</li>
 *   <li><b>concise</b> — terse replies, code and bullets only</li>
 * </ul>
 */
public record OutputStyle(String name, String description, String body,
                           boolean keepCoding, boolean builtin) {

    public static final OutputStyle DEFAULT = new OutputStyle(
        "default", "Unmodified coding agent behaviour", "", true, true);

    public static final OutputStyle EXPLANATORY = new OutputStyle(
        "explanatory",
        "Explain non-obvious implementation choices as you go",
        "Communication style — Explanatory: as you work, surface the reasoning behind " +
            "non-obvious choices. After a substantive change, add a short \"## Insight\" note " +
            "covering the key trade-off or why an alternative was rejected. Teach the why, " +
            "not just the what; keep it brief.",
        true, true);

    public static final OutputStyle LEARNING = new OutputStyle(
        "learning",
        "Collaborate and leave TODO(human) stubs for the user to complete",
        "Communication style — Learning: work collaboratively rather than doing everything. " +
            "When a meaningful implementation decision comes up, pause and ask the user to make the call. " +
            "For the most instructive pieces, write the surrounding code but leave a small, clearly-marked " +
            "`TODO(human)` stub with a one-line description for the user to implement themselves.",
        true, true);

    public static final OutputStyle CONCISE = new OutputStyle(
        "concise",
        "Terse replies: minimal prose, code and bullets only",
        "Communication style — Concise: keep replies terse. No preamble or postamble, no restating " +
            "the request. Prefer code and short bullet points over paragraphs; answer in the fewest words " +
            "that are still clear.",
        true, true);

    /** All built-in styles. */
    public static final OutputStyle[] BUILTINS = { DEFAULT, EXPLANATORY, LEARNING, CONCISE };

    /** Resolve by name (case-insensitive). Returns DEFAULT for unknown names. */
    public static OutputStyle resolve(String name) {
        if (name == null || name.isBlank()) return DEFAULT;
        for (var s : BUILTINS) {
            if (s.name().equalsIgnoreCase(name.trim())) return s;
        }
        return DEFAULT;
    }

    /** Apply this style to the system prompt. */
    public String applyTo(String systemPrompt) {
        if (this == DEFAULT || body.isEmpty()) return systemPrompt;
        if (keepCoding) return systemPrompt + "\n\n" + body;
        return body;
    }
}
