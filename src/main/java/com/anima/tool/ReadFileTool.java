package com.anima.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a file from the workspace and returns its contents.
 */
public class ReadFileTool implements Tool {

    @Override
    public String name() { return "read_file"; }

    @Override
    public String description() {
        return "Read the contents of a file. Use this to inspect source code, config files, or any text file.";
    }

    @Override
    public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "file_path": {
              "type": "string",
              "description": "Relative or absolute path to the file to read (alias: path)"
            },
            "path": {
              "type": "string",
              "description": "Relative or absolute path to the file to read (alias: file_path)"
            }
          }
        }
        """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        // Accept both "path" and "file_path" — models often use file_path (Claude Code convention)
        String pathStr = json.has("path") ? json.get("path").asText() :
                         json.has("file_path") ? json.get("file_path").asText() : null;
        if (pathStr == null) {
            return "Error: missing 'path' or 'file_path' argument";
        }
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(pathStr).normalize();
        }
        if (!Files.exists(path)) {
            return "Error: file not found: " + path;
        }
        if (Files.isDirectory(path)) {
            return "Error: path is a directory, not a file: " + path;
        }
        String content = Files.readString(path);
        if (content.length() > 32_000) {
            content = content.substring(0, 32_000) + "\n... (truncated, " + (content.length() - 32_000) + " more chars)";
        }
        return content;
    }
}
