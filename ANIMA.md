# Anima 项目记忆

此文件在每个会话启动时自动加载到系统提示词中（作为 DeepSeek 前缀缓存的稳定部分）。
修改后需重启 Anima 生效。它是 Anima 对标 Claude Code CLAUDE.md 和 Reasonix REASONIX.md 的项目常驻指令。

## 项目概述

Anima — Java 21 + LangChain4j 实现的 DeepSeek 原生终端 AI 编码智能体，
对标 Reasonix（Go）和 Claude Code。当前版本 **v0.17.0**。
双模式运行：终端模式（默认，`anima.bat`）或 Web 模式（`--web` → http://localhost:8080）。

## 编码约定

- 所有代码 UTF-8。package 声明必须与 `src/main/java` 下的目录结构严格匹配。
- 使用 Java 21 语法（record、switch 表达式、文本块）。匹配已有文件的注释密度和风格。
- Agent Loop 完全自研，不依赖 LangChain4j 的 AiServices — 只用其 Provider 层做 API 调用。
- 工具参数支持双别名（`path`/`file_path`/`file`、`command`/`cmd`），兼容不同 LLM 的命名习惯。
- 一个 `AgentLoop` 承载所有前端（终端 TUI、Web SSE、子 Agent）。新增行为加在 AgentLoop 而非前端，使所有入口自动继承。

## 架构约束

- **Cache-first**：System Prompt 前缀（基础角色 + 项目记忆 + 工具声明）必须在同一会话中保持字节稳定，
  确保 DeepSeek 自动前缀缓存持续命中。不要在会话中途变更 System Prompt — 走 mid-turn steer 通道注入。
- **Agent 循环**：REASON → 工具调用？→ EXECUTE → 循环。`maxSteps=0` 时不限轮次，靠 Token 预算制熔断。
- **权限控制**：三态 ALLOW / ASK / DENY + glob 规则 + 会话授权记忆。Plan Mode 是额外的只读粗粒度闸门，先于权限层检查。
- **压缩**：三级阈值（0.5 软警告 / 0.8 自动压缩 / 0.9 强制），对齐 Reasonix。压缩只折叠助手/工具工作，
  用户消息和错误永远保留原文。`tool_call` 与 `tool_result` 必须成对保留或成对折叠，否则 API 拒绝。
- **双模型**：Planner（Pro，只读工具，独立会话）+ Executor（Flash，全工具，独立会话）。两者会话永不混合，
  各自保持缓存稳定。

## 包依赖规则

依赖方向（无环）：`AnimaApp → terminal/agent → tool/llm`。

- `agent/` 不依赖 `terminal/`（AgentLoop 通过 `LoopCallback` 接口回调 UI）。
- `tool/` 不依赖 `agent/`（工具不知道 Agent 的存在）。
- `llm/` 不依赖任何业务包（纯 LLM 调用层）。
- 向 `agent/` 引入对 `tool/` 以外包的新依赖前，确认不会形成环。

## 提交前检查

**每次提交前执行**，覆盖最快的编译失败路径：

```bash
mvn compile                         # 编译检查（覆盖所有源文件）
mvn exec:java -Dexec.mainClass="com.anima.AnimaApp" -Dexec.args="--terminal" < /dev/null 2>&1 | head -5
```

## 记忆系统

- 分层文档：`ANIMA.md`（本文件，提交/共享）、`ANIMA.local.md`（个人，git-ignored）、
  用户全局 `~/.config/anima/ANIMA.md`、祖先目录中的 `AGENTS.md` / `CLAUDE.md` / `REASONIX.md`。
- `@path` 独立行导入其他文件内容。
- `remember` 工具保存持久事实到项目/全局记忆文件（frontmatter + `MEMORY.md` 索引），下次会话自动注入前缀。
- `forget` 工具归档到 `.archive/`，不物理删除。`memory` 工具提供 BM25 搜索已保存记忆。

## 构建与运行

```bash
mvn compile                                     # 编译
mvn exec:java -Dexec.mainClass="com.anima.AnimaApp"                   # Web 模式
mvn exec:java -Dexec.mainClass="com.anima.AnimaApp" -Dexec.args="--terminal"  # 终端模式
```

## 前置条件

- JDK 21+
- `DEEPSEEK_API_KEY` 环境变量，或首次运行时交互式输入（自动保存到 `~/.anima/config.properties`）
