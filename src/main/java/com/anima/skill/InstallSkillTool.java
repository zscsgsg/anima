package com.anima.skill;

import com.anima.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Author and save a new skill — a reusable playbook future turns invoke via run_skill.
 * Mirrors Reasonix's {@code internal/skill/tools.go installSkillTool}.
 */
public class InstallSkillTool implements Tool {

    private final SkillStore store;

    /** Optional callback when a skill is installed (for UI refresh). */
    @FunctionalInterface
    public interface InstalledHook {
        void onInstalled(String name, String path, Skill.Scope scope);
    }

    private final InstalledHook onInstalled;

    public InstallSkillTool(SkillStore store, InstalledHook onInstalled) {
        this.store = store;
        this.onInstalled = onInstalled;
    }

    @Override
    public String name() { return "install_skill"; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public String description() {
        String scope = store.hasProjectScope()
            ? "'project' (default) writes to <workspace>/.anima/skills/ (this project only); " +
              "'global' writes to ~/.anima/skills/ (every project)"
            : "'global' (only option — no project workspace) writes to ~/.anima/skills/";
        return "Author and save a new skill — a reusable playbook future turns invoke via run_skill (or /<name>). " +
            "Runnable immediately this turn; appears in the pinned Skills index on the next launch. " + scope;
    }

    @Override
    public String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Identifier — letters/digits/_/-/., 1-64 chars, starts alphanumeric. Becomes the skill folder name under the selected skills directory."
                },
                "description": {
                  "type": "string",
                  "description": "≤120-char one-liner shown in the pinned Skills index — future agents read it to decide whether to invoke."
                },
                "body": {
                  "type": "string",
                  "description": "Markdown playbook. For subagent skills, write the subagent's persona/rules — it gets no context besides 'arguments' at runtime."
                },
                "scope": {
                  "type": "string",
                  "enum": ["project", "global"],
                  "description": "Where to write. Defaults to project when a workspace exists, else global."
                },
                "runAs": {
                  "type": "string",
                  "enum": ["inline", "subagent"],
                  "description": "inline (default) folds the body into the parent turn; subagent spawns an isolated child loop returning only its final answer (use for context-heavy work)."
                },
                "model": {
                  "type": "string",
                  "description": "Optional model override for runAs=subagent (a configured provider/model name). Ignored otherwise."
                },
                "effort": {
                  "type": "string",
                  "description": "Optional effort for runAs=subagent (e.g. high, max). Ignored otherwise."
                },
                "allowedTools": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "Optional tool allowlist for runAs=subagent (e.g. ['read_file','grep'])."
                }
              },
              "required": ["name", "description", "body"]
            }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode args = MAPPER.readTree(arguments);

        String name = args.has("name") ? args.get("name").asText().trim() : "";
        String desc = args.has("description") ? args.get("description").asText().trim() : "";
        String body = args.has("body") ? args.get("body").asText().trim() : "";

        if (name.isEmpty()) return error("install_skill requires a non-empty 'name'");
        if (desc.isEmpty()) return error("install_skill requires a non-empty 'description' — it is what appears in the Skills index");
        if (body.isEmpty()) return error("install_skill requires a non-empty 'body' — the playbook the skill executes");

        // Collapse multi-line description
        desc = desc.replaceAll("\\s+", " ").trim();

        Skill.Scope scope = resolveScope(args);
        if (scope == Skill.Scope.PROJECT && !store.hasProjectScope()) {
            return error("install_skill: scope='project' requires a workspace — use scope='global'");
        }

        Skill.RunAs runAs = Skill.RunAs.parse(args.has("runAs") ? args.get("runAs").asText() : "");
        String model = args.has("model") ? args.get("model").asText().trim() : null;
        String effort = args.has("effort") ? args.get("effort").asText().trim() : null;

        List<String> allowedTools = new ArrayList<>();
        if (args.has("allowedTools") && args.get("allowedTools").isArray()) {
            for (JsonNode t : args.get("allowedTools")) {
                String toolName = t.asText().trim();
                if (!toolName.isEmpty()) allowedTools.add(toolName);
            }
        }

        try {
            String content = SkillStore.renderSkillFile(name, desc, body, runAs, model, effort, allowedTools);
            String path = store.createWithContent(name, scope, content);

            if (onInstalled != null) {
                onInstalled.onInstalled(name, path, scope);
            }

            return MAPPER.writeValueAsString(Map.of(
                "ok", true,
                "name", name,
                "scope", scope.toString(),
                "path", path,
                "runAs", runAs.toString(),
                "note", "Callable now via run_skill({name}) or /" + name +
                    ". Appears in the pinned Skills index on the next launch."
            ));
        } catch (Exception e) {
            return error("install_skill: " + e.getMessage());
        }
    }

    private Skill.Scope resolveScope(JsonNode args) {
        if (!args.has("scope")) {
            return store.hasProjectScope() ? Skill.Scope.PROJECT : Skill.Scope.GLOBAL;
        }
        return "global".equalsIgnoreCase(args.get("scope").asText())
            ? Skill.Scope.GLOBAL : Skill.Scope.PROJECT;
    }

    private static String error(String msg) {
        return "Error: " + msg;
    }
}
