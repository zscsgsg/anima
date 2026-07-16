# Anima 版本路线图

> Anima — DeepSeek 原生终端 AI 编码助手，Java 生态下的 Claude 级智能体。
> 以 [Reasonix](https://github.com/esengine/DeepSeek-Reasonix)（Go 实现）为演进参照。

---

## v0.9.0 — 基础可用 ✅

**目标：跑通 Agent 循环，最小可运行产品。**

| 能力          | 说明                                                                                                                           |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| Agent 循环    | REASON → TOOL_CALLS → EXECUTE → 循环                                                                                           |
| 上下文压缩    | DeepSeek 缓存优化的三级自动压缩                                                                                                |
| 三态权限      | ALLOW / ASK / DENY + glob 规则 + 会话授权记忆                                                                                  |
| 12 个内置工具 | read_file / write_file / edit_file / multi_edit / move_file / ls / glob / grep / bash / todo_write / complete_step / web_fetch |
| 流式 LLM      | DeepSeek V4 Flash + Thinking/Content 分离                                                                                      |
| 终端 UI       | JLine3 REPL + 交互式权限确认（y/n/a）                                                                                          |
| 项目记忆      | ANIMA.md + ANIMA.local.md + @path 导入                                                                                         |
| Web 模式      | HTTP/SSE 流式端点                                                                                                              |

---

## v0.10.0 — Loop Engineering ✅

**目标：Agent 循环从"能用"提升为"工程级健壮"。**

| 优先级 | 功能               | 说明                                                          |
| ------ | ------------------ | ------------------------------------------------------------- |
| 高     | Evidence 证据系统  | per-turn 工具调用证据账本，为 complete_step 提供验证基础      |
| 高     | complete_step 工具 | 证据驱动步骤签收，与 todo_write 形成"计划→执行→证据→签收"闭环 |
| 高     | Plan Mode          | 只读规划模式，禁止写工具 + 限制 bash 为安全命令白名单         |
| 中     | Grace Round        | maxSteps 到达时不立即终止，给模型 1 轮机会给出最终答案        |

---

## v0.11.0 — 会话持久化与 Slash 命令 ✅

**目标：退出不丢上下文，命令式交互体验对齐 Claude Code。**

| 优先级 | 功能               | 说明                                                                       |
| ------ | ------------------ | -------------------------------------------------------------------------- |
| 高     | Session 持久化     | JSONL 格式保存/恢复完整对话历史，启动时自动加载最近会话                    |
| 高     | Slash 命令系统     | `/help` `/model` `/plan` `/clear` `/sessions` `/memory` `/compact` `/exit` |
| 中     | 修复孤儿 tool_call | 损坏会话中自动剥离无结果对应的 tool_call，防止 API 拒绝请求                |

---

## v0.12.0 — 多模型 Provider + 代码索引 ✅

**目标：运行时热切换模型，模型能理解代码结构而不依赖 grep。**

| 优先级 | 功能                | 说明                                                            |
| ------ | ------------------- | --------------------------------------------------------------- |
| 高     | LLMProvider 接口    | 统一抽象，DeepSeek/OpenAI 均可实现                              |
| 高     | ProviderManager     | 多模型注册 + 热切换管理                                         |
| 高     | `/model` 运行时切换 | 下一轮对话立即使用新模型，无需重启                              |
| 高     | code_index 工具     | 14 种语言符号索引（outline/search），对标 Reasonix codeindex.go |
| 中     | 配置驱动注册        | anima.properties 中定义多个 `model.*` 别名                      |
| 中     | maxSteps=0 不限轮   | 对齐 Reasonix 默认值，模型自主决定何时停止                      |

---

## v0.13.0 — LSP 集成 ✅

**目标：语言服务器协议接入，14 种语言代码智能跳转与诊断。Anima 独有增强（Reasonix 无此能力）。**

| 优先级 | 功能                | 说明                                                          |
| ------ | ------------------- | ------------------------------------------------------------- |
| 高     | LSP JSON-RPC 传输   | Content-Length 帧协议 + read-pump demux                       |
| 高     | LspClient 进程管理  | 子进程启动/初始化/didOpen/didChange/close                     |
| 高     | LspManager 多语言池 | 14 种默认 ServerSpec + 懒启动 + 并发启动门                    |
| 高     | 4 个 LSP 工具       | lsp_definition / lsp_references / lsp_hover / lsp_diagnostics |
| 高     | code_index 降级     | LSP 不可用时自动降级为 code_index                             |

---

## v0.14.0 — 工程级健壮性增强 ✅

**目标：Subagents + Hooks + Steer + Storm Breaker，对标 Reasonix 的安全与可靠性。**

| 优先级 | 功能             | 说明                                                                                       |
| ------ | ---------------- | ------------------------------------------------------------------------------------------ |
| 高     | Subagents (task) | 子智能体独立 session，过滤工具注册表，仅返回最终答案                                       |
| 高     | Hooks 系统       | PreToolUse/PostToolUse/UserPromptSubmit/Stop 四个生命周期钩子，`.anima/settings.json` 配置 |
| 高     | Mid-turn Steer   | Agent 运行中用户用 `!<msg>` 注入指导，不破坏 prefix cache                                  |
| 高     | Storm Breaker    | 连续 3 次相同失败 → 自动注入策略变更提示                                                   |
| 高     | Repeat Guard     | 同一写入工具成功 2 次以上 → 阻止第 3 次（对标 Reasonix）                                   |
| 中     | Tool Output Cap  | 32KB 上限，防止单次工具结果撑爆上下文                                                      |

---

## v0.15.0 — Skills + MCP + 交互增强 ✅

**目标：Skills 系统 + MCP 插件生态 + Ask/Remember/Forget/OutputStyle。**

| 优先级 | 功能                   | 说明                                                                  |
| ------ | ---------------------- | --------------------------------------------------------------------- |
| 高     | Skills 系统            | SKILL.md 标准，Inline + Subagent 双模式，多生态目录兼容               |
| 高     | MCP 插件系统           | .mcp.json 配置 + stdio JSON-RPC + HTTP/SSE 传输，懒加载 + Schema 缓存 |
| 高     | Ask 工具               | 结构化多选题交互式提问                                                |
| 高     | Remember / Forget 工具 | 跨会话持久记忆保存与删除                                              |
| 中     | Output Style           | 4 种输出风格切换（/style 命令）                                       |
| 中     | install_source 工具    | 从 URL/GitHub/npm 安装技能或 MCP 服务                                 |

---

## v0.16.0 — Reasonix 能力对齐 ✅

**目标：补齐 Checkpoint/Rewind + GoalMachine + Coordinator + ParallelTasks + BM25 五大能力，覆盖率从 ~35% 提升至 ~70%。**

| 优先级 | 功能                | 核心文件                                                      |
| ------ | ------------------- | ------------------------------------------------------------- |
| 高     | Checkpoint / Rewind | CheckpointStore.java — 快照式文件回退 + `/rewind` 命令        |
| 高     | GoalMachine         | GoalMachine.java — 长时间自主任务状态机 + `/goal` 命令        |
| 高     | Coordinator         | Coordinator.java — 双模型 Planner(Pro) + Executor(Flash) 分离 |
| 高     | ParallelTasks       | ParallelTasksTool.java — DAG 依赖任务并行调度                 |
| 高     | HistoryTool         | HistoryTool.java + Bm25Index.java — BM25 跨会话历史检索       |
| 中     | 主干集成            | AgentLoop/TerminalUI/SlashDispatcher/AnimaConfig 同步修改     |

---

## v0.17.0 — 记忆升级 + 工具补全 ✅（当前）

**目标：记忆系统收敛 + 补齐 Reasonix 独有工具。**

| 优先级 | 功能                 | 说明                                                                          |
| ------ | -------------------- | ----------------------------------------------------------------------------- |
| 高     | memory 搜索工具      | BM25 搜索/读取/列出已保存记忆                                                 |
| 高     | GlobalDir 跨项目共享 | user/feedback 记忆存到 `~/.anima/memory/global/`                              |
| 高     | Archive 归档         | forget 时按时间戳归档到 `.archive/`，不物理删除                               |
| 高     | 祖先链发现           | AGENTS.md / CLAUDE.md / REASONIX.md 兼容 + `@import` 递归 + `~` 展开          |
| 高     | 3 个新工具           | delete_range（按行号删除）、workspace（工作区信息）、preview（URL/HTML 预览） |
| 中     | 用户全局记忆         | `~/.config/anima/ANIMA.md`                                                    |
| 中     | 记忆索引保护         | MEMORY.md 超过 30 行自动提示清理                                              |

---

## 规划中（v0.18+）

| 功能             | 说明                                                            |
| ---------------- | --------------------------------------------------------------- |
| 沙盒系统         | 文件写入根限制 + bash 命令沙箱（对标 Reasonix sandbox Phase 1） |
| 后台任务         | bash run_in_background + bash_output + kill_shell + wait        |
| Notebook 编辑    | Jupyter 单元格级别编辑（对标 Reasonix notebookedit）            |
| Confine 工具     | 沙箱限制工具                                                    |
| 全量工具输出保护 | 对标 Reasonix 的 read_file 结果永不裁剪机制                     |

---

## 对标基准

Anima 以 [Reasonix](https://github.com/esengine/DeepSeek-Reasonix)（Go 实现）为演进参照，
目标是在 Java 生态下实现同等的 Claude 级终端智能体能力边界。

当前对齐度：**~70%**。核心 Agent 循环、压缩、权限、SubAgent、Checkpoint、Goal、Coordinator、Skills、MCP 均已对齐。
独有增强：**LSP 集成**（14 种语言）Reasonix 无此能力。
