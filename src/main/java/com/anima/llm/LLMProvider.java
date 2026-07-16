package com.anima.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * Abstract LLM Provider — decouples the agent loop from a specific model.
 * Implementations: DeepSeekAdapter, OpenAIAdapter, etc.
 */
public interface LLMProvider {

    /** Human-readable label shown in /model and status bar.
     *  UI 显示名，如 "DeepSeek V4"
     * */
    String label();

    /** The underlying model name sent to the API (e.g. "deepseek-v4-pro").
     * 实际发给 API 的名字，如 "deepseek-v4-pro" */
    String modelName();

    /**
     * Stream a chat completion with tool support.
     *
     * @param messages conversation history
     * @param tools    tool schemas (empty = no tools)
     * @param listener receives tokens and tool calls
     */
    void stream(List<ChatMessage> messages, List<ToolSpecification> tools, StreamListener listener);

    /**
     * Synchronous (non-streaming) completion — used for compaction summarization.
     * 同步 一次性返回完整结果  用于压缩总结  这个就像执行/compact或者到底窗口上下文自动执行这个方法进行压缩
     */
    CompleteResult complete(List<ChatMessage> messages, List<ToolSpecification> tools);

    /** Token usage record.
     * token使用情况     */
    record Usage(int promptTokens, int completionTokens) {}

    /** A tool call requested by the model.  工具调用情况 */
    record ToolCall(String id, String name, String arguments) {}

    /** Result of a synchronous completion. 一次完整响应的封装  同步调用的返回结果
     * 封装了模型生成的完整文本内容，以及本次调用的 Token 消耗统计。
     * 适用于所有需要一次性获取完整结果的非流式场景（如压缩总结、批处理、结构化提取等）
     * */
    record CompleteResult(String text, Usage usage) {}

    /** Callback for streaming responses. */
    interface StreamListener {
        void onThinking(String token); // 模型思考
        void onResponse(String token); // 正式回复
        void onComplete(String text, List<ToolCall> toolCalls, Usage usage); // 模型生成完成时的回调（可以回复一段话后，要调用工具在这个弄）
        void onError(Throwable e); // 出错时的回调
    }
}
