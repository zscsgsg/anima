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
 * DeepSeek streaming LLM provider — implements LLMProvider for the AgentLoop.
 * Supports any OpenAI-compatible endpoint by passing modelName + baseUrl.
 */
public class DeepSeekProvider implements LLMProvider {
    // 模型的标签，用于标识模型
    private final String label;
    // 模型的名称，用于标识模型
    private final String modelName;
    // 模型的实现，用于生成文本
    private final OpenAiStreamingChatModel model;

    public DeepSeekProvider() {
        this("flash", "deepseek-v4-flash", "https://api.deepseek.com");
    }

    public DeepSeekProvider(String label, String modelName, String baseUrl) {
        this.label = label;
        this.modelName = modelName;
        // Priority: env var > system property
        // 从环境变量获取 API 密钥，如果不存在则从系统属性获取
        // 从系统属性获取 API 密钥，如果不存在则抛出异常
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("anima.api.key");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY environment variable is not set.");
        }
        // 创建 OpenAiStreamingChatModel 实例，用于生成文本
        this.model = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl) // 模型的地址
                .apiKey(apiKey) // 模型的密钥
                .modelName(modelName) // 模型的名称
                .timeout(Duration.ofSeconds(120)) // 模型的超时时间
                .logRequests(true) // 打印请求日志，方便调试
                .logResponses(true) // 打印响应日志，方便调试
                .build();
    }

    @Override public String label() { return label; }
    @Override public String modelName() { return modelName; }
    // 流式生成文本
    @Override
    public void stream(List<ChatMessage> messages, List<ToolSpecification> tools, StreamListener listener) {
        var builder = ChatRequest.builder().messages(messages);
        if (tools != null && !tools.isEmpty()) builder.toolSpecifications(tools);
        //handler 是一个匿名内部类，有 4 个回调
        model.chat(builder.build(), new StreamingChatResponseHandler() {
            //大模型在"思考"，把思考过程告诉 listener
            @Override public void onPartialThinking(PartialThinking t) {
                if (t.text() != null) listener.onThinking(t.text());
            }
            //大模型在"回答"，把回答文字告诉 listener
            @Override public void onPartialResponse(String pr) {
                if (pr != null) listener.onResponse(pr);
            }
            // 全部生成完了
            @Override public void onCompleteResponse(ChatResponse resp) {
                //这个是AI的回复
                var ai = resp.aiMessage();
                //工具的集合
                List<ToolCall> calls = new ArrayList<>();
                if (ai != null && ai.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : ai.toolExecutionRequests())
                        //从 resp 里提取工具调用列表    arguments这个是工具的参数
                        calls.add(new ToolCall(req.id(), req.name(), req.arguments()));
                }
                //从 resp 里提取 token 用量
                String text = ai != null ? ai.text() : "";
                var tu = resp.metadata() != null ? resp.metadata().tokenUsage() : null;
                int pt = tu != null ? tu.inputTokenCount() : 0;
                int ct = tu != null ? tu.outputTokenCount() : 0;
                //这个返回完整text文本，工具调用数，token的使用情况
                listener.onComplete(text, calls, new Usage(pt, ct));
            }
            //这个是网络出现错误和API出现错误，返回给listener 错误信息
            @Override public void onError(Throwable e) { listener.onError(e); }
        });
    }

    @Override
    public CompleteResult complete(List<ChatMessage> messages, List<ToolSpecification> tools) {
        // 这个是同步调用，返回完整text文本，token的使用情况
        var result = new Object() { String text = ""; Usage usage = null; Throwable error = null; };
        // 这个是一个计数器，用来等待异步任务完成 大模型说完再走
        var latch = new java.util.concurrent.CountDownLatch(1);
        // 这个是异步调用，返回完整text文本，token的使用情况
        model.chat(ChatRequest.builder().messages(messages)
                .toolSpecifications(tools != null && !tools.isEmpty() ? tools : List.of()).build(),
            //设置一个"应答机"——告诉它收到回复时该干嘛
            new StreamingChatResponseHandler() {
              // StringBuilder buf，就像一个接水桶，大模型每次吐一个字，就往桶里倒
                final StringBuilder buf = new StringBuilder();
                @Override public void onPartialResponse(String pr) { if (pr != null) buf.append(pr); }
                @Override public void onPartialThinking(PartialThinking pt) {}
                @Override public void onCompleteResponse(ChatResponse resp) {
                    //把桶里的水全倒出来，这就是大模型给我的完整回复
                    result.text = buf.toString();
                    //从 resp 里提取 token 用量
                    var tu = resp.metadata() != null ? resp.metadata().tokenUsage() : null;
                    //这个是token的使用情况，inputTokenCount是输入的token数，outputTokenCount是输出的token数
                    result.usage = new Usage(tu != null ? tu.inputTokenCount() : 0, tu != null ? tu.outputTokenCount() : 0);
                    //计数器减一，到零，告诉主线程，我已经完成了
                    latch.countDown();
                }
                @Override public void onError(Throwable error) { result.error = error; latch.countDown(); }
            });
        try {
            //主线程等待计数器减为零，也就是大模型说完了，我才能走
            latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (result.error != null) throw new RuntimeException(result.error);
        return new CompleteResult(result.text, result.usage);
    }
}
