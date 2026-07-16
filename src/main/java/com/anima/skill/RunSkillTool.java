package com.anima.skill;

import com.anima.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Invokes a skill by name. Inline skills fold body into the turn;
 * subagent skills spawn an isolated child loop returning only the final answer.
 * Mirrors Reasonix's {@code internal/skill/tools.go runSkillTool}.
 */
public class RunSkillTool implements Tool {

    private final SkillStore store;
    private final SubagentRunner runner;

    /**
     * @param store  skill discovery store
     * @param runner subagent executor (may be null; subagent skills will error if no runner)
     */
    public RunSkillTool(SkillStore store, SubagentRunner runner) {
        this.store = store;
        this.runner = runner;
    }

    @Override
    public String name() { return "run_skill"; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public String description() {
        return "Invoke a playbook from the Skills index pinned in the system prompt. " +
            "For the built-in subagent skills (explore / research / review / security_review), " +
            "prefer the dedicated top-level tools of the same name — they're easier to pick and do the same thing. " +
            "Pass `name` as the BARE identifier (e.g. 'explore'), NOT the `[🧬 subagent]` tag that follows it in the index. " +
            "`[🧬 subagent]` skills spawn an isolated subagent — only the final distilled answer returns; " +
            "supply `arguments` describing the concrete task since the subagent has no other context. " +
            "Untagged skills are inlined: the body becomes a tool result you read and follow.";
    }

    @Override
    public String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Skill identifier as it appears in the pinned Skills index (e.g. 'explore', 'review'). Case-sensitive. Just the identifier, not the [🧬 subagent] tag."
                },
                "arguments": {
                  "type": "string",
                  "description": "Free-form arguments. For inline skills: appended as an 'Arguments:' line; the skill's own instructions decide how to use them. For subagent skills: REQUIRED — becomes the entire task the subagent receives."
                }
              },
              "required": ["name"]
            }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode args = MAPPER.readTree(arguments);

        String rawName = args.has("name") ? args.get("name").asText() : "";
        String name = cleanSkillName(rawName);
        if (name.isEmpty()) {
            return "Error: run_skill requires a 'name' argument (got '" + rawName +
                "', which is just a marker/tag)";
        }

        var sk = store.read(name);
        if (sk.isEmpty()) {
            return "Error: unknown skill '" + name + "' — available: " + availableNames();
        }

        Skill skill = sk.get();
        String rawArgs = args.has("arguments") ? args.get("arguments").asText("") : "";

        if (skill.runAs() == Skill.RunAs.SUBAGENT) {
            if (runner == null) {
                return "Error: run_skill: skill '" + name +
                    "' is runAs=subagent but no subagent runner is configured in this session";
            }
            if (rawArgs.isBlank()) {
                return "Error: run_skill: skill '" + name +
                    "' is a subagent and requires 'arguments' — the subagent has no other context, " +
                    "so describe the concrete task";
            }
            return runner.run(skill, rawArgs.trim());
        }

        return skill.renderInline(rawArgs.trim());
    }

    private String availableNames() {
        var skills = store.list();
        if (skills.isEmpty()) return "(none — no skills defined)";
        return skills.stream().map(Skill::name).reduce((a, b) -> a + ", " + b).orElse("");
    }

    /** Strip [tag] decorations from name (models sometimes copy "explore [🧬 subagent]" verbatim). */
    static String cleanSkillName(String raw) {
        if (raw == null) return "";
        String stripped = raw.trim().replaceAll("\\[[^\\]]*\\]", "").trim();
        for (String token : stripped.split("\\s+")) {
            if (!token.isEmpty() && Character.isLetterOrDigit(token.charAt(0))) {
                return token;
            }
        }
        return "";
    }

    /** Functional interface for subagent execution. */
    @FunctionalInterface
    public interface SubagentRunner {
        String run(Skill skill, String task) throws Exception;
    }
}
