package com.anima.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek streaming LLM provider with tool calling support.
 */
public class DeepSeekProvider {

    private final OpenAiStreamingChatModel model;

    public DeepSeekProvider() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "DEEPSEEK_API_KEY environment variable is not set.");
        }
        this.model = OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .modelName("deepseek-v4-flash")
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * Stream a chat completion with tool support.
     *
     * @param messages conversation history (system, user, assistant, tool messages)
     * @param tools    tool schemas (empty list = no tools)
     * @param listener receives tokens and tool calls as they arrive
     */
    public void stream(List<ChatMessage> messages, List<ToolSpecification> tools, StreamListener listener) {
        var builder = ChatRequest.builder().messages(messages);
        if (tools != null && !tools.isEmpty()) {
            builder.toolSpecifications(tools);
        }
        var request = builder.build();

        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialThinking(PartialThinking thinking) {
                if (thinking.text() != null) {
                    listener.onThinking(thinking.text());
                }
            }

            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse != null) {
                    listener.onResponse(partialResponse);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                var aiMsg = completeResponse.aiMessage();
                List<ToolCall> toolCalls = new ArrayList<>();
                if (aiMsg != null && aiMsg.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                        toolCalls.add(new ToolCall(req.id(), req.name(), req.arguments()));
                    }
                }
                String text = aiMsg != null ? aiMsg.text() : "";
                var tu = completeResponse.metadata() != null ? completeResponse.metadata().tokenUsage() : null;
                int pt = tu != null ? tu.inputTokenCount() : 0;
                int ct = tu != null ? tu.outputTokenCount() : 0;
                listener.onComplete(text, toolCalls, new Usage(pt, ct));
            }

            @Override
            public void onError(Throwable error) {
                listener.onError(error);
            }
        });
    }

    public record Usage(int promptTokens, int completionTokens) {}
    public record ToolCall(String id, String name, String arguments) {}

    public interface StreamListener {
        void onThinking(String token);
        void onResponse(String token);
        void onComplete(String text, List<ToolCall> toolCalls, Usage usage);
        void onError(Throwable e);
    }
}
