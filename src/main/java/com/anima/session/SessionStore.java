package com.anima.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * JSONL-based session persistence — mirrors Reasonix's JSONL session format.
 * One JSON object per line = one ChatMessage.
 *
 * <p>Storage: .anima/sessions/anima-&lt;timestamp&gt;.jsonl
 */
public class SessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Save a conversation history to a JSONL file. Creates parent dirs as needed. */
    public static void save(List<ChatMessage> history, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            for (ChatMessage msg : history) {
                Map<String, Object> obj = serialize(msg);
                w.write(MAPPER.writeValueAsString(obj));
                w.newLine();
            }
        }
    }

    /** Load a conversation history from a JSONL file. */
    public static List<ChatMessage> load(Path file) throws IOException {
        List<ChatMessage> messages = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, Object> obj = MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
                ChatMessage msg = deserialize(obj);
                if (msg != null) messages.add(msg);
            }
        }
        return messages;
    }

    /** List all session files, newest first. */
    public static List<Path> listSessions(Path sessionsDir) throws IOException {
        if (!Files.isDirectory(sessionsDir)) return List.of();
        try (Stream<Path> files = Files.list(sessionsDir)) {
            return files
                    .filter(f -> f.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(SessionStore::lastModified).reversed())
                    .toList();
        }
    }

    /** Count messages in a session file without loading them all. */
    public static int countMessages(Path file) throws IOException {
        int count = 0;
        try (BufferedReader r = Files.newBufferedReader(file)) {
            while (r.readLine() != null) count++;
        }
        return count;
    }

    /** Read the first user message from a session file for preview. */
    public static String previewFirstUser(Path file) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, Object> obj = MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
                if ("USER".equals(obj.get("role"))) {
                    String content = (String) obj.getOrDefault("content", "");
                    return content.length() > 80 ? content.substring(0, 77) + "..." : content;
                }
            }
        }
        return "(empty)";
    }

    private static long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); }
        catch (IOException e) { return 0; }
    }

    // ── Serialization ──

    @SuppressWarnings("unchecked")
    private static Map<String, Object> serialize(ChatMessage msg) {
        Map<String, Object> obj = new LinkedHashMap<>();
        if (msg instanceof SystemMessage sm) {
            obj.put("role", "SYSTEM");
            obj.put("content", sm.text());
        } else if (msg instanceof UserMessage um) {
            obj.put("role", "USER");
            obj.put("content", singleText(um));
        } else if (msg instanceof AiMessage ai) {
            obj.put("role", "AI");
            String text = ai.text();
            obj.put("content", text != null ? text : "");
            if (ai.hasToolExecutionRequests()) {
                List<Map<String, String>> calls = new ArrayList<>();
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    Map<String, String> c = new LinkedHashMap<>();
                    c.put("id", req.id());
                    c.put("name", req.name());
                    c.put("arguments", req.arguments());
                    calls.add(c);
                }
                obj.put("toolCalls", calls);
            }
        } else if (msg instanceof ToolExecutionResultMessage tr) {
            obj.put("role", "TOOL_RESULT");
            obj.put("toolCallId", tr.id() != null ? tr.id() : "");
            obj.put("toolName", "");
            obj.put("content", tr.text());
        }
        return obj;
    }

    @SuppressWarnings("unchecked")
    private static ChatMessage deserialize(Map<String, Object> obj) {
        String role = (String) obj.getOrDefault("role", "");
        String content = (String) obj.getOrDefault("content", "");
        return switch (role) {
            case "SYSTEM" -> SystemMessage.from(content);
            case "USER" -> UserMessage.from(content);
            case "AI" -> {
                List<Map<String, String>> toolCalls = (List<Map<String, String>>) obj.get("toolCalls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    List<ToolExecutionRequest> requests = toolCalls.stream()
                            .map(c -> ToolExecutionRequest.builder()
                                    .id(c.get("id"))
                                    .name(c.get("name"))
                                    .arguments(c.get("arguments"))
                                    .build())
                            .toList();
                    yield content != null && !content.isBlank()
                            ? AiMessage.from(content, requests)
                            : AiMessage.from(requests);
                }
                yield AiMessage.from(content != null ? content : "");
            }
            case "TOOL_RESULT" -> {
                String id = (String) obj.getOrDefault("toolCallId", "");
                String toolName = (String) obj.getOrDefault("toolName", "");
                yield ToolExecutionResultMessage.from(id, toolName, content);
            }
            default -> null;
        };
    }

    private static String singleText(UserMessage um) {
        try {
            // LangChain4j UserMessage.singleText() — public in some versions
            return um.singleText();
        } catch (Exception e) {
            // Fallback: toString
            return um.toString();
        }
    }
}
