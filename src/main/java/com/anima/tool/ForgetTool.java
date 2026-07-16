package com.anima.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Forget tool — lets the model delete outdated memories.
 * Mirrors Reasonix's {@code internal/memory/forget.go}.
 *
 * <p>Instead of deleting, memories are archived to .archive/ with a timestamp
 * for traceability. Searches both project-local and global memory dirs.
 */
public class ForgetTool implements Tool {

    private final Path memoryDir;
    private final Path globalMemoryDir;

    public ForgetTool(Path workspaceDir) {
        this.memoryDir = workspaceDir.resolve(".anima").resolve("memory");
        this.globalMemoryDir = Path.of(System.getProperty("user.home"), ".anima", "memory", "global");
    }

    @Override
    public String name() { return "forget"; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public String description() {
        return "Delete a previously saved memory. Use when a fact is outdated, was incorrectly saved, " +
            "or the user asks to remove it. Pass the exact name (slug) of the memory to delete.";
    }

    @Override
    public String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "The memory name (slug) to delete, e.g. 'prefers-tabs'. Case-insensitive match on the file stem."
                }
              },
              "required": ["name"]
            }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode args = MAPPER.readTree(arguments);
        String name = args.has("name") ? args.get("name").asText().trim() : "";
        if (name.isEmpty()) return "Error: name is required";

        // Slugify and find matching file in both dirs
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        Path target = null;
        Path targetDir = null;

        for (Path dir : List.of(memoryDir, globalMemoryDir)) {
            if (!Files.isDirectory(dir)) continue;
            try (var files = Files.list(dir)) {
                for (var f : files.toList()) {
                    if (f.getFileName().toString().equals("MEMORY.md")) continue;
                    String fileName = f.getFileName().toString();
                    if (fileName.equals(slug + ".md") || fileName.equalsIgnoreCase(name + ".md")) {
                        target = f;
                        targetDir = dir;
                        break;
                    }
                }
            }
            if (target != null) break;
        }

        if (target == null) {
            return "No memory found matching '" + name + "'. Available: " + listMemories();
        }

        // Archive instead of delete — move to .archive/ with timestamp
        String ts = java.time.Instant.now().toString()
            .replace(":", "-").replace("T", "-");
        if (ts.length() > 30) ts = ts.substring(0, 30);
        Path archiveDir = targetDir.resolve(".archive");
        Files.createDirectories(archiveDir);
        Path archived = archiveDir.resolve(ts + "-" + target.getFileName().toString());
        Files.move(target, archived);

        // Update indexes in both dirs
        updateIndex(memoryDir);
        updateIndex(globalMemoryDir);

        return "Archived memory '" + target.getFileName().toString().replace(".md", "") +
            "' (it no longer applies and will not load in future sessions; archived to " +
            archiveDir.relativize(archived) + ")";
    }

    private String listMemories() throws IOException {
        List<String> names = new ArrayList<>();
        for (Path dir : List.of(memoryDir, globalMemoryDir)) {
            if (!Files.isDirectory(dir)) continue;
            try (var s = Files.list(dir)) {
                s.filter(f -> f.getFileName().toString().endsWith(".md")
                    && !f.getFileName().toString().equals("MEMORY.md"))
                    .forEach(f -> names.add(f.getFileName().toString().replace(".md", "")));
            }
        }
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }

    private void updateIndex(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        var files = Files.list(dir)
            .filter(f -> f.getFileName().toString().endsWith(".md")
                && !f.getFileName().toString().equals("MEMORY.md"))
            .sorted().toList();

        StringBuilder idx = new StringBuilder("# Anima Memory Index\n\n");
        for (var f : files) {
            String content = Files.readString(f, StandardCharsets.UTF_8);
            String title = extractFrontmatter(content, "title");
            String desc = extractFrontmatter(content, "description");
            String type = extractFrontmatter(content, "type");
            String n = f.getFileName().toString().replace(".md", "");
            idx.append("- [").append(title != null ? title : n).append("](")
                .append(n).append(".md)");
            if (type != null) idx.append(" [").append(type).append("]");
            if (desc != null) idx.append(" — ").append(desc);
            idx.append("\n");
        }
        Files.writeString(dir.resolve("MEMORY.md"), idx.toString(), StandardCharsets.UTF_8);
    }

    private static String extractFrontmatter(String content, String key) {
        var lines = content.split("\n");
        boolean inFm = false;
        for (String line : lines) {
            if (line.trim().equals("---")) {
                if (!inFm) { inFm = true; continue; }
                else break;
            }
            if (inFm) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equals(key)) {
                    return line.substring(colon + 1).trim().replaceAll("^[\"']|[\"']$", "");
                }
            }
        }
        return null;
    }
}
