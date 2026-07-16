package com.anima.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Remember tool — lets the model persist durable facts to project memory
 * that survive across sessions. Mirrors Reasonix's {@code internal/memory/remember.go}.
 *
 * <p>Memory routing:
 * <ul>
 *   <li>user / feedback → GlobalDir (~/.anima/memory/global/), shared across projects</li>
 *   <li>project / reference → project-local .anima/memory/</li>
 * </ul>
 *
 * <p>Reusing a name updates existing memory; duplicate removal in other dir.
 */
public class RememberTool implements Tool {

    private final Path memoryDir;
    private final Path globalMemoryDir;

    public RememberTool(Path workspaceDir) {
        this.memoryDir = workspaceDir.resolve(".anima").resolve("memory");
        this.globalMemoryDir = Path.of(System.getProperty("user.home"), ".anima", "memory", "global");
    }

    /** Target dir for a given memory type. */
    private Path dirFor(String type) {
        if ("user".equals(type) || "feedback".equals(type)) {
            return globalMemoryDir;
        }
        return memoryDir;
    }

    @Override
    public String name() { return "remember"; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public String description() {
        return "Save a durable fact to project memory so it survives across sessions. " +
            "Use for things worth remembering long-term: who the user is and their preferences (type \"user\"); " +
            "guidance on how to work, including the why (type \"feedback\"); ongoing goals or constraints not " +
            "derivable from the code (type \"project\"); or pointers to external resources (type \"reference\"). " +
            "For feedback/project, structure the body with a \"**Why:**\" line and a \"**How to apply:**\" line " +
            "so the fact is actionable later. Do NOT save what the repo already records (code structure, git history) " +
            "or facts that only matter to the current conversation. Before saving, check the loaded memory index " +
            "for an entry that already covers this — reuse that name to update it rather than create a near-duplicate, " +
            "and use `forget` to drop one that is now wrong. The saved index loads into context at the start of each session.";
    }

    @Override
    public String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Short kebab-case slug identifying the fact, e.g. 'prefers-tabs'. Reusing a name overwrites that memory. Omit to derive one from the description."
                },
                "title": {
                  "type": "string",
                  "description": "Short human-readable label shown in the memory index, e.g. 'Prefers tabs'. Omit to derive one from the name."
                },
                "description": {
                  "type": "string",
                  "description": "One-line hook shown in the index — the phrase a future session reads to decide whether to open this memory. Make it specific."
                },
                "type": {
                  "type": "string",
                  "enum": ["user", "feedback", "project", "reference"],
                  "description": "Category of the fact."
                },
                "body": {
                  "type": "string",
                  "description": "The fact itself (Markdown). For feedback/project, include a '**Why:**' line and a '**How to apply:**' line."
                }
              },
              "required": ["description", "body"]
            }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode args = MAPPER.readTree(arguments);

        String desc = args.has("description") ? args.get("description").asText().trim() : "";
        String body = args.has("body") ? args.get("body").asText().trim() : "";
        if (desc.isEmpty() || body.isEmpty()) {
            return "Error: description and body are required";
        }

        String name = args.has("name") && !args.get("name").asText().isBlank()
            ? args.get("name").asText().trim()
            : (args.has("title") ? args.get("title").asText().trim() : desc);
        name = slug(name);

        String title = args.has("title") && !args.get("title").asText().isBlank()
            ? args.get("title").asText().trim() : name;

        String type = args.has("type") ? args.get("type").asText().trim() : "project";
        if (!Set.of("user", "feedback", "project", "reference").contains(type)) {
            type = "project";
        }

        // Route to correct directory
        Path targetDir = dirFor(type);
        Files.createDirectories(targetDir);
        Path memoryFile = targetDir.resolve(name + ".md");

        // Write with frontmatter including name field
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("title: ").append(title).append("\n");
        sb.append("description: ").append(desc.replace("\n", " ")).append("\n");
        sb.append("type: ").append(type).append("\n");
        sb.append("---\n\n");
        sb.append(body.stripTrailing()).append("\n");
        Files.writeString(memoryFile, sb.toString(), StandardCharsets.UTF_8);

        // Remove from other dir if exists (re-route from project→global or vice versa)
        Path otherDir = "user".equals(type) || "feedback".equals(type) ? memoryDir : globalMemoryDir;
        Path otherFile = otherDir.resolve(name + ".md");
        if (Files.exists(otherFile)) {
            archiveFile(otherFile, otherDir);
        }

        // Update indexes in both dirs
        updateIndex(memoryDir);
        updateIndex(globalMemoryDir);

        String location = ("user".equals(type) || "feedback".equals(type)) ? "global" : "project";
        return "Saved memory '" + name + "' (" + type + ", " + location + "): " + truncate(desc, 80) +
            "\nIt applies now and loads automatically in future sessions.";
    }

    private void updateIndex(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        var files = Files.list(dir)
            .filter(f -> f.getFileName().toString().endsWith(".md")
                && !f.getFileName().toString().equals("MEMORY.md"))
            .sorted()
            .toList();

        StringBuilder idx = new StringBuilder("# Anima Memory Index\n\n");
        for (var f : files) {
            String content = Files.readString(f, StandardCharsets.UTF_8);
            String title = extractFrontmatter(content, "title");
            String desc = extractFrontmatter(content, "description");
            String type = extractFrontmatter(content, "type");
            String fname = f.getFileName().toString().replace(".md", "");
            idx.append("- [").append(title != null ? title : fname).append("](")
                .append(fname).append(".md)");
            if (type != null) idx.append(" [").append(type).append("]");
            if (desc != null) idx.append(" — ").append(desc);
            idx.append("\n");
        }
        Files.writeString(dir.resolve("MEMORY.md"), idx.toString(), StandardCharsets.UTF_8);
    }

    /** Archive a file to .archive/ with timestamp (instead of deleting). */
    private static void archiveFile(Path file, Path dir) {
        try {
            Path archiveDir = dir.resolve(".archive");
            Files.createDirectories(archiveDir);
            String ts = java.time.Instant.now().toString()
                .replace(":", "-").replace("T", "-");
            if (ts.length() > 30) ts = ts.substring(0, 30);
            Path dest = archiveDir.resolve(ts + "-" + file.getFileName().toString());
            Files.move(file, dest);
        } catch (Exception ignored) {}
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

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
