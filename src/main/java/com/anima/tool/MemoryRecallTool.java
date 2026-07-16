package com.anima.tool;

import com.anima.retrieval.Bm25Index;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-only memory recall tool — lets the model search saved memories with BM25,
 * read full memory bodies, or list all saved memories.
 * Mirrors Reasonix's {@code internal/memory/recall.go}.
 *
 * <p>Operations:
 * <ul>
 *   <li><b>search</b> — BM25 search over saved memory files (frontmatter + body)</li>
 *   <li><b>read</b> — return one full memory by name (slug)</li>
 *   <li><b>list</b> — return the MEMORY.md index contents</li>
 * </ul>
 */
public class MemoryRecallTool implements Tool {

    private final Path memoryDir;
    private final Path globalMemoryDir;
    private final Bm25Index index = new Bm25Index();
    private boolean indexed = false;

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;

    public MemoryRecallTool(Path workspaceDir) {
        this.memoryDir = workspaceDir.resolve(".anima").resolve("memory");
        Path userHome = Path.of(System.getProperty("user.home"));
        this.globalMemoryDir = userHome.resolve(".anima").resolve("memory").resolve("global");
    }

    @Override public String name() { return "memory"; }

    @Override public boolean isReadOnly() { return true; }

    @Override
    public String description() {
        return "Search, list, and read saved project memories. " +
            "Use search to find relevant memories by query with BM25 ranking; " +
            "use read with a memory name (slug) to get its full body; " +
            "use list to see all saved memories. " +
            "This tool is read-only; use remember to save or update a memory, and forget to delete one.";
    }

    @Override
    public String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "operation": {
                  "type": "string",
                  "enum": ["search", "read", "list"],
                  "description": "search ranks saved memories by BM25; read returns one full memory by name; list returns all saved memories."
                },
                "query": {
                  "type": "string",
                  "description": "Search query for operation=search. Use 1-3 distinctive terms."
                },
                "name": {
                  "type": "string",
                  "description": "Memory slug for operation=read, e.g. the name in [Label](name.md) from the memory index."
                },
                "type": {
                  "type": "string",
                  "enum": ["user", "feedback", "project", "reference"],
                  "description": "Optional memory type filter for search or list."
                },
                "limit": {
                  "type": "integer",
                  "description": "Maximum results for search/list, default 8, max 20."
                }
              },
              "required": ["operation"]
            }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode args = MAPPER.readTree(arguments);
        String op = args.has("operation") ? args.get("operation").asText() : "";

        return switch (op) {
            case "search" -> doSearch(args);
            case "read" -> doRead(args);
            case "list" -> doList(args);
            case "" -> "Error: operation is required (search, read, or list)";
            default -> "Error: unknown operation '" + op + "' (use search, read, or list)";
        };
    }

    // ── Search ──

    private String doSearch(JsonNode args) throws Exception {
        String query = args.has("query") ? args.get("query").asText().trim() : "";
        if (query.isEmpty()) return "Error: query is required for search";

        String typeFilter = args.has("type") ? args.get("type").asText().trim() : "";
        int limit = clampLimit(args.has("limit") ? args.get("limit").asInt() : DEFAULT_LIMIT);

        ensureIndex();

        List<Bm25Index.Hit> hits = index.search(query, limit * 2); // oversample for filtering
        if (typeFilter != null && !typeFilter.isEmpty()) {
            hits = hits.stream()
                .filter(h -> h.kind() != null && h.kind().equalsIgnoreCase(typeFilter))
                .collect(Collectors.toList());
        }
        if (hits.size() > limit) hits = hits.subList(0, limit);

        if (hits.isEmpty()) {
            return "No saved memories matched \"" + query + "\".\n" +
                "This does not prove the fact was never recorded. Try:\n" +
                "1. Retry with 1-3 distinctive terms (function name, task id, rare phrase).\n" +
                "2. For exact literals, search one distinctive token.\n" +
                "Use operation=\"read\" with a memory name to inspect the full saved fact.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Memory search results for \"").append(query).append("\":\n");
        for (int i = 0; i < hits.size(); i++) {
            Bm25Index.Hit hit = hits.get(i);
            sb.append("\n").append(i + 1).append(". score=")
              .append(String.format("%.3f", hit.score()))
              .append(" name=").append(hit.sessionId())
              .append(" type=").append(hit.kind() != null ? hit.kind() : "project");
            sb.append("\n   snippet: ").append(hit.snippet()).append("\n");
        }
        sb.append("\nUse operation=\"read\" with a memory name to inspect the full saved fact.");
        return sb.toString().trim();
    }

    // ── Read ──

    private String doRead(JsonNode args) throws Exception {
        String name = args.has("name") ? args.get("name").asText().trim() : "";
        if (name.isEmpty()) return "Error: name is required for read";

        String slug = toSlug(name);
        Path file = findMemoryFile(slug);
        if (file == null) {
            // List available memories to help
            List<String> available = listMemoryNames();
            return "Memory \"" + name + "\" not found. Available: " +
                (available.isEmpty() ? "(none)" : String.join(", ", available));
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        String title = extractFrontmatter(content, "title");
        String type = extractFrontmatter(content, "type");
        String desc = extractFrontmatter(content, "description");
        String body = extractBody(content);

        StringBuilder sb = new StringBuilder();
        sb.append("Memory ").append(slug).append("\n");
        sb.append("title: ").append(title != null ? title : slug).append("\n");
        sb.append("type: ").append(type != null ? type : "project").append("\n");
        if (desc != null) sb.append("description: ").append(desc).append("\n");
        sb.append("path: ").append(file.toAbsolutePath()).append("\n\n");
        sb.append(body != null ? body.stripTrailing() : "(empty)");
        return sb.toString().trim();
    }

    // ── List ──

    private String doList(JsonNode args) throws Exception {
        String typeFilter = args.has("type") ? args.get("type").asText().trim() : "";
        int limit = clampLimit(args.has("limit") ? args.get("limit").asInt() : DEFAULT_LIMIT);

        List<String> names = listMemoryNames();
        // Read MEMORY.md for formatted index
        Path indexFile = memoryDir.resolve("MEMORY.md");
        if (Files.exists(indexFile)) {
            String idx = Files.readString(indexFile, StandardCharsets.UTF_8);
            // Filter by type if requested
            if (!typeFilter.isEmpty()) {
                var filtered = Arrays.stream(idx.split("\n"))
                    .filter(l -> l.contains("[" + typeFilter + "]"))
                    .limit(limit)
                    .collect(Collectors.joining("\n"));
                if (filtered.isEmpty()) return "No memories of type \"" + typeFilter + "\" found.";
                return "Saved memories (type=" + typeFilter + "):\n" + filtered;
            }
            return "Saved memories (" + names.size() + " total):\n" + idx.stripTrailing();
        }
        if (names.isEmpty()) return "No saved memories found.";
        return "Saved memories: " + String.join(", ", names);
    }

    // ── Indexing ──

    private void ensureIndex() {
        if (indexed) return;
        List<Path> dirs = new ArrayList<>();
        dirs.add(memoryDir);
        if (Files.isDirectory(globalMemoryDir)) dirs.add(globalMemoryDir);

        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    String fileName = f.getFileName().toString();
                    if (!fileName.endsWith(".md") || fileName.equals("MEMORY.md")) continue;
                    try {
                        String content = Files.readString(f, StandardCharsets.UTF_8);
                        String name = fileName.replace(".md", "");
                        String type = extractFrontmatter(content, "type");
                        if (type == null) type = "project";
                        String title = extractFrontmatter(content, "title");
                        String desc = extractFrontmatter(content, "description");
                        String body = extractBody(content);

                        // Index searchable text
                        String searchText = name + "\n" +
                            (title != null ? title + "\n" : "") +
                            type + "\n" +
                            (desc != null ? desc + "\n" : "") +
                            (body != null ? body : "");
                        index.add(new Bm25Index.Document(
                            name, f.toString(), searchText, type,
                            name, 0, "memory", null));
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        indexed = true;
    }

    // ── Helpers ──

    private List<String> listMemoryNames() {
        List<String> names = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        dirs.add(memoryDir);
        if (Files.isDirectory(globalMemoryDir)) dirs.add(globalMemoryDir);

        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (var files = Files.list(dir)) {
                files.filter(f -> {
                    String n = f.getFileName().toString();
                    return n.endsWith(".md") && !n.equals("MEMORY.md");
                }).forEach(f -> names.add(f.getFileName().toString().replace(".md", "")));
            } catch (Exception ignored) {}
        }
        return names.stream().distinct().sorted().collect(Collectors.toList());
    }

    private Path findMemoryFile(String slug) {
        for (Path dir : List.of(memoryDir, globalMemoryDir)) {
            Path f = dir.resolve(slug + ".md");
            if (Files.exists(f)) return f;
        }
        return null;
    }

    private static String toSlug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    }

    static String extractFrontmatter(String content, String key) {
        var lines = content.split("\n");
        boolean inFm = false;
        for (String line : lines) {
            if (line.trim().equals("---")) {
                if (!inFm) { inFm = true; continue; }
                else break;
            }
            if (inFm) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(key)) {
                    return line.substring(colon + 1).trim().replaceAll("^[\"']|[\"']$", "");
                }
            }
        }
        return null;
    }

    static String extractBody(String content) {
        var lines = content.split("\n", -1);
        int sepCount = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                sepCount++;
                if (sepCount == 2 && i + 1 < lines.length) {
                    return String.join("\n", Arrays.copyOfRange(lines, i + 1, lines.length));
                }
            }
        }
        return content;
    }

    private static int clampLimit(int n) {
        if (n <= 0) return DEFAULT_LIMIT;
        return Math.min(n, MAX_LIMIT);
    }
}
