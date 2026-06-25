package com.anima.llm;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.time.Duration;
import java.util.ArrayList;

/**
 * DeepSeek streaming LLM provider.
 * Wraps LangChain4j's OpenAiStreamingChatModel pointed at DeepSeek's OpenAI-compatible API.
 */
public class DeepSeekProvider {

    private final OpenAiStreamingChatModel model;

    public DeepSeekProvider() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "DEEPSEEK_API_KEY environment variable is not set. " +
                "Get your key at https://platform.deepseek.com/api_keys"
            );
        }

        this.model = OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .modelName("deepseek-chat")
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * Stream a chat completion to the given listener.
     *
     * @param systemPrompt the system message (can be null)
     * @param userMessage  the user's input
     * @param listener     receives tokens as they arrive
     */
    public void stream(String systemPrompt, String userMessage, StreamListener listener) {
        var messages = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));

        var request = ChatRequest.builder()
                .messages(messages)
                .build();

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
                var tokenUsage = completeResponse.metadata() != null
                        ? completeResponse.metadata().tokenUsage()
                        : null;
                int promptTokens = tokenUsage != null ? tokenUsage.inputTokenCount() : 0;
                int completionTokens = tokenUsage != null ? tokenUsage.outputTokenCount() : 0;
                listener.onComplete(new Usage(promptTokens, completionTokens));
            }

            @Override
            public void onError(Throwable error) {
                listener.onError(error);
            }
        });
    }

    /** Token usage record. */
    public record Usage(int promptTokens, int completionTokens) {}

    /** Callback for streaming responses. */
    public interface StreamListener {
        void onThinking(String token);
        void onResponse(String token);
        void onComplete(Usage usage);
        void onError(Throwable e);
    }
}
