# Anima Step 1: DeepSeek 流式调用 + HTML 演示

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建最小可运行程序：用户在 HTML 终端风格页面输入 → Java 后端流式调用 DeepSeek API → 逐 token 展示。

**Architecture:** Javalin 嵌入式 HTTP 服务 + SSE 推送。LangChain4j 的 OpenAiStreamingChatModel 对接 DeepSeek（OpenAI 兼容端点）。单一 HTML 文件内嵌终端风格 CSS/JS。

**Tech Stack:** Java 21, Maven 3.9+, LangChain4j 1.16.3, Javalin 6.6.0, Jackson 2.18.3, SLF4J Simple 2.0.17

## Global Constraints

- Java 21 语法（record、switch 表达式、文本块等）
- 所有代码文件 UTF-8 编码
- API Key 从环境变量 `DEEPSEEK_API_KEY` 读取，不硬编码
- 零外部配置文件（Step 1 不需要 config 文件）
- HTML 页面终端风格：黑底 (#1a1a2e)、等宽字体 (Consolas/Cascadia Code)、绿色强调色

---

### Task 1: 创建 Maven 项目骨架

**Files:**
- Create: `pom.xml`

**Interfaces:**
- Produces: Maven 项目，坐标 `com.anima:anima:0.1.0`，Java 21，依赖 langchain4j、langchain4j-open-ai、javalin、jackson、slf4j-simple

- [ ] **Step 1: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.anima</groupId>
    <artifactId>anima</artifactId>
    <version>0.1.0</version>
    <packaging>jar</packaging>

    <name>Anima</name>
    <description>DeepSeek-native terminal AI coding agent — Java + LangChain4j</description>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <langchain4j.version>1.16.3</langchain4j.version>
        <javalin.version>6.6.0</javalin.version>
        <jackson.version>2.18.3</jackson.version>
        <slf4j.version>2.0.17</slf4j.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>dev.langchain4j</groupId>
                <artifactId>langchain4j-bom</artifactId>
                <version>${langchain4j.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j</artifactId>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-open-ai</artifactId>
        </dependency>
        <dependency>
            <groupId>io.javalin</groupId>
            <artifactId>javalin</artifactId>
            <version>${javalin.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
            <version>${slf4j.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>21</source>
                    <target>21</target>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 验证 pom.xml 可解析**

```bash
cd d:\1111\code\Anima
mvn validate
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "feat: add Maven project skeleton with LangChain4j + Javalin"
```

---

### Task 2: 创建 DeepSeekProvider（LLM 流式调用封装）

**Files:**
- Create: `src/main/java/com/anima/llm/DeepSeekProvider.java`

**Interfaces:**
- Produces: `DeepSeekProvider` 类，构造时从环境变量读取 API Key，提供 `stream(systemPrompt, userMessage, listener)` 方法
- Consumes: LangChain4j `OpenAiStreamingChatModel`、`StreamingChatResponseHandler`

- [ ] **Step 1: 创建目录并编写 DeepSeekProvider.java**

```java
package com.anima.llm;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.time.Duration;

/**
 * DeepSeek streaming LLM provider.
 * Wraps LangChain4j's OpenAiStreamingChatModel pointed at DeepSeek's API.
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
        var messages = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();
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
                var usage = completeResponse.metadata() != null
                        ? completeResponse.metadata().usage()
                        : null;
                int promptTokens = usage != null ? usage.inputTokenCount() : 0;
                int completionTokens = usage != null ? usage.outputTokenCount() : 0;
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
```

- [ ] **Step 2: 编译验证**

```bash
cd d:\1111\code\Anima
mvn compile
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/anima/llm/DeepSeekProvider.java
git commit -m "feat: add DeepSeekProvider for streaming LLM calls"
```

---

### Task 3: 创建 ChatEndpoint（SSE 端点）

**Files:**
- Create: `src/main/java/com/anima/web/ChatEndpoint.java`

**Interfaces:**
- Consumes: `DeepSeekProvider.StreamListener`、`DeepSeekProvider.Usage`
- Produces: `ChatEndpoint` 类，提供 `register(Javalin app)` 注册 POST /api/chat SSE 端点

- [ ] **Step 1: 编写 ChatEndpoint.java**

```java
package com.anima.web;

import com.anima.llm.DeepSeekProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
        Map<String, String> body;
        try {
            body = json.readValue(ctx.body(), Map.class);
        } catch (Exception e) {
            ctx.status(400).result("{\"error\": \"Invalid JSON body\"}");
            return;
        }

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            ctx.status(400).result("{\"error\": \"Missing 'message' field\"}");
            return;
        }

        ctx.header("Access-Control-Allow-Origin", "*");

        // Javalin SSE: server-sent events stream
        ctx.contentType("text/event-stream");
        ctx.header("Cache-Control", "no-cache");
        ctx.header("Connection", "keep-alive");

        var out = ctx.outputStream();
        try {
            provider.stream(
                "You are Anima, a helpful AI coding assistant. Respond concisely.",
                message,
                new DeepSeekProvider.StreamListener() {
                    @Override
                    public void onThinking(String token) {
                        try {
                            out.write(("event: thinking\ndata: " + escapeSse(token) + "\n\n").getBytes());
                            out.flush();
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onResponse(String token) {
                        try {
                            out.write(("event: response\ndata: " + escapeSse(token) + "\n\n").getBytes());
                            out.flush();
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onComplete(DeepSeekProvider.Usage usage) {
                        try {
                            String done = json.writeValueAsString(Map.of(
                                "promptTokens", usage.promptTokens(),
                                "completionTokens", usage.completionTokens()
                            ));
                            out.write(("event: done\ndata: " + done + "\n\n").getBytes());
                            out.flush();
                            out.close();
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onError(Throwable e) {
                        try {
                            String err = json.writeValueAsString(Map.of(
                                "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
                            ));
                            out.write(("event: error\ndata: " + err + "\n\n").getBytes());
                            out.flush();
                            out.close();
                        } catch (Exception ignored) {}
                    }
                }
            );
        } catch (Exception e) {
            try {
                out.write(("event: error\ndata: " + json.writeValueAsString(Map.of("message", e.getMessage())) + "\n\n").getBytes());
                out.flush();
                out.close();
            } catch (Exception ignored) {}
        }
    }

    /** Escape \n in SSE data so multi-line strings don't break the protocol. */
    private static String escapeSse(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd d:\1111\code\Anima
mvn compile
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/anima/web/ChatEndpoint.java
git commit -m "feat: add SSE chat endpoint for streaming responses"
```

---

### Task 4: 创建 AnimaApp（入口 + 静态文件服务）

**Files:**
- Create: `src/main/java/com/anima/AnimaApp.java`

**Interfaces:**
- Consumes: `DeepSeekProvider`、`ChatEndpoint`
- Produces: 可运行的主类，启动 Javalin 在 8080 端口，提供 SSE 端点和静态 HTML

- [ ] **Step 1: 编写 AnimaApp.java**

```java
package com.anima;

import com.anima.llm.DeepSeekProvider;
import com.anima.web.ChatEndpoint;
import io.javalin.Javalin;

/**
 * Anima — DeepSeek-native terminal AI coding agent.
 * Step 1: streaming chat with HTML demo.
 */
public class AnimaApp {

    public static void main(String[] args) {
        System.out.println("Anima v0.1.0 starting...");

        DeepSeekProvider provider = new DeepSeekProvider();
        System.out.println("DeepSeek provider initialized.");

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.directory = "/web";
                staticFiles.location = io.javalin.http.staticfiles.Location.CLASSPATH;
            });
            config.http.asyncTimeout = 120_000L;
        });

        // Register SSE endpoint
        new ChatEndpoint(provider).register(app);

        // Health check
        app.get("/api/health", ctx -> ctx.result("OK"));

        app.start(8080);
        System.out.println("Anima running at http://localhost:8080");
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd d:\1111\code\Anima
mvn compile
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/anima/AnimaApp.java
git commit -m "feat: add AnimaApp entry point with Javalin server"
```

---

### Task 5: 创建终端风格 HTML 演示页面

**Files:**
- Create: `src/main/resources/web/index.html`

**Spec:** 终端风格页面 — 黑底 (#1a1a2e)、等宽字体、绿色强调色、输入框在底部、消息区滚动

- [ ] **Step 1: 编写 index.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Anima — Terminal AI Agent</title>
<style>
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

body {
    background: #1a1a2e;
    color: #e0e0e0;
    font-family: 'Cascadia Code', 'Consolas', 'Courier New', monospace;
    height: 100vh;
    display: flex;
    flex-direction: column;
    overflow: hidden;
}

/* Header */
.header {
    background: #16213e;
    border-bottom: 1px solid #0f3460;
    padding: 8px 16px;
    font-size: 13px;
    color: #a0a0b0;
    display: flex;
    align-items: center;
    gap: 16px;
    flex-shrink: 0;
}
.header .brand { color: #e94560; font-weight: bold; }
.header .dot { color: #00ff88; }
.header .sep { color: #333; }

/* Messages area */
.messages {
    flex: 1;
    overflow-y: auto;
    padding: 16px;
    display: flex;
    flex-direction: column;
    gap: 12px;
}
.messages::-webkit-scrollbar { width: 6px; }
.messages::-webkit-scrollbar-track { background: #1a1a2e; }
.messages::-webkit-scrollbar-thumb { background: #333; border-radius: 3px; }

/* Message bubbles */
.msg {
    max-width: 85%;
    padding: 8px 14px;
    border-radius: 6px;
    font-size: 14px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
    animation: fadeIn 0.15s ease;
}
@keyframes fadeIn { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }

.msg.user {
    align-self: flex-end;
    background: #0f3460;
    color: #e0e0e0;
    border: 1px solid #1a4a8a;
}
.msg.assistant {
    align-self: flex-start;
    background: #16213e;
    color: #d0d0d0;
    border: 1px solid #1a3a5c;
}
.msg.thinking {
    align-self: flex-start;
    background: #1a1a2e;
    color: #888;
    border: 1px dashed #333;
    font-style: italic;
}
.msg.error {
    align-self: center;
    background: #3a1010;
    color: #ff6b6b;
    border: 1px solid #8a2020;
    font-size: 13px;
}
.msg.system {
    align-self: center;
    color: #555;
    font-size: 12px;
    border: none;
    background: transparent;
}

/* Cursor blink for streaming */
.msg.streaming::after {
    content: "▊";
    color: #e94560;
    animation: blink 0.8s infinite;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }

/* Input area */
.input-area {
    background: #16213e;
    border-top: 1px solid #0f3460;
    padding: 12px 16px;
    display: flex;
    gap: 10px;
    flex-shrink: 0;
}
.input-area input {
    flex: 1;
    background: #1a1a2e;
    border: 1px solid #333;
    color: #e0e0e0;
    font-family: inherit;
    font-size: 14px;
    padding: 10px 14px;
    border-radius: 4px;
    outline: none;
}
.input-area input:focus { border-color: #e94560; }
.input-area button {
    background: #e94560;
    color: white;
    border: none;
    font-family: inherit;
    font-size: 14px;
    padding: 10px 20px;
    border-radius: 4px;
    cursor: pointer;
    transition: background 0.2s;
}
.input-area button:hover { background: #d63850; }
.input-area button:disabled { background: #555; cursor: not-allowed; }

/* Status bar */
.status {
    background: #0f3460;
    padding: 4px 16px;
    font-size: 11px;
    color: #888;
    display: flex;
    gap: 20px;
    flex-shrink: 0;
}
.status .label { color: #666; }
.status .value { color: #00ff88; }
</style>
</head>
<body>

<div class="header">
    <span class="brand">⬡ Anima</span>
    <span class="sep">|</span>
    <span>v0.1.0</span>
    <span class="sep">|</span>
    <span>model: <span class="dot">●</span> deepseek-chat</span>
    <span class="sep">|</span>
    <span>mode: ask</span>
</div>

<div class="messages" id="messages">
    <div class="msg system">Anima v0.1.0 — DeepSeek-native terminal agent. Type a message to begin.</div>
</div>

<div class="input-area">
    <input type="text" id="input" placeholder="> 输入消息，Enter 发送..." autofocus />
    <button id="sendBtn">Send</button>
</div>

<div class="status">
    <span><span class="label">prompt:</span> <span class="value" id="statPrompt">0</span></span>
    <span><span class="label">completion:</span> <span class="value" id="statCompletion">0</span></span>
    <span id="statStatus"></span>
</div>

<script>
const messagesEl = document.getElementById('messages');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('sendBtn');
const statPrompt = document.getElementById('statPrompt');
const statCompletion = document.getElementById('statCompletion');
const statStatus = document.getElementById('statStatus');

let isStreaming = false;

function addMessage(type, text) {
    const div = document.createElement('div');
    div.className = 'msg ' + type;
    div.textContent = text;
    messagesEl.appendChild(div);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return div;
}

function sendMessage() {
    const text = inputEl.value.trim();
    if (!text || isStreaming) return;
    inputEl.value = '';
    inputEl.disabled = true;
    sendBtn.disabled = true;
    isStreaming = true;
    statStatus.textContent = 'thinking...';

    addMessage('user', text);

    const thinkingDiv = addMessage('thinking', '');
    const responseDiv = addMessage('assistant', '');
    responseDiv.classList.add('streaming');
    let thinkingStarted = false;
    let responseStarted = false;

    fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: text })
    }).then(response => {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        function process() {
            reader.read().then(({ done, value }) => {
                if (done) return;
                buffer += decoder.decode(value, { stream: true });

                // Parse SSE events
                const lines = buffer.split('\n');
                buffer = lines.pop() || '';
                let eventType = '';
                let eventData = '';

                for (const line of lines) {
                    if (line.startsWith('event: ')) {
                        eventType = line.slice(7).trim();
                    } else if (line.startsWith('data: ')) {
                        eventData = line.slice(6);
                        // Fire event
                        if (eventType === 'thinking') {
                            thinkingStarted = true;
                            thinkingDiv.style.display = 'block';
                            thinkingDiv.textContent += eventData.replace(/\\n/g, '\n');
                        } else if (eventType === 'response') {
                            if (!responseStarted) {
                                thinkingDiv.classList.remove('thinking');
                                thinkingDiv.classList.add('system');
                                thinkingDiv.textContent = thinkingDiv.textContent || '(thought process hidden)';
                                responseStarted = true;
                            }
                            responseDiv.textContent += eventData.replace(/\\n/g, '\n');
                            statStatus.textContent = 'streaming';
                        } else if (eventType === 'done') {
                            try {
                                const usage = JSON.parse(eventData);
                                statPrompt.textContent = usage.promptTokens;
                                statCompletion.textContent = usage.completionTokens;
                            } catch(e) {}
                        } else if (eventType === 'error') {
                            try {
                                const err = JSON.parse(eventData);
                                addMessage('error', '✗ ' + err.message);
                            } catch(e) {
                                addMessage('error', '✗ Connection error');
                            }
                        }
                        eventType = '';
                        eventData = '';
                    }
                }
                messagesEl.scrollTop = messagesEl.scrollHeight;
                process();
            }).catch(err => {
                addMessage('error', '✗ ' + err.message);
                finish();
            });
        }
        process();
        return new Promise(() => {}); // never resolve — keep connection open
    }).catch(err => {
        addMessage('error', '✗ ' + err.message);
        finish();
    });
}

function finish() {
    isStreaming = false;
    inputEl.disabled = false;
    sendBtn.disabled = false;
    inputEl.focus();
    statStatus.textContent = '';
    // Remove streaming cursor from last assistant message
    document.querySelectorAll('.msg.streaming').forEach(el => el.classList.remove('streaming'));
}

inputEl.addEventListener('keydown', e => { if (e.key === 'Enter') sendMessage(); });
sendBtn.addEventListener('click', sendMessage);
</script>
</body>
</html>
```

- [ ] **Step 2: 编译 + 打包验证**

```bash
cd d:\1111\code\Anima
mvn compile
```
Expected: BUILD SUCCESS（HTML 在 resources 下，编译时自动拷贝）

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/web/index.html
git commit -m "feat: add terminal-style HTML demo page with SSE streaming"
```

---

### Task 6: 集成测试 — 启动并验证

**Files:** 无新文件

- [ ] **Step 1: 设置 API Key 并启动**

```bash
set DEEPSEEK_API_KEY=sk-your-key-here
cd d:\1111\code\Anima
mvn exec:java -Dexec.mainClass="com.anima.AnimaApp"
```
Expected: 控制台输出 "Anima running at http://localhost:8080"

- [ ] **Step 2: 打开浏览器验证**

打开 http://localhost:8080，输入 "用Java写一个Hello World"，观察：
- 思考过程（如果有）显示在灰色斜体框
- 正文逐字显示在深蓝色框
- 流式输出时有闪烁光标
- 完成后状态栏显示 token 消耗

- [ ] **Step 3: 验证错误处理**

关闭 DeepSeek API Key（设为无效值），重启，输入消息：
- 前端显示红色错误消息
- 不会崩溃或白屏

- [ ] **Step 4: Commit（如有微调）**

```bash
git add -A
git commit -m "chore: finalize Step 1 integration"
```
