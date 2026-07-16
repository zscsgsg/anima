package com.anima.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Delete a range of lines from a file. Mirrors Reasonix's delete_range tool.
 *
 * <p>Lines are 1-based. start_line is inclusive, end_line is inclusive.
 * The remaining lines are joined back. If start_line > end_line, they are swapped.
 */
public class DeleteRangeTool implements Tool {

    private final Path workspaceRoot;

    public DeleteRangeTool(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Override public String name() { return "delete_range"; }

    @Override public boolean isReadOnly() { return false; }

    @Override
    public String description() {
        return "Delete a range of lines from a file (1-based, inclusive). " +
            "Use this to remove a contiguous block of lines by line number. " +
            "Prefer edit_file for precise string-based edits; use delete_range " +
            "when you know the exact line numbers to remove.";
    }

    @Override
    public String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Path to the file to edit, relative to workspace root."
                },
                "start_line": {
                  "type": "integer",
                  "description": "First line to delete (1-based, inclusive)."
                },
                "end_line": {
                  "type": "integer",
                  "description": "Last line to delete (1-based, inclusive). Defaults to start_line."
                }
              },
              "required": ["path", "start_line"]
            }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode args = MAPPER.readTree(arguments);

        String pathStr = args.has("path") ? args.get("path").asText().trim() : "";
        if (pathStr.isEmpty()) return "Error: path is required";

        int start = args.has("start_line") ? args.get("start_line").asInt() : 0;
        if (start <= 0) return "Error: start_line must be >= 1";

        int end = args.has("end_line") ? args.get("end_line").asInt() : start;
        if (end <= 0) end = start;

        if (start > end) { int tmp = start; start = end; end = tmp; }

        Path file = resolvePath(pathStr);
        if (!Files.exists(file)) return "Error: file not found: " + pathStr;

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (start > lines.size()) {
            return "Error: start_line " + start + " exceeds file length " + lines.size();
        }
        if (end > lines.size()) end = lines.size();

        var result = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i + 1 >= start && i + 1 <= end) continue;
            result.append(lines.get(i)).append('\n');
        }

        Files.writeString(file, result.toString(), StandardCharsets.UTF_8);

        int removed = end - start + 1;
        return "Deleted " + removed + " line(s) (L" + start + "-L" + end +
            ") from " + pathStr + ". " + lines.size() + " → " +
            (lines.size() - removed) + " lines.";
    }

    private Path resolvePath(String pathStr) {
        Path p = workspaceRoot.resolve(pathStr).normalize();
        if (!p.startsWith(workspaceRoot)) {
            throw new SecurityException("Path escapes workspace: " + pathStr);
        }
        return p;
    }
}
