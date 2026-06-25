package com.anima.agent;

import com.anima.llm.DeepSeekProvider;
import com.anima.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Core agent state machine: REASON → (tool calls?) EXECUTE → REASON → ... → DONE.
 * Mirrors Reasonix's Run loop — minimal MVP: no compaction, no storm detection, no permissions.
 */
public class AgentLoop {

    private final DeepSeekProvider provider;
    private final ToolRegistry tools;
    private final int maxSteps;
    private final List<ChatMessage> history;
    private final String systemPrompt;

    private final PermissionGate permissionGate;

    public AgentLoop(DeepSeekProvider provider, ToolRegistry tools, int maxSteps, String systemPrompt) {
        this(provider, tools, maxSteps, systemPrompt, null);
    }

    public AgentLoop(DeepSeekProvider provider, ToolRegistry tools, int maxSteps, String systemPrompt, PermissionGate permissionGate) {
        this.provider = provider;
        this.tools = tools;
        this.maxSteps = maxSteps;
        this.systemPrompt = systemPrompt;
        this.permissionGate = permissionGate;
        this.history = new ArrayList<>();
        this.history.add(SystemMessage.from(systemPrompt));
    }

    /**
     * Run the agent loop. Returns the final text answer.
     * Events are pushed through the callback so the UI can stream.
     */
    public String run(String userInput, LoopCallback callback) {
        history.add(UserMessage.from(userInput));
        callback.onUserMessage(userInput);

        for (int step = 0; step < maxSteps; step++) {
            // Build tool schemas
            var toolSchemas = tools.list().isEmpty() ? List.<ToolSpecification>of() : tools.schemas();

            // Stream LLM call
            var result = new Object() {
                String text = "";
                List<DeepSeekProvider.ToolCall> calls = List.of();
                DeepSeekProvider.Usage usage = null;
                Throwable error = null;
            };
            var latch = new java.util.concurrent.CountDownLatch(1);

            provider.stream(new ArrayList<>(history), toolSchemas, new DeepSeekProvider.StreamListener() {
                @Override public void onThinking(String t) { callback.onThinking(t); }
                @Override public void onResponse(String t) { callback.onResponse(t); }
                @Override public void onComplete(String text, List<DeepSeekProvider.ToolCall> calls, DeepSeekProvider.Usage usage) {
                    result.text = text;
                    result.calls = calls != null ? calls : List.of();
                    result.usage = usage;
                    latch.countDown();
                }
                @Override public void onError(Throwable e) {
                    result.error = e;
                    latch.countDown();
                }
            });

            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }

            if (result.error != null) {
                callback.onError(result.error);
                return "Error: " + result.error.getMessage();
            }
            if (result.usage != null) {
                callback.onUsage(result.usage);
            }

            // Add assistant message to history
            String assistantText = result.text != null ? result.text : "";
            if (!result.calls.isEmpty()) {
                AiMessage aiMsg;
                if (assistantText != null && !assistantText.isBlank()) {
                    aiMsg = AiMessage.from(assistantText, result.calls.stream().map(c ->
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                            .id(c.id()).name(c.name()).arguments(c.arguments()).build()
                    ).toList());
                } else {
                    aiMsg = AiMessage.from(result.calls.stream().map(c ->
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                            .id(c.id()).name(c.name()).arguments(c.arguments()).build()
                    ).toList());
                }
                history.add(aiMsg);

                // Execute tools (with permission check)
                for (var call : result.calls) {
                    // Check permission gate
                    if (permissionGate != null && !permissionGate.allow(call.name(), call.arguments())) {
                        String denied = "Tool call denied by user: " + call.name();
                        callback.onToolPermissionDenied(call.name(), call.arguments());
                        history.add(ToolExecutionResultMessage.from(
                            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                .id(call.id()).name(call.name()).arguments(call.arguments()).build(),
                            denied
                        ));
                        continue;
                    }
                    callback.onToolStart(call.name(), call.arguments());
                    String toolResult;
                    try {
                        var tool = tools.get(call.name());
                        if (tool == null) {
                            toolResult = "Error: unknown tool '" + call.name() + "'";
                        } else {
                            toolResult = tool.execute(call.arguments());
                        }
                    } catch (Exception e) {
                        toolResult = "Error executing tool: " + e.getMessage();
                    }
                    callback.onToolResult(call.name(), toolResult);
                    history.add(ToolExecutionResultMessage.from(
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                            .id(call.id()).name(call.name()).arguments(call.arguments()).build(),
                        toolResult
                    ));
                }
                // Continue loop — LLM sees tool results and may call more tools
                continue;
            }

            // No tool calls → final answer
            history.add(AiMessage.from(assistantText));
            callback.onComplete(assistantText);
            return assistantText;
        }

        String msg = "Reached max steps (" + maxSteps + ") without final answer.";
        callback.onError(new RuntimeException(msg));
        return msg;
    }

    /** Callback interface for the UI to observe agent activity. */
    public interface LoopCallback {
        void onUserMessage(String text);
        void onThinking(String token);
        void onResponse(String token);
        void onToolStart(String toolName, String args);
        void onToolPermissionDenied(String toolName, String args);
        void onToolResult(String toolName, String result);
        void onUsage(DeepSeekProvider.Usage usage);
        void onComplete(String finalText);
        void onError(Throwable e);
    }
}
