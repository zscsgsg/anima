package com.anima.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Lists directory contents. Cross-platform, cross-language — works everywhere Java runs.
 */
public class LsTool implements Tool {

    @Override public String name() { return "ls"; }
    @Override public String description() {
        return "List files and directories in a given path. Use this to explore project structure, see what files exist, and understand the codebase layout.";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Directory path to list (default: current directory)"
            }
          }
        }
        """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        String pathStr = json.has("path") ? json.get("path").asText() : ".";
        Path dir = Path.of(pathStr);
        if (!dir.isAbsolute()) dir = Path.of("").toAbsolutePath().resolve(pathStr).normalize();
        if (!Files.isDirectory(dir)) return "Error: not a directory: " + dir;

        var sb = new StringBuilder();
        try (var stream = Files.list(dir)) {
            var entries = stream.sorted().collect(Collectors.toList());
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    sb.append(name).append("/\n");
                } else {
                    long size = Files.size(p);
                    sb.append(name).append("  (").append(formatSize(size)).append(")\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
