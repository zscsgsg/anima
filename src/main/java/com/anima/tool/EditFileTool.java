package com.anima.tool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Precise string replacement in files. Mirrors Claude Code's Edit tool.
 * Finds old_string (must match exactly once) and replaces with new_string.
 */
public class EditFileTool implements Tool {

    @Override public String name() { return "edit_file"; }
    @Override public String description() {
        return "Make a precise edit to an existing file by replacing one exact string with another. The old_string must match exactly once in the file. Use this for targeted changes to existing code instead of rewriting the entire file.";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "file_path": {
              "type": "string",
              "description": "Path to the file to edit (relative or absolute)"
            },
            "old_string": {
              "type": "string",
              "description": "The exact text to find and replace. Must match exactly once in the file."
            },
            "new_string": {
              "type": "string",
              "description": "The text to replace it with"
            }
          },
          "required": ["file_path", "old_string", "new_string"]
        }
        """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        String pathStr = json.has("file_path") ? json.get("file_path").asText() : json.get("path").asText();
        String oldStr = json.get("old_string").asText();
        String newStr = json.get("new_string").asText();

        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(pathStr).normalize();
        }
        if (!Files.exists(path)) {
            return "Error: file not found: " + path;
        }

        String content = Files.readString(path);
        int idx = content.indexOf(oldStr);
        if (idx == -1) {
            return "Error: old_string not found in file. Make sure the text matches exactly, including whitespace and indentation.";
        }
        int secondIdx = content.indexOf(oldStr, idx + 1);
        if (secondIdx != -1) {
            return "Error: old_string matches " + countMatches(content, oldStr) + " times in the file. It must match exactly once. Add more surrounding context to make it unique.";
        }

        String result = content.substring(0, idx) + newStr + content.substring(idx + oldStr.length());
        Files.writeString(path, result);

        // Show a brief diff preview
        String preview = "Replaced in " + path.getFileName() + ":\n" +
            "  - " + truncate(oldStr, 120) + "\n" +
            "  + " + truncate(newStr, 120);
        return preview;
    }

    private static int countMatches(String s, String sub) {
        int count = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }

    private static String truncate(String s, int max) {
        String flat = s.replace("\n", "\\n").replace("\r", "\\r");
        return flat.length() > max ? flat.substring(0, max) + "..." : flat;
    }
}
