package com.anima.web;

import com.anima.agent.AgentLoop;
import com.anima.llm.DeepSeekProvider;
import com.anima.tool.BashTool;
import com.anima.tool.ReadFileTool;
import com.anima.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SSE endpoint using AgentLoop for tool-using chat completions.
 */
public class ChatEndpoint {

    private static final String SYSTEM_PROMPT = """
        You are Anima, a terminal AI coding agent. You have tools available.
        When the user asks you to do something, use the appropriate tool.
        After seeing tool results, synthesize a final answer.
        Never guess file contents — use read_file to read them.
        Never guess command output — use bash to run commands.
        Respond in the same language as the user.
        """;

    private final AgentLoop agentLoop;
    private final ObjectMapper json = new ObjectMapper();
    private final ToolRegistry toolRegistry;

    public ChatEndpoint(DeepSeekProvider provider) {
        this.toolRegistry = new ToolRegistry();
        this.toolRegistry.register(new ReadFileTool());
        this.toolRegistry.register(new BashTool());
        this.agentLoop = new AgentLoop(provider, toolRegistry, 10, SYSTEM_PROMPT);
    }

    public void register(Javalin app) {
        app.post("/api/chat", this::handleChat);
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

        CountDownLatch latch = new CountDownLatch(1);

        new Thread(() -> {
            agentLoop.run(message, new AgentLoop.LoopCallback() {
                @Override public void onUserMessage(String t) {
                    writeSse(out, "user", t);
                }
                @Override public void onThinking(String t) {
                    writeSse(out, "thinking", t);
                }
                @Override public void onResponse(String t) {
                    writeSse(out, "response", t);
                }
                @Override public void onToolStart(String name, String args) {
                    writeSse(out, "tool_start", jsonToolEvent(name, args, null));
                }
                @Override public void onToolResult(String name, String result) {
                    writeSse(out, "tool_result", jsonToolEvent(name, null, result));
                }
                @Override public void onUsage(DeepSeekProvider.Usage u) {
                    try {
                        writeSseRaw(out, "event: usage\ndata: " + json.writeValueAsString(Map.of("promptTokens", u.promptTokens(), "completionTokens", u.completionTokens())) + "\n\n");
                    } catch (Exception ignored) {}
                }
                @Override public void onComplete(String text) {
                    writeSseRaw(out, "event: done\ndata: {}\n\n");
                    latch.countDown();
                }
                @Override public void onError(Throwable e) {
                    try {
                        writeSseRaw(out, "event: error\ndata: " + json.writeValueAsString(Map.of("message", e.getMessage() != null ? e.getMessage() : "Unknown error")) + "\n\n");
                    } catch (Exception ignored) {}
                    latch.countDown();
                }
            });
        }).start();

        try { latch.await(120, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { out.close(); } catch (Exception ignored) {}
    }

    private String jsonToolEvent(String name, String args, String result) {
        try {
            var map = new java.util.LinkedHashMap<String, Object>();
            map.put("name", name);
            if (args != null) map.put("args", args);
            if (result != null) map.put("result", result.length() > 2000 ? result.substring(0, 2000) + "..." : result);
            return json.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
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
}
