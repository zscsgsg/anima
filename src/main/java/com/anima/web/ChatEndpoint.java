package com.anima.web;

import com.anima.llm.DeepSeekProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletResponse;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

        var req = ctx.req();
        var asyncCtx = req.startAsync();
        asyncCtx.setTimeout(120_000);

        var resp = (HttpServletResponse) asyncCtx.getResponse();
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("Access-Control-Allow-Origin", "*");

        CompletableFuture.runAsync(() -> {
            try {
                PrintWriter writer = resp.getWriter();
                provider.stream(
                    "You are Anima, a helpful AI coding assistant. Respond concisely.",
                    message,
                    new DeepSeekProvider.StreamListener() {
                        @Override
                        public void onThinking(String token) {
                            writeSse(writer, "thinking", token);
                        }

                        @Override
                        public void onResponse(String token) {
                            writeSse(writer, "response", token);
                        }

                        @Override
                        public void onComplete(DeepSeekProvider.Usage usage) {
                            try {
                                String done = json.writeValueAsString(Map.of(
                                    "promptTokens", usage.promptTokens(),
                                    "completionTokens", usage.completionTokens()
                                ));
                                writeSseRaw(writer, "event: done\ndata: " + done + "\n\n");
                                writer.close();
                                asyncCtx.complete();
                            } catch (Exception ignored) {
                                asyncCtx.complete();
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            try {
                                String err = json.writeValueAsString(Map.of(
                                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
                                ));
                                writeSseRaw(writer, "event: error\ndata: " + err + "\n\n");
                                writer.close();
                            } catch (Exception ignored) {}
                            asyncCtx.complete();
                        }
                    }
                );
            } catch (Exception e) {
                asyncCtx.complete();
            }
        });
    }

    private static void writeSse(PrintWriter writer, String event, String data) {
        writeSseRaw(writer, "event: " + event + "\ndata: " + escapeSse(data) + "\n\n");
    }

    private static void writeSseRaw(PrintWriter writer, String frame) {
        try {
            writer.write(frame);
            writer.flush();
        } catch (Exception ignored) {}
    }

    private static String escapeSse(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }
}
