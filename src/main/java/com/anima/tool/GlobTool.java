package com.anima.tool;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * File pattern search. Supports glob patterns like "**​/*.java", "src/**​/*Controller.java".
 */
public class GlobTool implements Tool {

    @Override public String name() { return "glob"; }
    @Override public String description() {
        return "Find files matching a glob pattern. Use this to discover project files by name (e.g. '**​/*Controller.java', 'src/**​/*Test*'). Supports *, ?, and ** (recursive).";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "Glob pattern to match file paths (e.g. '**​/*.java', 'src/**​/*.xml')"
            },
            "path": {
              "type": "string",
              "description": "Base directory to search in (default: current directory)"
            }
          },
          "required": ["pattern"]
        }
        """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        String pattern = json.get("pattern").asText();
        String baseStr = json.has("path") ? json.get("path").asText() : ".";
        Path base = Path.of(baseStr);
        if (!base.isAbsolute()) base = Path.of("").toAbsolutePath().resolve(baseStr).normalize();
        final Path baseDir = base;
        if (!Files.isDirectory(base)) return "Error: not a directory: " + base;

        // Convert glob pattern to PathMatcher syntax
        String glob = "glob:" + baseDir.toString().replace("\\", "/") + "/" + pattern.replace("\\", "/");
        PathMatcher matcher;
        try { matcher = FileSystems.getDefault().getPathMatcher(glob); }
        catch (PatternSyntaxException | UnsupportedOperationException e) {
            return "Error: invalid glob pattern: " + pattern;
        }

        List<String> matches = new ArrayList<>();
        Files.walk(baseDir, 5).filter(p -> !Files.isDirectory(p)).forEach(p -> {
            if (matcher.matches(p)) matches.add(baseDir.relativize(p).toString().replace("\\", "/"));
        });

        if (matches.isEmpty()) return "No files matching: " + pattern;
        var sb = new StringBuilder();
        sb.append(matches.size()).append(" file(s) matching \"").append(pattern).append("\":\n");
        for (String m : matches) sb.append(m).append("\n");
        return sb.toString().trim();
    }
}
