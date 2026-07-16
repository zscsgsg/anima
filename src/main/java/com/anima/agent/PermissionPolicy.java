package com.anima.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, I/O-free permission policy engine.
 * Mirrors Reasonix's Policy struct + Decide method.
 *
 * <p>Precedence: deny &gt; ask &gt; allow &gt; fallback (Allow for readers, mode for writers).
 */
public class PermissionPolicy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PermissionDecision mode;
    private final List<PermissionRule> allowRules;
    private final List<PermissionRule> askRules;
    private final List<PermissionRule> denyRules;

    public PermissionPolicy(PermissionDecision mode,
                            List<PermissionRule> allowRules,
                            List<PermissionRule> askRules,
                            List<PermissionRule> denyRules) {
        this.mode = mode;
        this.allowRules = allowRules;
        this.askRules = askRules;
        this.denyRules = denyRules;
    }

    /** Default: ask for all writers, allow readers. */
    public static PermissionPolicy defaults() {
        return new PermissionPolicy(PermissionDecision.ASK, List.of(), List.of(), List.of());
    }

    /**
     * Evaluate a tool call. readOnly is the tool's own classification;
     * args is the raw JSON from the model, from which the call's subject is extracted.
     */
    public PermissionDecision decide(String toolName, boolean readOnly, String argsJson) {
        String subject = extractSubject(toolName, argsJson);

        if (matchAny(denyRules, toolName, subject))  return PermissionDecision.DENY;
        if (matchAny(askRules, toolName, subject))   return PermissionDecision.ASK;
        if (matchAny(allowRules, toolName, subject)) return PermissionDecision.ALLOW;
        if (readOnly) return PermissionDecision.ALLOW;

        return mode; // fallback for writers
    }

    /** Extract the "subject" from tool args — the thing being operated on. */
    public static String extractSubject(String toolName, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return "";
        try {
            JsonNode root = MAPPER.readTree(argsJson);
            // Bash: command is the subject
            if ("bash".equals(toolName)) {
                if (root.has("command")) return root.get("command").asText();
                if (root.has("cmd")) return root.get("cmd").asText();
                return "";
            }
            // Web fetch: URL is the subject
            if ("web_fetch".equals(toolName)) {
                if (root.has("url")) return root.get("url").asText();
                return "";
            }
            // File tools: path/file_path is the subject
            for (String key : List.of("file_path", "path", "file")) {
                if (root.has(key)) return root.get(key).asText();
            }
            // Grep/glob: pattern is the subject
            if (root.has("pattern")) return root.get("pattern").asText();
        } catch (Exception e) {
            return "";
        }
        return "";
    }

    private static boolean matchAny(List<PermissionRule> rules, String toolName, String subject) {
        for (PermissionRule r : rules) {
            if (r.matches(toolName, subject)) return true;
        }
        return false;
    }

    /**
     * Parse a comma-separated list of rule strings.
     */
    public static List<PermissionRule> parseRules(String rulesStr) {
        if (rulesStr == null || rulesStr.isBlank()) return List.of();
        List<PermissionRule> rules = new ArrayList<>();
        for (String part : rulesStr.split(",")) {
            PermissionRule r = PermissionRule.parse(part.strip());
            if (r != null) rules.add(r);
        }
        return rules;
    }
}
