package com.anima.tool;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Content search with regex. Like ripgrep but built-in and cross-platform.
 */
public class GrepTool implements Tool {

    @Override public String name() { return "grep"; }
    @Override public String description() {
        return "Search file contents for a regex pattern. Use this to find where a class, method, string, or pattern appears in the codebase.";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "Regex pattern to search for (e.g. 'import.*Javalin', 'class AgentLoop')"
            },
            "path": {
              "type": "string",
              "description": "Directory to search in (default: current directory)"
            },
            "include": {
              "type": "string",
              "description": "File glob to filter by (e.g. '*.java', '*.xml'). Default: all text files."
            }
          },
          "required": ["pattern"]
        }
        """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        String regex = json.get("pattern").asText();
        String baseStr = json.has("path") ? json.get("path").asText() : ".";
        String include = json.has("include") ? json.get("include").asText() : null;
        Path base = Path.of(baseStr);
        if (!base.isAbsolute()) base = Path.of("").toAbsolutePath().resolve(baseStr).normalize();
        final Path baseDir = base;

        Pattern pat;
        try { pat = Pattern.compile(regex, Pattern.CASE_INSENSITIVE); }
        catch (PatternSyntaxException e) { return "Error: invalid regex pattern: " + regex; }

        List<String> results = new ArrayList<>();
        Files.walk(baseDir, 5).filter(p -> !Files.isDirectory(p)).forEach(p -> {
            if (include != null && !p.getFileName().toString().toLowerCase().endsWith(fileExt(include))) return;
            try {
                String rel = baseDir.relativize(p).toString().replace("\\", "/");
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    if (pat.matcher(lines.get(i)).find()) {
                        results.add(rel + ":" + (i + 1) + ": " + lines.get(i).stripLeading());
                        if (results.size() >= 50) break;
                    }
                }
            } catch (Exception ignored) {}
        });
        if (results.size() >= 50) results.add("... (truncated at 50 matches)");

        if (results.isEmpty()) return "No matches for: " + regex;
        var sb = new StringBuilder();
        sb.append(results.size()).append(" match(es) for \"").append(regex).append("\":\n");
        for (String r : results) sb.append(r).append("\n");
        return sb.toString().trim();
    }

    private static String fileExt(String include) {
        if (include.startsWith("*.")) return include;
        if (include.startsWith(".")) return include;
        return "." + include;
    }
}
