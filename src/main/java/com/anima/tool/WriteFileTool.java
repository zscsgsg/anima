package com.anima.tool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates or overwrites a file. Mirrors Claude Code's Write tool.
 */
public class WriteFileTool implements Tool {

    @Override public String name() { return "write_file"; }
    @Override public String description() {
        return "Create a new file or completely overwrite an existing file. Use this to create new source files, config files, or any text file.";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "file_path": {
              "type": "string",
              "description": "Path to the file to write (relative or absolute)"
            },
            "content": {
              "type": "string",
              "description": "The complete file content to write"
            }
          },
          "required": ["file_path", "content"]
        }
        """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        String pathStr = json.has("file_path") ? json.get("file_path").asText() : json.get("path").asText();
        String content = json.get("content").asText();

        if (content == null || content.isEmpty()) {
            return "Error: content cannot be empty. Use bash rm to delete files.";
        }

        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(pathStr).normalize();
        }

        boolean existed = Files.exists(path);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);

        int lines = content.split("\n", -1).length;
        int bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return (existed ? "Updated" : "Created") + " " + path.getFileName() + " (" + lines + " lines, " + bytes + " bytes)";
    }
}
