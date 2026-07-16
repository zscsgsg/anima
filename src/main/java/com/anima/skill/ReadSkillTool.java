package com.anima.skill;

import com.anima.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Loads an inline skill body into context without executing anything.
 * Read-only, so it works in plan mode (unlike run_skill).
 * Mirrors Reasonix's {@code internal/skill/tools.go readSkillTool}.
 */
public class ReadSkillTool implements Tool {

    private final SkillStore store;

    public ReadSkillTool(SkillStore store) {
        this.store = store;
    }

    @Override
    public String name() { return "read_skill"; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String description() {
        return "Load an inline playbook from the Skills index into your context WITHOUT running anything — " +
            "the skill body returns as a tool result you read and follow. Read-only, so it works in plan mode " +
            "(unlike run_skill). Pass `name` as the BARE identifier (e.g. 'commit'), NOT the `[🧬 subagent]` tag. " +
            "Subagent-tagged skills are rejected: use run_skill (or the dedicated tool) for those, " +
            "since they execute work.";
    }

    @Override
    public String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Inline skill identifier as it appears in the pinned Skills index. Just the identifier, not the [🧬 subagent] tag."
                },
                "arguments": {
                  "type": "string",
                  "description": "Optional free-form arguments, appended as an 'Arguments:' line; the skill's own instructions decide how to use them."
                }
              },
              "required": ["name"]
            }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode args = MAPPER.readTree(arguments);

        String rawName = args.has("name") ? args.get("name").asText() : "";
        String name = RunSkillTool.cleanSkillName(rawName);
        if (name.isEmpty()) {
            return "Error: read_skill requires a 'name' argument (got '" + rawName +
                "', which is just a marker/tag)";
        }

        var sk = store.read(name);
        if (sk.isEmpty()) {
            return "Error: unknown skill '" + name + "' — available: " + availableNames();
        }

        Skill skill = sk.get();
        if (skill.runAs() == Skill.RunAs.SUBAGENT) {
            return "Error: read_skill: skill '" + name +
                "' is a subagent and must be executed, not read — use run_skill (or the dedicated " +
                name + " tool)";
        }

        String rawArgs = args.has("arguments") ? args.get("arguments").asText("") : "";
        return skill.renderInline(rawArgs.trim());
    }

    private String availableNames() {
        var skills = store.list();
        if (skills.isEmpty()) return "(none — no skills defined)";
        return skills.stream().map(Skill::name).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
