package com.anima.tool;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * File pattern search using glob-to-regex conversion.
 * Avoids PathMatcher bugs with Windows drive letters.
 */
public class GlobTool implements Tool {

    @Override public String name() { return "glob"; }
        @Override public boolean isReadOnly() { return true; }
    @Override public String description() {
        return "Find files matching a glob pattern. Use this to discover project files by name. Supports *, ?, and ** (recursive). Examples: '**​/*.java', 'src/**​/*Test*'.";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "Glob pattern to match file paths"
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
        Path baseDir = Path.of(baseStr);
        if (!baseDir.isAbsolute()) baseDir = Path.of("").toAbsolutePath().resolve(baseStr).normalize();
        if (!Files.isDirectory(baseDir)) return "Error: not a directory: " + baseDir;
        final Path root = baseDir;

        Pattern regex = globToRegex(pattern);
        List<String> matches = new ArrayList<>();
        int maxDepth = pattern.contains("**") ? 10 : 1;

        Files.walk(root, maxDepth).filter(Files::isRegularFile).limit(500).forEach(p -> {
            String rel = root.relativize(p).toString().replace("\\", "/");
            if (regex.matcher(rel).matches()) matches.add(rel);
        });

        if (matches.isEmpty()) return "No files matching: " + pattern;
        var sb = new StringBuilder();
        sb.append(matches.size()).append(" file(s) matching \"").append(pattern).append("\":\n");
        matches.stream().sorted().forEach(m -> sb.append(m).append("\n"));
        return sb.toString().trim();
    }

    /** Convert a glob pattern to regex. */
    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        sb.append("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    // ** matches zero or more directory levels
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        sb.append("(?:.*/)?");
                        i += 2; // skip * and /
                        continue;
                    }
                    i++; // skip second *
                }
                sb.append("[^/]*");
            } else if (c == '?') {
                sb.append("[^/]");
            } else if (c == '.') {
                sb.append("\\.");
            } else {
                sb.append(c);
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }
}
