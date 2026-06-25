package com.anima.web;

import com.anima.llm.DeepSeekProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * SSE endpoint for streaming chat completions.
 * POST /api/chat → SSE stream with events: thinking, response, done, error.
 */
public class ChatEndpoint {

    private final DeepSeekProvider provider;
    private final ObjectMapper json = new ObjectMapper();

    public ChatEndpoint(DeepSeekProvider provider) {
        this.provider = provider;
    }

    public void register(Javalin app) {
        app.post("/api/chat", this::handleChat);
    }

    private void handleChat(Context ctx) {
        Map<?, ?> body;
        try {
            body = json.readValue(ctx.body(), Map.class);
        } catch (Exception e) {
            ctx.status(400).result("{\"error\": \"Invalid JSON body\"}");
            return;
        }

        Object msgObj = body.get("message");
        if (!(msgObj instanceof String message) || message.isBlank()) {
            ctx.status(400).result("{\"error\": \"Missing 'message' field\"}");
            return;
        }

        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.contentType("text/event-stream");
        ctx.header("Cache-Control", "no-cache");
        ctx.header("Connection", "keep-alive");

        OutputStream out;
        try {
            out = ctx.res().getOutputStream();
        } catch (Exception e) {
            ctx.status(500).result("{\"error\": \"Cannot get output stream\"}");
            return;
        }

        try {
            provider.stream(
                "You are Anima, a helpful AI coding assistant. Respond concisely.",
                message,
                new DeepSeekProvider.StreamListener() {
                    @Override
                    public void onThinking(String token) {
                        writeSse(out, "thinking", token);
                    }

                    @Override
                    public void onResponse(String token) {
                        writeSse(out, "response", token);
                    }

                    @Override
                    public void onComplete(DeepSeekProvider.Usage usage) {
                        try {
                            String done = json.writeValueAsString(Map.of(
                                "promptTokens", usage.promptTokens(),
                                "completionTokens", usage.completionTokens()
                            ));
                            writeSseRaw(out, "event: done\ndata: " + done + "\n\n");
                            out.close();
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onError(Throwable e) {
                        try {
                            String err = json.writeValueAsString(Map.of(
                                "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
                            ));
                            writeSseRaw(out, "event: error\ndata: " + err + "\n\n");
                            out.close();
                        } catch (Exception ignored) {}
                    }
                }
            );
        } catch (Exception e) {
            try {
                writeSseRaw(out, "event: error\ndata: " + json.writeValueAsString(Map.of("message", e.getMessage())) + "\n\n");
                out.close();
            } catch (Exception ignored) {}
        }
    }

    private static void writeSse(OutputStream out, String event, String data) {
        writeSseRaw(out, "event: " + event + "\ndata: " + escapeSse(data) + "\n\n");
    }

    private static void writeSseRaw(OutputStream out, String frame) {
        try {
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {}
    }

    /** Escape \n in SSE data so multi-line strings don't break the protocol. */
    private static String escapeSse(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }
}
