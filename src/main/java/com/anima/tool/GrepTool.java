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
        @Override public boolean isReadOnly() { return true; }
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
        Files.walk(baseDir, 10).filter(p -> !Files.isDirectory(p)).forEach(p -> {
            String fileName = p.getFileName().toString();
            if (!matchesInclude(fileName, include)) return;
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
        if (include == null || include.isEmpty()) return null;
        // If it looks like a glob path (contains / or **), extract the extension from the last segment
        if (include.contains("/")) {
            String last = include.substring(include.lastIndexOf('/') + 1);
            if (last.startsWith("*.")) return last.substring(1);
            int dot = last.lastIndexOf('.');
            if (dot > 0) return last.substring(dot);
        }
        if (include.startsWith("*.")) return include.substring(1);  // *.java → .java
        if (include.startsWith(".")) return include;
        return "." + include;
    }

    /** Check if a filename matches a glob-style include pattern. */
    private static boolean matchesInclude(String fileName, String include) {
        if (include == null || include.isEmpty()) return true;
        String ext = fileExt(include);
        if (ext != null && fileName.toLowerCase().endsWith(ext.toLowerCase())) return true;
        // Try glob matching for patterns like **/pom.xml
        if (include.contains("/")) {
            String pattern = include.replace("**", ".*").replace("*", "[^/]*").replace("?", ".");
            return java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(fileName).matches();
        }
        return false;
    }
}
