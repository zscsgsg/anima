package com.anima.web;

import com.anima.agent.AgentLoop;
import com.anima.agent.PermissionDecision;
import com.anima.agent.PermissionGate;
import com.anima.llm.DeepSeekProvider;
import com.anima.llm.LLMProvider;
import com.anima.memory.ProjectMemory;
import com.anima.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import jakarta.servlet.ServletOutputStream;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

/**
 * SSE endpoint with interactive permission confirmation.
 */
public class ChatEndpoint {

    private static final String SYSTEM_PROMPT = """
        You are Anima, a terminal AI coding agent.
        You are running on Windows (cmd.exe).
        Use ls, glob, grep for cross-platform exploration without confirmation.
        Use read_file to read files — NEVER use bash type/cat for reading.
        Use write_file to create/overwrite files, edit_file for precise changes.
        Use bash ONLY for actual commands: build (mvn), git, tests, etc.

        EDITING WORKFLOW (follow this exactly):
        1. grep to locate the target line/string in the file
        2. read_file to see the exact text including ALL whitespace and indentation
        3. edit_file with old_string copied verbatim from read_file output
        If edit_file fails, go back to step 2 — re-read and copy EXACTLY.

        After seeing tool results, synthesize a final answer.
        Respond in the same language as the user.
        """;

    private final ToolRegistry toolRegistry;
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectMemory memory;

    /** Holds the pending confirmation future for the current request. */
    private volatile CompletableFuture<Boolean> pendingConfirm;

    public ChatEndpoint(DeepSeekProvider provider, ProjectMemory memory) {
        this.memory = memory;
        this.toolRegistry = new ToolRegistry();
        this.toolRegistry.register(new ReadFileTool());
        this.toolRegistry.register(new BashTool());
        this.toolRegistry.register(new WriteFileTool());
        this.toolRegistry.register(new EditFileTool());
        this.toolRegistry.register(new MoveFileTool());
        this.toolRegistry.register(new LsTool());
        this.toolRegistry.register(new GlobTool());
        this.toolRegistry.register(new GrepTool());
        this.toolRegistry.register(new CodeIndexTool());

        // LSP tools
        var lspMgr = new com.anima.lsp.LspManager(
            System.getProperty("user.dir", "."), com.anima.lsp.LspManager.defaultSpecs());
        for (var lspTool : com.anima.lsp.LspTool.tools(lspMgr))
            this.toolRegistry.register(lspTool);
    }

    public void register(Javalin app) {
        app.post("/api/chat", this::handleChat);
        app.post("/api/confirm", this::handleConfirm);
    }

    /** POST /api/confirm — user clicked Allow or Deny. */
    private void handleConfirm(Context ctx) {
        try {
            var body = json.readValue(ctx.body(), Map.class);
            Boolean allowed = (Boolean) body.getOrDefault("allow", false);
            var future = pendingConfirm;
            if (future != null && !future.isDone()) {
                future.complete(allowed);
                ctx.result("{\"ok\":true}");
            } else {
                ctx.result("{\"ok\":false,\"reason\":\"no pending confirmation\"}");
            }
        } catch (Exception e) {
            ctx.status(400).result("{\"error\":\"invalid\"}");
        }
    }

    private void handleChat(Context ctx) {
        Map<?, ?> body;
        try { body = json.readValue(ctx.body(), Map.class); }
        catch (Exception e) { ctx.status(400).result("{\"error\":\"Invalid JSON\"}"); return; }

        Object msgObj = body.get("message");
        if (!(msgObj instanceof String message) || message.isBlank()) {
            ctx.status(400).result("{\"error\":\"Missing message\"}"); return;
        }

        var resp = ctx.res();
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setStatus(200);

        ServletOutputStream out;
        try { out = resp.getOutputStream(); resp.flushBuffer(); }
        catch (Exception e) { return; }

        CountDownLatch done = new CountDownLatch(1);

        // Headless permission gate:
        // - Read-only tools → ALLOW
        // - Writers → ASK (sends tool_confirm SSE event, waits for user)
        PermissionGate gate = new PermissionGate() {
            @Override
            public PermissionDecision check(String toolName, String args, boolean readOnly) {
                // Read-only tools: no confirmation needed
                if (readOnly) return PermissionDecision.ALLOW;

                // Writers: ask user via SSE
                try {
                    writeSseRaw(out, "event: tool_confirm\ndata: " +
                        json.writeValueAsString(Map.of("name", toolName, "args", args)) + "\n\n");
                } catch (Exception ignored) {}

                pendingConfirm = new CompletableFuture<>();
                try {
                    Boolean allowed = pendingConfirm.get(60, TimeUnit.SECONDS);
                    pendingConfirm = null;
                    return (allowed != null && allowed) ? PermissionDecision.ALLOW : PermissionDecision.DENY;
                } catch (Exception e) {
                    pendingConfirm = null;
                    return PermissionDecision.DENY;
                }
            }
        };

        var agentLoop = new AgentLoop(new DeepSeekProvider(), toolRegistry, 0,
            buildSystemPrompt(), gate);

        new Thread(() -> {
            agentLoop.run(message, new AgentLoop.LoopCallback() {
                @Override public void onUserMessage(String t) { writeSse(out, "user", t); }
                @Override public void onThinking(String t) { writeSse(out, "thinking", t); }
                @Override public void onResponse(String t) { writeSse(out, "response", t); }
                @Override public void onToolStart(String name, String args) {
                    writeSse(out, "tool_start", jsonToolEvent(name, args, null));
                }
                @Override public void onToolPermissionDenied(String name, String args) {
                    writeSse(out, "tool_denied", jsonToolEvent(name, args, "denied by user"));
                }
                @Override public void onToolResult(String name, String result) {
                    writeSse(out, "tool_result", jsonToolEvent(name, null, result));
                }
                @Override public void onUsage(LLMProvider.Usage u) {
                    try { writeSseRaw(out, "event: usage\ndata: " + json.writeValueAsString(Map.of("promptTokens", u.promptTokens(), "completionTokens", u.completionTokens())) + "\n\n"); } catch (Exception ignored) {}
                }
                @Override public void onCompaction(String summary, int folded, int kept) {
                    try { writeSseRaw(out, "event: compaction\ndata: " + json.writeValueAsString(Map.of("folded", folded, "kept", kept)) + "\n\n"); } catch (Exception ignored) {}
                }
                @Override public void onComplete(String text) {
                    writeSseRaw(out, "event: done\ndata: {}\n\n");
                    done.countDown();
                }
                @Override public void onError(Throwable e) {
                    try { writeSseRaw(out, "event: error\ndata: " + json.writeValueAsString(Map.of("message", e.getMessage() != null ? e.getMessage() : "Unknown error")) + "\n\n"); } catch (Exception ignored) {}
                    done.countDown();
                }
            });
        }).start();

        try { done.await(180, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { out.close(); } catch (Exception ignored) {}
    }

    private String jsonToolEvent(String name, String args, String result) {
        try {
            var map = new java.util.LinkedHashMap<String, Object>();
            map.put("name", name);
            if (args != null) map.put("args", args);
            if (result != null) map.put("result", result.length() > 2000 ? result.substring(0, 2000) + "..." : result);
            return json.writeValueAsString(map);
        } catch (Exception e) { return "{}"; }
    }

    private static void writeSse(ServletOutputStream out, String event, String data) {
        writeSseRaw(out, "event: " + event + "\ndata: " + escapeSse(data) + "\n\n");
    }

    private static void writeSseRaw(ServletOutputStream out, String frame) {
        try { out.write(frame.getBytes(StandardCharsets.UTF_8)); out.flush(); } catch (Exception ignored) {}
    }

    private static String escapeSse(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }

    /** Build system prompt with optional project memory prepended. */
    private String buildSystemPrompt() {
        if (memory != null && !memory.isEmpty()) {
            return memory.block() + "\n\n" + SYSTEM_PROMPT;
        }
        return SYSTEM_PROMPT;
    }
}
