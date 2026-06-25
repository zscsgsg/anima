package com.anima.tool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Move or rename a file. Cross-platform — no shell mv/ren needed.
 */
public class MoveFileTool implements Tool {

    @Override public String name() { return "move_file"; }
    @Override public String description() {
        return "Move or rename a file from source to destination. Creates parent directories as needed.";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "source": {
              "type": "string",
              "description": "Existing file path to move"
            },
            "destination": {
              "type": "string",
              "description": "Destination path; must not already exist"
            }
          },
          "required": ["source", "destination"]
        }
        """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        String srcStr = json.has("source") ? json.get("source").asText() : json.get("source_path").asText();
        String dstStr = json.has("destination") ? json.get("destination").asText() : json.get("destination_path").asText();

        Path src = Path.of(srcStr);
        if (!src.isAbsolute()) src = Path.of("").toAbsolutePath().resolve(srcStr).normalize();
        Path dst = Path.of(dstStr);
        if (!dst.isAbsolute()) dst = Path.of("").toAbsolutePath().resolve(dstStr).normalize();

        if (!Files.exists(src)) return "Error: source not found: " + src;
        if (Files.exists(dst)) return "Error: destination already exists: " + dst;
        Files.createDirectories(dst.getParent());
        Files.move(src, dst);
        return "Moved " + src.getFileName() + " → " + dst;
    }
}
