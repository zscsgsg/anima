package com.anima.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Apply multiple edits to a single file atomically.
 * If any edit fails, the file is NOT written — safer than chaining edit_file calls.
 * Mirrors Reasonix's multi_edit.
 */
public class MultiEditTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "multi_edit"; }

    @Override public String description() {
        return "Apply a list of edits to a single file atomically. " +
               "Each edit runs against the result of the previous one, all in memory; " +
               "the file is rewritten only if every edit succeeds. " +
               "Cheaper and safer than chaining edit_file calls.";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "File path (relative or absolute)"},
            "edits": {
              "type": "array",
              "minItems": 1,
              "description": "Ordered edits. Each step sees the file as left by the previous step.",
              "items": {
                "type": "object",
                "properties": {
                  "old_string": {
                    "type": "string",
                    "description": "Exact text to find. Without replace_all, must match exactly once."
                  },
                  "new_string": {
                    "type": "string",
                    "description": "Replacement text (empty string deletes)."
                  },
                  "replace_all": {
                    "type": "boolean",
                    "description": "Replace every occurrence instead of requiring uniqueness."
                  }
                },
                "required": ["old_string", "new_string"]
              }
            }
          },
          "required": ["path", "edits"]
        }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode root = MAPPER.readTree(arguments);

        String pathStr = root.has("path") ? root.get("path").asText() :
                         root.has("file_path") ? root.get("file_path").asText() : null;
        if (pathStr == null) return "Error: missing 'path' or 'file_path' argument";
        if (!root.has("edits") || !root.get("edits").isArray()) {
            return "Error: 'edits' array is required";
        }

        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(pathStr).normalize();
        }
        if (!Files.exists(path)) return "Error: file not found: " + path;

        String content = Files.readString(path);
        var edits = root.get("edits");
        int applied = 0;

        for (int i = 0; i < edits.size(); i++) {
            JsonNode edit = edits.get(i);
            if (!edit.has("old_string")) return "Error: edit " + (i + 1) + ": old_string is required";
            String oldStr = edit.get("old_string").asText();
            String newStr = edit.has("new_string") ? edit.get("new_string").asText() : "";
            boolean replaceAll = edit.has("replace_all") && edit.get("replace_all").asBoolean();

            // CRLF adaptive matching
            String searchOld = oldStr;
            String replacement = newStr;
            if (!content.contains(searchOld) && content.contains("\r\n")) {
                String crlfOld = oldStr.replace("\r\n", "\n").replace("\n", "\r\n");
                if (content.contains(crlfOld)) {
                    searchOld = crlfOld;
                    replacement = newStr.replace("\r\n", "\n").replace("\n", "\r\n");
                }
            }

            if (replaceAll) {
                int count = countMatches(content, searchOld);
                if (count == 0) return "Error: edit " + (i + 1) + ": old_string not found";
                content = content.replace(searchOld, replacement);
                applied += count;
            } else {
                int idx = content.indexOf(searchOld);
                if (idx == -1) return "Error: edit " + (i + 1) + ": old_string not found";
                int second = content.indexOf(searchOld, idx + 1);
                if (second != -1) {
                    return "Error: edit " + (i + 1) + ": old_string is not unique (" +
                           countMatches(content, searchOld) + " matches). Add more context or set replace_all.";
                }
                content = content.substring(0, idx) + replacement + content.substring(idx + searchOld.length());
                applied++;
            }
        }

        Files.writeString(path, content);
        return "multi_edit " + path.getFileName() + ": " + edits.size() + " edits applied (" + applied + " total replacements)";
    }

    private static int countMatches(String s, String sub) {
        int count = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }
}
