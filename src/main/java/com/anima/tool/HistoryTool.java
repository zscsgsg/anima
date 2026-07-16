package com.anima.tool;

import com.anima.retrieval.Bm25Index;
import com.anima.retrieval.Bm25Index.Document;
import com.anima.retrieval.Bm25Index.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;

import java.nio.file.*;
import java.util.*;

/**
 * Read-only history retrieval tool — lets the model search past session
 * transcripts with BM25 and read messages around a hit.
 * Mirrors Reasonix's history tool.
 *
 * <p>Operations:
 * <ul>
 *   <li><b>search</b> — BM25 search over saved session JSONL files</li>
 *   <li><b>around</b> — read messages around a search hit</li>
 * </ul>
 */
public class HistoryTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path sessionsDir;
    private final Bm25Index index = new Bm25Index();
    private boolean indexed = false;

    public HistoryTool(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
    }

    @Override public String name() { return "history"; }

    @Override public boolean isReadOnly() { return true; }

    @Override public String description() {
        return "Search saved local session history with lightweight BM25 retrieval, " +
            "then read messages around a hit. Use search when past decisions, failed " +
            "attempts, commands, or tool inputs may help the current task; use around " +
            "with a returned session_path and message_index to inspect the nearby transcript.";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "operation": {
              "type": "string",
              "enum": ["search", "around"],
              "description": "search ranks saved history; around returns nearby messages for a search hit."
            },
            "query": {
              "type": "string",
              "description": "Search query for operation=search."
            },
            "limit": {
              "type": "integer",
              "description": "Maximum search hits, default 8, max 20."
            },
            "session_path": {
              "type": "string",
              "description": "Path from a search hit. Required for operation=around."
            },
            "message_index": {
              "type": "integer",
              "description": "Message index from a search hit. Required for operation=around."
            },
            "before": {
              "type": "integer",
              "description": "Messages before message_index, default 3, max 10."
            },
            "after": {
              "type": "integer",
              "description": "Messages after message_index, default 3, max 10."
            }
          },
          "required": ["operation"]
        }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = MAPPER.readTree(arguments);
        String operation = json.has("operation") ? json.get("operation").asText() : "";

        return switch (operation) {
            case "search" -> doSearch(json);
            case "around" -> doAround(json);
            case "" -> "Error: operation is required (search or around)";
            default -> "Error: unknown operation '" + operation + "'";
        };
    }

    private String doSearch(com.fasterxml.jackson.databind.JsonNode json) throws Exception {
        String query = json.has("query") ? json.get("query").asText() : "";
        if (query.isBlank()) return "Error: query is required for search";

        int limit = json.has("limit") ? json.get("limit").asInt() : 8;

        // Build index on first call
        if (!indexed) buildIndex();

        var hits = index.search(query, limit);
        if (hits.isEmpty()) {
            return "No saved session history matched \"" + query + "\".\n" +
                "0 results does not prove the event never happened. Try:\n" +
                "1. Retry with fewer, rarer terms (function name, command, error phrase, etc.)\n" +
                "2. If you need tool output, consider searching with kind filter\n" +
                "Use operation=\"around\" with a session_path and message_index to read nearby messages.";
        }

        var sb = new StringBuilder();
        sb.append("History search results for \"").append(query).append("\":\n");
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            sb.append("\n").append(i + 1).append(". score=")
              .append(String.format("%.3f", hit.score()))
              .append(" session=").append(hit.sessionId())
              .append(" msg_index=").append(hit.messageIndex())
              .append(" kind=").append(hit.kind())
              .append(" role=").append(hit.role());
            if (hit.toolName() != null && !hit.toolName().isEmpty()) {
                sb.append(" tool=").append(hit.toolName());
            }
            sb.append("\n   session_path: ").append(hit.sessionPath())
              .append("\n   snippet: ").append(hit.snippet()).append("\n");
        }
        sb.append("\nUse operation=\"around\" with a session_path and message_index to read nearby messages.");
        return sb.toString().trim();
    }

    private String doAround(com.fasterxml.jackson.databind.JsonNode json) throws Exception {
        if (!json.has("message_index")) {
            return "Error: message_index is required for around";
        }
        String sessionPath = json.has("session_path") ? json.get("session_path").asText() : "";
        if (sessionPath.isBlank()) return "Error: session_path is required for around";

        int msgIdx = json.get("message_index").asInt();
        int before = json.has("before") ? json.get("before").asInt() : 3;
        int after = json.has("after") ? json.get("after").asInt() : 3;
        before = Math.max(0, Math.min(before, 10));
        after = Math.max(0, Math.min(after, 10));

        Path path = sessionsDir.resolve(sessionPath);
        if (!Files.exists(path)) {
            return "Error: session file not found: " + sessionPath;
        }

        // Read the session file and find context around msgIdx
        List<String> messages = Files.readAllLines(path);
        int start = Math.max(0, msgIdx - before);
        int end = Math.min(messages.size(), msgIdx + after + 1);

        var sb = new StringBuilder();
        sb.append("History around ").append(sessionPath)
          .append(" message_index=").append(msgIdx).append(":\n");
        for (int i = start; i < end; i++) {
            sb.append("\n").append(messages.get(i));
        }
        return sb.toString().trim();
    }

    /** Index all JSONL session files from the sessions directory. */
    private void buildIndex() {
        if (sessionsDir == null || !Files.exists(sessionsDir)) {
            indexed = true;
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sessionsDir, "*.jsonl")) {
            for (Path file : ds) {
                String sessionId = file.getFileName().toString()
                    .replace(".jsonl", "");
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    try {
                        var msg = MAPPER.readTree(lines.get(i));
                        String role = msg.has("role") ? msg.get("role").asText() : "";
                        String content = msg.has("content") ? msg.get("content").asText() : "";

                        // Index different "kinds" of message content
                        if (role.equals("user") && !content.isBlank()) {
                            index.add(new Document(file.toString(), file.toString(),
                                content, "user_text", sessionId, i, "user", null));
                        }
                        if (role.equals("assistant")) {
                            if (!content.isBlank()) {
                                index.add(new Document(file.toString(), file.toString(),
                                    content, "assistant_text", sessionId, i, "assistant", null));
                            }
                            // Also index tool call arguments
                            if (msg.has("tool_calls")) {
                                for (var tc : msg.get("tool_calls")) {
                                    String tcName = tc.has("name") ? tc.get("name").asText() : "";
                                    String tcArgs = tc.has("arguments") ? tc.get("arguments").asText() : "";
                                    index.add(new Document(file.toString(), file.toString(),
                                        tcName + " " + tcArgs, "tool_input", sessionId, i, "assistant", tcName));
                                }
                            }
                        }
                        if (role.equals("tool") && !content.isBlank()) {
                            String toolName = msg.has("name") ? msg.get("name").asText() : null;
                            String kind = content.startsWith("Error:") || content.startsWith("blocked:")
                                ? "tool_error" : "tool_output";
                            index.add(new Document(file.toString(), file.toString(),
                                content, kind, sessionId, i, "tool", toolName));
                        }
                    } catch (Exception ignored) {
                        // Skip malformed lines
                    }
                }
            }
        } catch (Exception ignored) {}
        indexed = true;
    }
}
