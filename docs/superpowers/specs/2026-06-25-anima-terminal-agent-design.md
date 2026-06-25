# Anima — 终端智能体设计文档

> 创建日期：2026-06-25
> 状态：Step 1 已确认，其余为规划

## 1. 项目定位

Anima 是一个 Java + LangChain4j 实现的 DeepSeek 终端 AI 编程智能体，对标 Claude Code 和 Reasonix 的能力边界与交互体验。

- **语言**：Java 21
- **LLM 框架**：LangChain4j 1.16.3（仅做 Provider 层，Agent Loop 自研）
- **终端 UI**：Claude Code 风格（原生 cmd 滚动输出 + ANSI 颜色）
- **发布**：Maven shade JAR + 启动脚本，后续考虑 GraalVM Native Image
- **差异化**：Java 工程能力实现跨语言通用工具；DeepSeek cache-first 优化

## 2. 架构方案（方案 C）

LangChain4j 负责 LLM 调用和工具描述生成，Agent Loop（状态机、超时、重试、压缩、权限）完全自研。

```
┌─────────────────────────────────────────┐
│              终端 UI（cmd 风格）          │
├─────────────────────────────────────────┤
│              Agent Loop（自研）           │
│   REASON → AWAIT_CONFIRM → ACT → 循环    │
├──────────────────┬──────────────────────┤
│  Provider (LC4j) │  Tool Registry (自研) │
│  DeepSeek API    │  read_file/write/... │
└──────────────────┴──────────────────────┘
```

## 3. 分步实现计划

| Step | 功能              | 核心产出                           |
| ---- | ----------------- | ---------------------------------- |
| 1    | DeepSeek 流式调用 | 能对话的最小程序 + HTML 演示       |
| 2    | Agent Loop        | 多轮工具调用循环                   |
| 3    | 文件读写工具      | read_file / write_file / edit_file |
| 4    | Bash 执行         | shell 命令工具                     |
| 5    | 终端 UI           | Claude cmd 风格终端界面            |
| 6    | 权限控制          | ask/allow/deny                     |
| 7    | 上下文压缩        | 长对话自动压缩                     |
| 8    | 项目记忆          | REASONIX.md / AGENTS.md 加载       |
| 9    | MCP 插件          | 外部工具扩展                       |

## 4. Step 1 详细设计

### 4.1 技术选型

| 组件      | 选型                      | 版本   |
| --------- | ------------------------- | ------ |
| 构建      | Maven                     | 3.9+   |
| JDK       | Java 21                   | —      |
| LLM 调用  | LangChain4j + OpenAI 模块 | 1.16.3 |
| HTTP 服务 | Javalin                   | 6.x    |
| JSON      | Jackson                   | 2.18.3 |
| 日志      | SLF4J Simple              | 2.0.17 |

### 4.2 文件结构

```
anima/
├── pom.xml
├── src/main/java/com/anima/
│   ├── AnimaApp.java              # 入口：启动 HTTP + SSE
│   ├── llm/
│   │   └── DeepSeekProvider.java  # 封装 LangChain4j 流式调用
│   └── web/
│       └── ChatEndpoint.java      # Javalin SSE 端点
└── src/main/resources/web/
    └── index.html                 # 终端风格演示页面
```

### 4.3 核心接口

```java
// DeepSeekProvider — 流式调用封装
public class DeepSeekProvider {
    void stream(String systemPrompt, String userMessage, StreamListener listener);

    interface StreamListener {
        void onThinking(String token);    // reasoning_content（思考过程）
        void onResponse(String token);    // 正文内容
        void onComplete(Usage usage);     // token 消耗
        void onError(Throwable e);
    }
}
```

### 4.4 SSE 端点

```
POST /api/chat
Body: { "message": "用户输入" }

SSE Events:
  event: thinking  data: <token>    ← DeepSeek 思考过程
  event: response  data: <token>    ← 正文输出
  event: done      data: { "promptTokens": N, "completionTokens": M }
  event: error     data: { "message": "..." }
```

### 4.5 验收标准

- [ ] 用户输入内容后，DeepSeek 逐 token 流式返回
- [ ] reasoning_content（思考过程）正确显示
- [ ] 网络中断时前端显示错误，不崩溃
- [ ] API Key 从环境变量 `DEEPSEEK_API_KEY` 读取
- [ ] HTML 页面为终端风格（黑底、ANSI 色、等宽字体）

## 5. 后续规划（概略）

Step 2-9 的详细设计将在对应 Step 开始前单独确认，核心原则：

- 每个 Step 独立可运行、可演示
- Agent Loop 完全自研，不依赖 LangChain4j 的 AiServices
- 终端 UI 采用 Claude Code 风格：原生 cmd 滚动输出 + ANSI 颜色
- DeepSeek cache-first：系统提示词前缀保持稳定
