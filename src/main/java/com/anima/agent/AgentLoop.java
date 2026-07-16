package com.anima.agent;

import com.anima.llm.LLMProvider;
import com.anima.tool.CompleteStepTool;
import com.anima.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Core agent state machine: REASON → (tool calls?) EXECUTE → REASON → ... → DONE.
 * Includes DeepSeek-cache-optimized context compaction (Step 7).
 *
 * Compaction strategy (mirrors Reasonix):
 * - Stable prefix (system + first user + prior summaries) stays cache-warm
 * - ~16K token tail kept verbatim
 * - Small user turns never folded (user facts are sacred)
 * - Tool call+result pairs kept together
 * - Mechanical fallback when summarizer fails
 */
public class AgentLoop {

    // ── Compaction constants (DeepSeek V4: 1M context window) ──
    //定义了上下文窗口大小 ，为了避免上下文溢出  这个是DeepSeek V4的上下文窗口大小  1M
    private static final int CONTEXT_WINDOW = 1_000_000;
    //定义了压缩的比例 ，当上下文大小超过这个比例时，就会触发压缩  这个是压缩的比例  0.8  即80%
    private static final double COMPACT_RATIO = 0.8;   // trigger compaction
    //强制触发线  这个是强制触发线  0.9  即90%  当上下文大小超过这个比例时，就会触发压缩
    private static final double FORCE_RATIO = 0.9;       // force even for low-value folds
    //最近的对话（大约 16000 token）不能压缩——因为这是最新发生的事情，助理需要记住细节。
    private static final int TAIL_TOKENS = 16384;        // recent context kept verbatim
    //这个是字符估算器  用于估算上下文大小 这个是估算上下文大小的公式  0.25  即4个字符一个token
    private static final double TOK_PER_CHAR_FALLBACK = 0.25; // ~4 chars/token
    //第一条用户消息（通常包含系统指令、角色设定或长文档）最多保留 1500 Tokens 不压缩。因为首条往往定义了整个任务的基调，必须保留原貌
    private static final int MAX_PINNED_FIRST_USER = 1500;    // max tokens to pin first user turn
    //如果要压缩的部分还不到 400 token，就别浪费钱调 LLM 了，省不了多少。
    private static final int MIN_FOLD_TOKENS = 400;           // min savings to justify summarizer call
    //压缩完成后，生成的摘要会被包裹在 <compaction-summary> 标签内，以便后续的上下文合并
    private static final String SUMMARY_TAG = "<compaction-summary>";
    private static final String SUMMARY_CLOSE = "</compaction-summary>";
    //告诉模型，我们希望它生成的摘要是简洁的，只包含必要的关键信息，而不是冗长的解释。
    private static final String SUMMARY_SYSTEM = """
        You are compacting the earlier part of a coding agent's conversation to save context.
        The agent keeps your summary alongside the user's own turns (kept verbatim) and the recent tail;
        your job is to fold the assistant/tool work into a briefing it can resume from.
        Write under these exact headings, omitting a heading only if it has no content:

        ## Standing facts & constraints
        Everything the user stated that still governs the work — names, paths, IDs, versions, tokens,
        preferences, and hard "never do X" rules — in their own words. Be exhaustive.

        ## Goal
        The user's request and intent.

        ## Decisions & rationale
        Key choices made so far and why — so they are not re-litigated or reversed.

        ## Files & code
        Files read or modified, with the specific facts that matter: signatures, line locations, data
        shapes, and exact edits applied.

        ## Commands & outcomes
        Commands run (builds, tests, git) and their relevant results — what passed, what failed.

        ## Errors & fixes
        Problems hit and how they were resolved (or not), so the same dead ends are not repeated.

        ## Pending & next step
        What is still in progress or unstarted, and the single most concrete next action to take.

        Rules: be terse — bullet points and fragments, not prose. Preserve identifiers, paths, and
        numbers exactly. Do NOT invent anything not present in the messages.""";

    private LLMProvider provider;
    private final ToolRegistry tools;
    private final int maxSteps;
    private final List<ChatMessage> history;
    private final String systemPrompt;
    //权限管理  这个是权限管理  用于控制工具的调用权限  可以是ask、allow、deny
    private final PermissionGate permissionGate;

    // ── Loop Engineering (v0.10) ──
    /** Per-turn evidence ledger — records every tool call for complete_step verification. */
    // 每轮的"工具调用收据本"，验证工具调用的证据  这个是验证工具调用的证据  用于验证工具调用的正确性 主要用于配合 complete_step 工具进行自我校验（验证 AI 是否真的做了它说要做的事）
    // complete_step 工具执行时会去查这个账本——"你说你完成了？拿证据来！"没有真实工具调用记录的签收会被拒绝。这就是 "计划→执行→证据→签收"闭环。
    private final Evidence evidence = new Evidence();

    /** Plan mode: when true, only read-only tools are allowed. */
    //planMode 打开时，助理只能读文件，不能写文件。就像"先侦察，不开枪"。
    private boolean planMode = false;

    /** Tools unconditionally denied in plan mode. */
    //规划模式下，这三个"危险工具"绝对不让用，防止AI写文件，写注册表，写系统文件等操作。
    private static final java.util.Set<String> PLAN_MODE_DENIED_TOOLS = java.util.Set.of(
        "write_file", "edit_file", "multi_edit"
    );

    /** Bash commands safe to run in plan mode (read-only operations). */
    //这个是安全的bash命令  用于在规划模式下执行bash命令  防止AI执行写文件，写注册表，写系统文件等操作
    private static final java.util.List<String> PLAN_MODE_SAFE_BASH = java.util.List.of(
        "git status", "git diff", "git log", "git show",
        "git ls-files", "git grep", "git blame",
        "ls", "dir", "cat", "type", "grep", "find", "head", "tail", "pwd",
        "echo", "wc", "which", "where", "uname", "hostname",
        "go version", "go list", "go doc", "go vet",
        "node -v", "npm list", "python --version",
        "mvn --version", "java --version", "javac --version"
    );

    /** Shell metacharacters that indicate chaining/redirection — blocked in plan mode. */
    //这个是bash元字符  防止出现ls /tmp && rm -rf / 这样的命令  防止AI执行写文件，写注册表，写系统文件等操作
    private static final String[] PLAN_MODE_BASH_META = {
        "&&", "||", ">>", "<<", "$(", "`", ";", "|", ">", "<", "&", "\n", "\r"
    };

    // ── Compaction state ──
    // 上次请求用了多少 token
    private int lastPromptTokens = 0;
    //连续压缩了几次
    private int consecutiveCompacts = 0;
    //一旦设为 true，自动压缩就暂停了——再压也是浪费钱。只有用户手动 /compact 才能重置这个状态。好比马桶堵了，再冲水只会溢出来，得停一停。
    private boolean compactStuck = false;
    //每个字符大约等于多少 token（会自我校准）
    private double calibratedTokPerChar = TOK_PER_CHAR_FALLBACK;
    //上一次压缩的摘要内容"。不为空就说明聊天记录已经被压缩过了。主要用于两个场景：① 判断当前是否处于已压缩状态；② 恢复会话时扫描历史里的摘要。
    private String lastCompactionSummary = null;

    // ── v0.14: Mid-turn Steer ──
    /** Messages queued by user during agent run; consumed one per loop iteration. */
    //助理正干活呢，你突然说"等等，别改那个文件！"。这句话就会放进 steerQueue，助理本轮结束后会读到你的新指令。
    private final List<String> steerQueue = new ArrayList<>();
    //这个是一个标志位  用于标记是否已经消费了 steerQueue 中的消息  防止重复消费
    private boolean steerConsumed = false;

    /** Prefix marking a mid-turn steer message (model sees as guidance, not new task). */
    //当助理正在干活时，你中途插了一句话（比如"别改那个文件，换个方法！"），这句话前面会贴上这个标签，然后塞进聊天记录。标签的作用是告诉 LLM：这不是新任务，只是对当前工作的指导！
    private static final String STEER_PREFIX =
        "[Mid-turn steer from user — do NOT treat as new task; use as guidance for current work]";

    // ── v0.14: Storm Breaker + Repeat Guard ──
    /** Signature of consecutive tool failures — detects death spirals. */
    //"失败签名"——记录上一次失败的工具名+错误信息（去掉数字等变量后的指纹）
    private String stormSig = null;
    //连续失败的次数
    private int stormCount = 0;
    //风暴阈值  连续失败的次数超过这个阈值时，就会触发风暴保护器 强制 AI 换思路 防止AI陷入死循环
    private static final int STORM_THRESHOLD = 3;

    /** Counts per-tool successes this turn — catches repeated successful writes. */
    //同一件事做成功了几次"的统计表。比如 write_file 给同一个文件写了 2 次同样的内容，就会触发拦截——"你已经改过了，重复改没意义"
    private Map<String, Integer> repeatSuccessCounts = null;
    /** Only track repeat successes for write tools (Reasonix parity). Read-only tools
     *  like grep/read_file are expected to be called repeatedly in sub-agents. */
    //最多允许做成功 2 次"。同一写工具同样参数成功 ≥2 次就直接拦住
    private static final int REPEAT_WARN_THRESHOLD = 2;  // matching Reasonix

    // ── v0.14: ToolHooks (lifecycle hooks around tool execution) ──
    //工具生命周期钩子  用于在工具调用前后执行一些操作  比如记录工具调用次数  记录工具调用时间等操作
    private ToolHooks toolHooks = null;

    /** Max bytes per tool result before truncation (≈8K tokens, ~32KB). */
    //工具输出上限 32KB 防止工具返回太大结果撑爆上下文
    private static final int MAX_TOOL_OUTPUT_BYTES = 32 * 1024;

    // ── v0.14: Subagent support ──
    /** Parent call ID + sink for nested sub-agent event forwarding. */
    /**
     * 父级工具调用 ID。仅当本实例作为子代理（Sub-agent）运行时使用。
     * 用于将子代理的最终结果，精准回填到父代理历史记录中对应的那个工具调用（ToolCall）里。
     */
    private String parentCallId = null;
    /**
     * 父级事件回调。仅当本实例作为子代理（Sub-agent）运行时使用。
     * 用于把子代理内部的实时进度（思考/工具执行/错误），向上冒泡转发给父代理的 UI 监听器。
     */
    private LoopCallback parentCallback = null;

    // ── v0.16: Checkpoint/Rewind ──
    /** Snapshot store for edit safety net. Null when disabled. */
    //"时光机存档库"。每次 AI 要改文件之前，先把文件当前内容拍个快照存起来。如果 AI 改坏了，用户可以 /rewind 回到之前的版本。
    private CheckpointStore checkpointStore = null;
    /** Current turn number (incremented at each user turn). */
    //当前是第几轮对话"。每次用户发新消息，轮数 +1。用于给快照打时间戳——"第 3 轮时的文件长这样"。
    private int currentTurn = 0;
    /** Goal machine for long-running autonomous pursuit. Null when disabled. */
    //"自动驾驶巡航系统"。设置一个长期目标后，Agent 会自动跨多轮推进，不需要用户每轮都说"继续"。
    private GoalMachine goalMachine = null;
    //这个是简易版的 AgentLoop  用于测试用的
    public AgentLoop(LLMProvider provider, ToolRegistry tools, int maxSteps, String systemPrompt) {
        this(provider, tools, maxSteps, systemPrompt, null);
    }
    //这个是完整版的 AgentLoop  用于正式用的
    public AgentLoop(LLMProvider provider, ToolRegistry tools, int maxSteps, String systemPrompt, PermissionGate permissionGate) {
        this.provider = provider;
        this.tools = tools;
        this.maxSteps = maxSteps;
        this.systemPrompt = systemPrompt;
        this.permissionGate = permissionGate;
        // 新建一个空的聊天记录本。因为是全新会话，没有历史消息。
        this.history = new ArrayList<>();
        //在聊天记录本的第一页，写上 system prompt（工作说明书）
        this.history.add(SystemMessage.from(systemPrompt));
    }

    /**
     * Construct with pre-existing history (for session resume).
     * If history is null or empty, starts fresh with system prompt.
     * If history already has a system message first, uses it as-is.
     */
    //用于从已有的历史记录中恢复会话
    //如果历史记录为空，就从 system prompt 开始
    //如果历史记录已经包含 system prompt，就直接使用它
    //用户从磁盘恢复了之前的会话，把旧聊天记录（existingHistory）传进来，接着上次聊。这是整个会话持久化功能的唯一入口——没有它，每次启动都是全新会话。
    public AgentLoop(LLMProvider provider, ToolRegistry tools, int maxSteps,
                      String systemPrompt, PermissionGate permissionGate,
                      List<ChatMessage> existingHistory) {
        // 先用完整版的 AgentLoop 构造函数，初始化聊天记录本
        this(provider, tools, maxSteps, systemPrompt, permissionGate);
        // 如果有旧聊天记录，就用它替换聊天记录本
        if (existingHistory != null && !existingHistory.isEmpty()) {
            // 清空聊天记录本
            history.clear();
            // 把旧聊天记录（existingHistory）添加到聊天记录本
            // 这样，用户就可以接着上次聊了
            history.addAll(existingHistory);
            // Ensure system message is present and current
            // 笔记本第一页是不是工作说明书？ 可能出现两种情况：①旧记录是空的（极端情况）；②旧记录第一页不是 system prompt（比如旧版本保存的格式不标准）
            if (history.isEmpty() || !(history.get(0) instanceof SystemMessage)) {
                //如果旧记录是空的，或者第一页不是 system prompt，就写上 system prompt（工作说明书）
                history.add(0, SystemMessage.from(systemPrompt));
            } else {
                // Replace stale system message with current one
                //用当前最新的说明书替换掉旧的说明书。为什么要替换？因为 Anima 可能更新了版本，system prompt 可能改了，旧的不适用了。这叫"热更新"
                history.set(0, SystemMessage.from(systemPrompt));
            }
            // v0.14.1: Repair orphaned tool_calls from corrupted sessions
            // 扫描聊天记录，拔掉"有钥匙没锁"的孤儿工具调用 防止 API 因为配对不完整而拒绝请求
            /**
             * 修复前：🔑A → 🔑B → 🔒B → 🔑C
             *          ↑              ↑
             *        没有锁！       没有锁！
             * 修复后：🔑B → 🔒B
             *         （孤儿🔑A和🔑C被拔掉了）
             */
            repairLoadedHistory();
            //从聊天记录里找之前的"压缩摘要"书签 让 Anima 知道自己处于"已压缩"状态，而不是从头再来
            this.lastCompactionSummary = detectCompactionSummary(history);
        }
    }

    /**
     * Scan loaded history and strip tool_calls from any AI message whose
     * tool results are missing. Prevents API errors from corrupted sessions.
     */
    // 扫描已加载的历史记录，拔掉所有"有钥匙没锁"的孤儿工具调用 防止 API 因为配对不完整而拒绝请求
    private void repairLoadedHistory() {
        // Collect all tool result IDs present in the history
        //先拿出一个空篮子，准备装所有"锁的编号"
        Set<String> resultIds = new HashSet<>();
        //遍历聊天记录，把所有"锁的编号" 放到篮子里
        for (ChatMessage m : history) {
            if (m instanceof ToolExecutionResultMessage tr && tr.id() != null) {
                resultIds.add(tr.id());
            }
        }
        // Strip orphaned tool_calls from AI messages
        //遍历聊天记录，把所有"有钥匙没锁"的孤儿工具调用移除，防止 API出错 因为配对不完整而拒绝请求
        for (int i = 0; i < history.size(); i++) {
            // 找到 AI 说的话里带了"我要调工具"的那种
            if (history.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                // 先假设这把钥匙是好的（能找到对应的锁）
                boolean anyOrphaned = false;
                //检查 AI 要调的每个工具的编号，在不在刚才那个篮子里
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    //如果 AI 要调的工具的编号不在刚才那个篮子里，就说明是孤儿工具调用
                    if (!resultIds.contains(req.id())) {
                        // 标记这把钥匙是坏的（找不到对应的锁）
                        anyOrphaned = true;
                        break;
                    }
                }
                //如果这把钥匙是坏的（找不到对应的锁），就说明是孤儿工具调用
                if (anyOrphaned) {
                    // Replace with text-only version (keep any text content)
                    //看看 AI 除了说要调工具，还说了别的话没有（比如"让我看看这个文件..."）
                    String text = ai.text();
                    //如果还有别的话，就只保留说话内容，删掉工具调用
                    if (text != null && !text.isBlank()) {
                        history.set(i, AiMessage.from(text));
                    } else {
                        //如果 AI 除了调工具啥也没说，整条删掉
                        history.remove(i);
                        //i-- 是因为删掉一条后后面的会往前挪，计数器要退一步，不然会漏掉下一条
                        i--; // adjust index after removal
                    }
                }
            }
        }
    }

    // ── Loop Engineering public API ──

    /** Enable/disable plan mode (read-only planning gate). */
    public void setPlanMode(boolean planMode) { this.planMode = planMode; }
    public boolean isPlanMode() { return planMode; }

    /** Get the per-turn evidence ledger (for complete_step to read). */
    public Evidence getEvidence() { return evidence; }

    /** Get the active LLM provider. */
    public LLMProvider getProvider() { return provider; }

    /** Hot-swap the LLM provider for the next turn. */
    public void setProvider(LLMProvider newProvider) { this.provider = newProvider; }

    /** Get the tool registry. */
    public ToolRegistry tools() { return tools; }

    /** Get the current conversation history (for session save). */
    public List<ChatMessage> getHistory() { return new ArrayList<>(history); }

    /** Replace the entire history (for session resume or new session).
     *  Pass null to reset to fresh (system prompt only). */
    // 替换整个对话历史记录（用于会话恢复或新会话）这是双模型下会话恢复的关键方法。把 Agent 当前的聊天记录全部换掉——就像给一个人换了个记忆芯片。
    //newHistory 就是你上次聊天的完整记录——你说的每句话、AI 的每个回复、每个工具调用和结果，全部原封不动从磁盘读出来，然后整本抄进执行者的脑子里。
    public void replaceHistory(List<ChatMessage> newHistory) {
        // 如果新历史记录为空，就只保留系统提示
        if (newHistory == null || newHistory.isEmpty()) {
            // 清空所有聊天记录，只保留系统提示
            history.clear();
            // 把系统提示添加到历史记录里
            history.add(SystemMessage.from(systemPrompt));
            //标记"没压缩过"——因为就一页纸，不可能需要压缩
            lastCompactionSummary = null;
        } else {
            //还是先擦干净，再添加新历史记录
            history.clear();
            // 把旧记录整本抄进来
            history.addAll(newHistory);
            //安全检查：第一页是不是工作说明书？不是，就添加一个
            if (history.isEmpty() || !(history.get(0) instanceof SystemMessage)) {
                history.add(0, SystemMessage.from(systemPrompt));
            }
            //检测旧记录里有没有压缩过，如果有，就记录下压缩后的结果
            lastCompactionSummary = detectCompactionSummary(history);
        }
    }

    /** Clear conversation context, keeping only the system prompt. */
    //规划者每次做完计划后清空上下文，只保留系统提示
    public void clearContext() {
        history.clear();
        history.add(SystemMessage.from(systemPrompt));
        lastCompactionSummary = null;
    }

    // ── v0.14: Mid-turn Steer API ──

    /**
     * Queue a mid-turn steer message. The agent consumes one per loop iteration,
     * injecting it as guidance (not a new task). Safe to call from any thread.
     */
    // 套方法让你在 AI 干活时中途打断它，塞一句新指令。就像你让助理去写报告，写了一半你说"等等，格式用 A4 别用 Letter"。任务
    public void steer(String text) {
        // 加锁——因为可能你从别的线程喊话，跟 AI 的主线程同时操作，不加锁会乱
        synchronized (steerQueue) {
            steerQueue.add(text);
            //标记"还有话没被 AI 读呢"
            steerConsumed = false;
        }
    }

    /** Whether the steer queue is empty after the last consume. */
    //还有便签没读吗？
    public boolean steerConsumed() {
        synchronized (steerQueue) { return steerConsumed; }
    }

    private String consumeSteer() {
        //
        synchronized (steerQueue) {
            //// 没便签了，返回空
            if (steerQueue.isEmpty()) return null;
            // 拿走最上面那张便签
            String t = steerQueue.remove(0);
            //便签拿完了吗？
            steerConsumed = steerQueue.isEmpty();
            //返回便签
            return t;
        }
    }
    //清空所有便签
    private void clearSteerQueue() {
        synchronized (steerQueue) {
            steerQueue.clear();
            steerConsumed = false;
        }
    }

    // ── v0.14: ToolHooks setter ──

    /** Install lifecycle hooks. null disables hook firing. */
    // 安装工具钩子。 使用工具调用前、后触发的事件。
    public void setToolHooks(ToolHooks hooks) { this.toolHooks = hooks; }
    public ToolHooks getToolHooks() { return toolHooks; }

    // ── v0.14: Subagent context (for nested task tool) ──

    /** Set parent call context for nested sub-agent event forwarding. */
    // 设置父调用上下文，用于嵌套子代理事件转发。想一个你是外卖站长，来了个大订单要分给骑手去送
    public void setParentContext(String callId, LoopCallback callback) {
        this.parentCallId = callId;// 订单号
        this.parentCallback = callback;// 对讲机频道
    }//站长对骑手说——"这单编号是 #9527，你的对讲机调到 3 频道，有情况随时呼我！"

    /** Clear parent context (back to top-level agent). */
    // 骑手送完了，交回装备
    public void clearParentContext() {
        this.parentCallId = null;
        this.parentCallback = null;
    }

    /** Whether this agent is running as a sub-agent. */
    // 问：你是站长本人，还是派出去的骑手？ 如果是骑手（嵌套子代理），就返回 true
    public boolean isSubagent() { return parentCallId != null; }

    // ── v0.16: Checkpoint/Rewind API ──

    /** Install a checkpoint store for edit safety. */
    //给你配一个存档功能
    public void setCheckpointStore(CheckpointStore store) { this.checkpointStore = store; }
    public CheckpointStore getCheckpointStore() { return checkpointStore; }
    // 问：当前是第几轮？
    public int currentTurn() { return currentTurn; }

    /** Install a goal machine for autonomous pursuit. */
    // 安装目标机器，用于自主追求目标。
    public void setGoalMachine(GoalMachine gm) { this.goalMachine = gm; }
    // 问：当前的目标机器是哪个？
    public GoalMachine getGoalMachine() { return goalMachine; }

    /**
     * Run the agent loop. Returns the final text answer.
     * Events are pushed through the callback so the UI can stream.
     */
    public String run(String userInput, LoopCallback callback) {
        // 重置证据，开始新的轮次（设计的原因是如果不这样设计，上一轮的工具记录残留到下一轮，complete_step 检查时会误判 AI"确实干活了"，让 AI 蒙混过关。）
        evidence.reset();  // fresh ledger for this turn
        //把刚擦干净的作业本递给 CompleteStepTool。
        CompleteStepTool.setCurrentEvidence(evidence);  // wire for complete_step
        // 清空所有便签
        clearSteerQueue();
        //重置重复成功次数
        repeatSuccessCounts = null;
        //工具名+错误信息
        stormSig = null;
        //连续失败次数归零
        stormCount = 0;

        // ── v0.16: Begin checkpoint for this turn ──
        //①配了存档库 ②不是小弟。
        if (checkpointStore != null && !isSubagent()) {
            // 分配回合编号 + 记录"这一轮开始时用户说了啥、聊天记录有多少条"。
            currentTurn = checkpointStore.nextTurn();
            checkpointStore.beginTurn(currentTurn, userInput, history.size());
            //为什么记录 userInput 和 history.size()？回退（rewind）不只是把文件恢复到旧版本，还要把聊天记录也截断到那个时间点。
        }

        // Fire onUserPromptSubmit hook
        if (toolHooks != null) toolHooks.onUserPromptSubmit(userInput);

        try {
            return runInternal(userInput, callback);
        } finally {
            //这个设计的原因是不这样设计会怎样：API 调用出错后，下一次对话里 complete_step 工具可能访问到一个已经被回收的 evidence 对象（或者更糟——访问到别人残留的 evidence），导致不可预测的行为。
            CompleteStepTool.clearCurrentEvidence();
            clearSteerQueue();
        }
    }
    //userInput 是你跟 AI 说的话，callback 是 AI 干活时跟你汇报的喇叭。一个进、一个出，构成了对话的完整通道。
    private String runInternal(String userInput, LoopCallback callback) {
        // 把用户输入添加到聊天记录里
        history.add(UserMessage.from(userInput));
        // 告诉终端界面——"嘿，用户说了这句话，你赶紧在屏幕上显示出来"。callback 就是一个传声筒，Agent 在后台干活，但要实时告诉终端发生了什么
        callback.onUserMessage(userInput);
        /** 宽限轮标记
         * 什么是宽限轮？比如你设置最多让 AI 调 20 次工具，到了第 20 次还没做完，AI 会说"我还没干完"。这时系统给它额外一轮——"行，
         * 我破例让你再跑一轮，但这一轮不准再调工具了，把你已经干完的活总结一下交上来"
         *为什么这样设计：用户体验。如果 step 一到上限就直接中断，用户看到的就是"到达步数上限"——啥结果都没有。
         * 宽限轮让 AI 至少能把干了半截的活总结一下，告诉用户"我干到哪了，剩下啥还没干"。
         * graceRound = true大白话：你已经到了步数上限，但我额外赏你最后一轮。这一轮的特殊规则是：不准再调工具了，把你已经干完的活总结一下交上来。
         */
        boolean graceRound = false;
        for (int step = 0; maxSteps <= 0 || step < maxSteps || graceRound; step++) {
            // ── v0.14: Consume queued steer ──
            // 从队列里取一个便签
            String steerText = consumeSteer();
            // 如果有便签，就添加到聊天记录里
            if (steerText != null) {
                history.add(UserMessage.from(STEER_PREFIX + "\n" + steerText));
                // 通知 UI 显示便签
                callback.onSteer(steerText);
            }

            // Build tool schemas
            // 把工具箱里的每样工具（读文件、写文件、执行命令等）的名字、参数格式整理好，发给 LLM。如果工具箱是空的，就发空列表。
            var toolSchemas = tools.list().isEmpty() ? List.<ToolSpecification>of() : tools.schemas();

            // Stream LLM call
            //调 LLM——异步等结果
            var result = new Object() {
                String text = "";
                List<LLMProvider.ToolCall> calls = List.of();
                LLMProvider.Usage usage = null;
                Throwable error = null;
            };
            // 等待 LLM 回复
            var latch = new java.util.concurrent.CountDownLatch(1);
            // 调用 LLM 并等待回复
            provider.stream(new ArrayList<>(history), toolSchemas, new LLMProvider.StreamListener() {
                @Override public void onThinking(String t) { callback.onThinking(t); }
                @Override public void onResponse(String t) { callback.onResponse(t); }
                @Override public void onComplete(String text, List<LLMProvider.ToolCall> calls, LLMProvider.Usage usage) {
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
                //判断是否需要压缩聊天记录
                maybeCompact(result.usage, callback);
            }

            // Add assistant message to history
            String assistantText = result.text != null ? result.text : "";
            // 如果有工具调用，就添加到聊天记录里
            if (!result.calls.isEmpty()) {
                // 构建助手消息
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

                // Execute tools (with plan mode + permission check + evidence recording + v0.14 hooks/storm)
                for (var call : result.calls) {
                    boolean readOnly = tools.isReadOnly(call.name());
                    boolean blocked = false;
                    String toolResult;

                    // ── Gate 0: ToolHooks preToolUse (v0.14) ──
                    if (!blocked && toolHooks != null) {
                        String hookBlock = toolHooks.preToolUse(call.name(), call.arguments());
                        if (hookBlock != null) {
                            toolResult = "blocked: " + hookBlock;
                            blocked = true;
                            callback.onToolPermissionDenied(call.name(), call.arguments());
                            history.add(ToolExecutionResultMessage.from(
                                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                    .id(call.id()).name(call.name()).arguments(call.arguments()).build(),
                                toolResult
                            ));
                            evidence.record(call.name(), call.arguments(), false, readOnly);
                            continue;
                        }
                    }

                    // ── Gate 0.5: Repeat Guard (v0.14.1) — block before executing if
                    //     this write tool has already succeeded 2+ times with same args.
                    //     Mirrors Reasonix's repeatedSuccessBlock at top of executeOne(). ──
                    if (!blocked && !readOnly && repeatSuccessCounts != null) {
                        String repeatSig = repeatSignature(call.name(), call.arguments());
                        if (repeatSig != null) {
                            int count = repeatSuccessCounts.getOrDefault(repeatSig, 0);
                            if (count >= REPEAT_WARN_THRESHOLD) {
                                toolResult = "blocked: [loop guard] \"" + call.name() +
                                    "\" has already succeeded " + count +
                                    " times with the same write-like arguments in this user turn. " +
                                    "Re-running it is unlikely to help. Change approach or produce a final answer.";
                                blocked = true;
                                callback.onToolPermissionDenied(call.name(), call.arguments());
                                history.add(ToolExecutionResultMessage.from(
                                    dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                        .id(call.id()).name(call.name()).arguments(call.arguments()).build(),
                                    toolResult
                                ));
                                evidence.record(call.name(), call.arguments(), false, readOnly);
                                continue;
                            }
                        }
                    }

                    // ── Gate 1: Plan Mode (read-only planning) ──
                    if (planMode && !readOnly) {
                        String planBlocked = planModeBlockReason(call.name(), call.arguments());
                        if (planBlocked != null) {
                            toolResult = "blocked: " + planBlocked;
                            blocked = true;
                            callback.onToolPermissionDenied(call.name(), call.arguments());
                            history.add(ToolExecutionResultMessage.from(
                                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                    .id(call.id()).name(call.name()).arguments(call.arguments()).build(),
                                toolResult
                            ));
                            evidence.record(call.name(), call.arguments(), false, readOnly);
                            continue;
                        }
                    }

                    // ── Gate 2: Permission check (three-state: ALLOW / ASK / DENY) ──
                    if (!blocked && permissionGate != null) {
                        PermissionDecision decision = permissionGate.check(
                            call.name(), call.arguments(), readOnly);
                        if (decision == PermissionDecision.DENY) {
                            toolResult = "blocked: denied by permission policy";
                            blocked = true;
                            callback.onToolPermissionDenied(call.name(), call.arguments());
                            history.add(ToolExecutionResultMessage.from(
                                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                    .id(call.id()).name(call.name()).arguments(call.arguments()).build(),
                                toolResult
                            ));
                            evidence.record(call.name(), call.arguments(), false, readOnly);
                            continue;
                        }
                    }

                    // ── v0.16: Snapshot file before write/edit/move/multi_edit ──
                    if (!blocked && !readOnly && checkpointStore != null && !isSubagent()) {
                        snapshotFileIfNeeded(call.name(), call.arguments());
                    }

                    // ── Execute tool ──
                    callback.onToolStart(call.name(), call.arguments());
                    boolean success = true;
                    try {
                        var tool = tools.get(call.name());
                        if (tool == null) {
                            toolResult = "Error: unknown tool '" + call.name() + "'";
                            success = false;
                        } else {
                            toolResult = tool.execute(call.arguments());
                            // Check if result indicates error
                            if (toolResult != null && (toolResult.startsWith("Error:") || toolResult.startsWith("blocked:"))) {
                                success = false;
                            }
                        }
                    } catch (Exception e) {
                        toolResult = "Error executing tool: " + e.getMessage();
                        success = false;
                    }

                    // ── v0.14: Cap tool output to prevent context blowout ──
                    if (toolResult != null) {
                        byte[] bytes = toolResult.getBytes(StandardCharsets.UTF_8);
                        if (bytes.length > MAX_TOOL_OUTPUT_BYTES) {
                            toolResult = new String(bytes, 0, MAX_TOOL_OUTPUT_BYTES, StandardCharsets.UTF_8)
                                + "\n... (truncated " + (bytes.length - MAX_TOOL_OUTPUT_BYTES) + " bytes)";
                        }
                    }

                    // ── v0.14: PostToolUse hook ──
                    if (toolHooks != null && !blocked) {
                        toolHooks.postToolUse(call.name(), call.arguments(), toolResult);
                    }

                    // ── v0.14.1: Storm Breaker — append warning to tool result (Reasonix parity) ──
                    if (!success && toolResult != null) {
                        toolResult = appendStormWarning(call.name(), toolResult);
                    } else {
                        // Reset storm on any success
                        stormSig = null;
                        stormCount = 0;
                        // Track repeat successes for repeat guard (Gate 0.5)
                        recordRepeatSuccess(call.name(), call.arguments(), readOnly);
                    }

                    evidence.record(call.name(), call.arguments(), success, readOnly);
                    callback.onToolResult(call.name(), toolResult);
                    history.add(ToolExecutionResultMessage.from(
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                            .id(call.id()).name(call.name()).arguments(call.arguments()).build(),
                        toolResult
                    ));
                }
                // Grace Round: if we just gave the model one extra round and it STILL
                // wants tools, stop here — work so far is saved in session.
                if (graceRound) {
                    String msg = "Paused after " + maxSteps + " tool-call rounds. " +
                        "Work so far is saved; send another message to continue.";
                    callback.onError(new RuntimeException(msg));
                    return msg;
                }
                // When maxSteps is reached but this is the last round, give one grace
                // round to let the model produce a final answer from completed work.
                if (maxSteps > 0 && step + 1 >= maxSteps && !graceRound) {
                    graceRound = true;
                    String nudge = "Do not call any more tools — your tool-call round limit (" +
                        maxSteps + ") has been reached. Synthesize a final answer from all " +
                        "the work already completed: summarize what was accomplished, " +
                        "what remains to be done, and any decisions the user should make.";
                    history.add(UserMessage.from(nudge));
                    callback.onError(new RuntimeException(
                        "Step budget exhausted — one grace round to finalize."));
                }
                // Continue loop — LLM sees tool results and may call more tools
                continue;
            }

            // No tool calls → final answer
            // 如果没有工具调用，就添加到聊天记录里
            history.add(AiMessage.from(assistantText));

            // ── v0.14: Fire onStop hook ──
            if (toolHooks != null) toolHooks.onStop(assistantText);

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
        void onUsage(LLMProvider.Usage usage);
        void onComplete(String finalText);
        void onError(Throwable e);
        /** Called when a mid-turn steer is injected. */
        default void onSteer(String text) {}
        /** Called when context compaction happens. summary may be null for mechanical fold. */
        default void onCompaction(String summary, int foldedMessages, int keptMessages) {}
        /** Called at phase boundaries (planning → executing). */
        default void onPhase(String label) {}
    }

    // ── v0.14: ToolHooks — lifecycle hooks around tool execution ──

    /**
     * Lifecycle hooks that fire around each tool call.
     * Mirrors Reasonix's hook.ToolHooks interface.
     */
    public interface ToolHooks {
        /**
         * Called BEFORE a tool executes. Return non-null to block the call;
         * the returned string becomes the tool result fed back to the model.
         */
        default String preToolUse(String toolName, String arguments) { return null; }

        /** Called AFTER a tool executes successfully. */
        default void postToolUse(String toolName, String arguments, String result) {}

        /** Called when a user turn is submitted. */
        default void onUserPromptSubmit(String text) {}

        /** Called when a turn completes. */
        default void onStop(String finalText) {}
    }

    // ─────────────────────────────────────────────
    //  Context Compaction (Step 7 — DeepSeek-cache-optimized)
    // ─────────────────────────────────────────────

    /** Return total messages in history (for status display). */
    public int messageCount() { return history.size(); }
    public int lastPromptTokens() { return lastPromptTokens; }
    public boolean isCompacted() { return lastCompactionSummary != null; }

    /**
     * Manually trigger compaction (via /compact command).
     * Always uses force mode so it compacts even below the auto threshold.
     */
    public void forceCompact(LoopCallback callback) {
        compactStuck = false; // manual compact resets stuck state
        try {
            compact(true, callback);
            consecutiveCompacts = 0;
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    /**
     * Check and trigger compaction after each turn.
     * Three-tier: soft-warn (0.5), compact (0.8), force (0.9).
     */
    private void maybeCompact(LLMProvider.Usage usage, LoopCallback callback) {
        lastPromptTokens = usage.promptTokens();
        // Calibrate token estimator from real usage
        int totalChars = countHistoryChars();
        if (totalChars > 0 && usage.promptTokens() > 0) {
            double r = (double) usage.promptTokens() / totalChars;
            if (r > 0.05 && r < 2.0) calibratedTokPerChar = r;
        }

        int high = (int) (CONTEXT_WINDOW * COMPACT_RATIO);
        if (usage.promptTokens() < high) {
            consecutiveCompacts = 0;
            return;
        }
        if (compactStuck) return;

        boolean force = usage.promptTokens() >= (int) (CONTEXT_WINDOW * FORCE_RATIO);

        // Prune stale tool results first (cheap — no LLM call)
        int pruned = pruneStaleToolResults();
        if (pruned > 0) {
            int saved = (int) (pruned * 200 * calibratedTokPerChar); // rough estimate
            if (!force && usage.promptTokens() - saved < high) return;
        }

        // Execute compaction
        try {
            compact(force, callback);
        } catch (Exception e) {
            callback.onError(e);
            return;
        }

        consecutiveCompacts++;
        if (consecutiveCompacts >= 2) {
            compactStuck = true;
            callback.onError(new RuntimeException(
                "上下文窗口太小，压缩无法有效释放空间。请减少工具输出或增大窗口。自动压缩已暂停。"));
        }
    }

    /**
     * Perform one compaction: split history, summarize old region, replace in-place.
     */
    private void compact(boolean force, LoopCallback callback) {
        List<ChatMessage> msgs = history;
        int head = pinnedPrefixLen(msgs);
        int start = tailStart(msgs, head);

        if (start - head < 1) return; // nothing to fold

        List<ChatMessage> region = new ArrayList<>(msgs.subList(head, start));
        var partition = partitionFold(region);
        List<ChatMessage> kept = partition.kept;
        List<ChatMessage> fold = partition.fold;

        if (fold.isEmpty()) return;

        int foldTokens = estimateMessagesTokens(fold);
        if (!force && foldTokens < MIN_FOLD_TOKENS) return;

        callback.onCompaction(null, fold.size(), kept.size());

        // Summarize foldable region via LLM
        String summary;
        try {
            summary = summarize(fold);
        } catch (Exception e) {
            // Mechanical fallback: free context anyway
            summary = "（自动摘要失败: " + e.getMessage() + "。如需早期对话细节请询问用户。）";
        }

        lastCompactionSummary = summary;

        // Rebuild history: prefix + kept + summary + tail
        List<ChatMessage> compacted = new ArrayList<>();
        compacted.addAll(msgs.subList(0, head));   // system + firstUser + prior summaries
        compacted.addAll(kept);                     // small user turns kept verbatim
        compacted.add(UserMessage.from(
            SUMMARY_TAG + "\n" +
            "以下是早期对话摘要（较旧的消息已被压缩以节省上下文）：\n" +
            summary + "\n" +
            SUMMARY_CLOSE
        ));
        compacted.addAll(msgs.subList(start, msgs.size())); // recent tail

        history.clear();
        history.addAll(compacted);

        callback.onCompaction(summary, fold.size(), kept.size());
    }

    /** Count leading messages to keep verbatim (cache-stable prefix). */
    private int pinnedPrefixLen(List<ChatMessage> msgs) {
        int i = 0;
        // Always keep system message
        if (i < msgs.size() && msgs.get(i) instanceof SystemMessage) i++;
        // Keep first user turn if small enough
        if (i < msgs.size() && msgs.get(i) instanceof UserMessage && !isCompactionSummary(msgs.get(i))) {
            UserMessage um = (UserMessage) msgs.get(i);
            String text = um.singleText();
            if (text != null && estimateTokens(text) <= MAX_PINNED_FIRST_USER) i++;
        }
        // Keep prior compaction summaries (never re-compress a summary)
        while (i < msgs.size() && isCompactionSummary(msgs.get(i))) i++;
        return i;
    }

    /** Find where the verbatim tail starts (walk backwards from end, budget-based). */
    private int tailStart(List<ChatMessage> msgs, int head) {
        int start = msgs.size();
        int acc = 0;
        for (int i = msgs.size() - 1; i > head; i--) {
            int tok = (int) (msgChars(msgs.get(i)) * calibratedTokPerChar);
            if (msgs.size() - i > 2 && acc + tok > TAIL_TOKENS) break;
            acc += tok;
            start = i;
        }
        // Align: never start tail with orphan tool result
        while (start > head && start < msgs.size() && msgs.get(start) instanceof ToolExecutionResultMessage) start--;
        return start;
    }

    /** Split region into kept (small user turns, errors) vs foldable (everything else).
     *  CRITICAL: never splits a tool_call from its result — if either is kept, both must be kept. */
    private PartitionResult partitionFold(List<ChatMessage> region) {
        List<ChatMessage> kept = new ArrayList<>();
        List<ChatMessage> fold = new ArrayList<>();
        java.util.Set<Integer> keepIdx = new java.util.HashSet<>();

        // Mark: small user turns and prior summaries always kept
        for (int i = 0; i < region.size(); i++) {
            ChatMessage m = region.get(i);
            if (isCompactionSummary(m)) { keepIdx.add(i); continue; }
            if (m instanceof UserMessage) {
                String text = ((UserMessage) m).singleText();
                if (text != null && estimateTokens(text) <= MAX_PINNED_FIRST_USER) keepIdx.add(i);
            }
            // Keep error tool results
            if (m instanceof ToolExecutionResultMessage) {
                String text = ((ToolExecutionResultMessage) m).text();
                if (text != null && (text.startsWith("Error:") || text.startsWith("blocked:"))) keepIdx.add(i);
            }
        }

        // Phase 1: If we keep a tool result, also keep its caller AI + sibling results
        for (int i = 0; i < region.size(); i++) {
            if (!keepIdx.contains(i)) continue;
            ChatMessage m = region.get(i);
            if (m instanceof ToolExecutionResultMessage) {
                keepAiAndSiblings(region, keepIdx, ((ToolExecutionResultMessage) m).id(), i);
            }
        }

        // Phase 2: If we keep an AI with tool_calls, ALSO keep ALL its tool results.
        // Without this, an AI message stays verbatim but its results get folded →
        // orphaned tool_calls → API rejects with "insufficient tool messages".
        for (int i = 0; i < region.size(); i++) {
            if (!keepIdx.contains(i)) continue;
            ChatMessage m = region.get(i);
            if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    for (int k = i + 1; k < region.size(); k++) {
                        if (region.get(k) instanceof ToolExecutionResultMessage tr
                                && req.id().equals(tr.id())) {
                            keepIdx.add(k);
                        }
                    }
                }
            }
        }

        for (int i = 0; i < region.size(); i++) {
            (keepIdx.contains(i) ? kept : fold).add(region.get(i));
        }
        return new PartitionResult(kept, fold);
    }

    /** Keep the AI caller of a tool result and all its sibling tool results. */
    private void keepAiAndSiblings(List<ChatMessage> region, java.util.Set<Integer> keepIdx,
                                    String callId, int resultIdx) {
        for (int j = resultIdx - 1; j >= 0; j--) {
            ChatMessage aj = region.get(j);
            if (aj instanceof AiMessage && ((AiMessage) aj).hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : ((AiMessage) aj).toolExecutionRequests()) {
                    if (req.id().equals(callId)) {
                        keepIdx.add(j);
                        // Also keep all sibling tool results
                        for (int k = j + 1; k < region.size(); k++) {
                            if (region.get(k) instanceof ToolExecutionResultMessage trk) {
                                for (ToolExecutionRequest req2 : ((AiMessage) aj).toolExecutionRequests()) {
                                    if (req2.id().equals(trk.id())) keepIdx.add(k);
                                }
                            }
                        }
                        return;
                    }
                }
            }
        }
    }

    private record PartitionResult(List<ChatMessage> kept, List<ChatMessage> fold) {}

    /**
     * Remove stale tool call/result pairs from turns before the most recent user turn.
     * Must remove BOTH the tool result AND its corresponding assistant tool_calls, or
     * the API will reject the request with "insufficient tool messages following tool_calls".
     */
    private int pruneStaleToolResults() {
        // Find the last user message index (exclude compaction summaries)
        int lastUserIdx = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage && !isCompactionSummary(history.get(i))) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx <= 0) return 0;

        // Phase 1: identify tool result IDs to prune
        Set<String> prunedIds = new HashSet<>();
        for (int i = 0; i < lastUserIdx; i++) {
            if (history.get(i) instanceof ToolExecutionResultMessage tr) {
                prunedIds.add(tr.id());
            }
        }
        if (prunedIds.isEmpty()) return 0;

        // Phase 2: remove tool results AND strip orphaned tool_calls from assistant messages
        int removed = 0;
        for (int i = lastUserIdx - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m instanceof ToolExecutionResultMessage tr && prunedIds.contains(tr.id())) {
                history.remove(i);
                removed++;
            } else if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                // Check if ALL tool calls from this assistant have been pruned
                boolean allPruned = true;
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    if (!prunedIds.contains(req.id())) {
                        allPruned = false;
                        break;
                    }
                }
                if (allPruned) {
                    // Strip tool_calls: replace with text-only AiMessage to keep any text content
                    String text = ai.text();
                    if (text != null && !text.isBlank()) {
                        history.set(i, AiMessage.from(text));
                    } else {
                        history.remove(i);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    /** Ask the LLM to summarize the foldable region. */
    private String summarize(List<ChatMessage> fold) {
        String transcript = renderTranscript(fold);
        List<ChatMessage> request = List.of(
            SystemMessage.from(SUMMARY_SYSTEM),
            UserMessage.from(transcript)
        );
        LLMProvider.CompleteResult result = provider.complete(request, List.of());
        String text = result.text();
        if (text == null || text.isBlank()) throw new RuntimeException("Summarizer returned empty output");
        return text.strip();
    }

    /** Flatten messages into a readable transcript for the summarizer. */
    private String renderTranscript(List<ChatMessage> msgs) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : msgs) {
            if (m instanceof SystemMessage) {
                sb.append("[system]\n").append(((SystemMessage) m).text()).append("\n\n");
            } else if (m instanceof UserMessage) {
                sb.append("[user]\n").append(((UserMessage) m).singleText()).append("\n\n");
            } else if (m instanceof AiMessage ai) {
                String text = ai.text();
                if (text != null && !text.isBlank()) sb.append("[assistant]\n").append(text).append("\n");
                if (ai.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        sb.append("[assistant calls ").append(req.name()).append("] ")
                          .append(summarizeToolArgs(req.arguments())).append("\n");
                    }
                }
                sb.append("\n");
            } else if (m instanceof ToolExecutionResultMessage tr) {
                sb.append("[tool ").append(tr.id()).append(" result]\n").append(tr.text()).append("\n\n");
            }
        }
        return sb.toString();
    }

    /** Shorten tool arguments to prevent the summarizer from reproducing long text. */
    private String summarizeToolArgs(String args) {
        if (args == null || args.isEmpty()) return "(no arguments)";
        if (args.length() > 80) return "(" + args.length() + " chars)";
        return args.replace("\n", "\\n");
    }

    /** Check if a message is a prior compaction summary. */
    // 检查一条消息是不是压缩摘要（有没有 <compaction-summary> 标签）
    private boolean isCompactionSummary(ChatMessage m) {
       //先看类型——不是用户消息？绝不可能是摘要，直接返回 false。因为摘要只可能以 UserMessage 的形式存在
        if (!(m instanceof UserMessage)) return false;
        //如果是用户消息，再看内容
        String text = ((UserMessage) m).singleText();
        //如果内容为空，肯定不是摘要 不为空，再检查是否以 <compaction-summary> 开头
        return text != null && text.stripLeading().startsWith(SUMMARY_TAG);
    }

    /** Scan history for the most recent compaction summary. */
    private String detectCompactionSummary(List<ChatMessage> msgs) {
        // 从最后一条往前翻（因为摘要可能在中间偏后的位置，从后往前找更快）
        for (int i = msgs.size() - 1; i >= 0; i--) {
            //检查这条消息是不是压缩摘要（有没有 <compaction-summary> 标签）
            if (isCompactionSummary(msgs.get(i))) {
                //找到了！把摘要内容取出来存好
                return ((UserMessage) msgs.get(i)).singleText();
            }
        }
        return null;
    }

    // ── Token estimation (no local tokenizer — calibrated from real API usage) ──

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int runes = text.codePointCount(0, text.length());
        int byBytes = (bytes + 3) / 4;
        // CJK-heavy text is closer to 1 token per character
        return Math.max(byBytes, runes);
    }

    private int estimateMessagesTokens(List<ChatMessage> msgs) {
        int total = 0;
        for (ChatMessage m : msgs) {
            total += 4; // message framing overhead
            total += msgChars(m);
        }
        return (int) (total * calibratedTokPerChar);
    }

    private int msgChars(ChatMessage m) {
        int n = 0;
        if (m instanceof SystemMessage sm) n += sm.text().length();
        else if (m instanceof UserMessage um) { String t = um.singleText(); n += t != null ? t.length() : 0; }
        else if (m instanceof AiMessage ai) {
            String t = ai.text(); n += t != null ? t.length() : 0;
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    n += req.name().length() + req.arguments().length();
                }
            }
        } else if (m instanceof ToolExecutionResultMessage tr) {
            n += tr.text().length();
            if (tr.id() != null) n += tr.id().length();
        }
        return n;
    }

    private int countHistoryChars() {
        int n = 0;
        for (ChatMessage m : history) n += msgChars(m);
        return n;
    }

    // ─────────────────────────────────────────────
    //  v0.16: Checkpoint snapshot helpers
    // ─────────────────────────────────────────────

    /** Extract file path from tool arguments and snapshot before mutation. */
    private void snapshotFileIfNeeded(String toolName, String arguments) {
        String path = extractFilePath(toolName, arguments);
        if (path != null) {
            checkpointStore.snapshot(path);
        }
    }

    /** Extract the file path from a tool's JSON arguments. */
    private String extractFilePath(String toolName, String arguments) {
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
            switch (toolName) {
                case "write_file":
                case "edit_file":
                    return resolvePath(json, "file_path", "path", "file");
                case "multi_edit":
                    return resolvePath(json, "path", "file_path");
                case "move_file":
                    return resolvePath(json, "source", "source_path");
                default:
                    return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolvePath(com.fasterxml.jackson.databind.JsonNode json, String... keys) {
        for (String key : keys) {
            if (json.has(key) && !json.get(key).asText().isBlank()) {
                String pathStr = json.get(key).asText();
                // Return relative path for checkpoint key
                Path p = Path.of(pathStr);
                if (p.isAbsolute()) {
                    try {
                        Path ws = Path.of("").toAbsolutePath();
                        return ws.relativize(p.normalize()).toString();
                    } catch (IllegalArgumentException e) {
                        return pathStr;
                    }
                }
                return pathStr;
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────
    //  Plan Mode (v0.10) — read-only planning gate
    // ─────────────────────────────────────────────

    /**
     * Returns a block reason if the tool call should be denied in plan mode.
     * Returns null if the call is allowed (read-only tool, or safe bash command).
     */
    private String planModeBlockReason(String toolName, String arguments) {
        // Denied tools are always blocked
        if (PLAN_MODE_DENIED_TOOLS.contains(toolName)) {
            return "\"" + toolName + "\" is not available in plan mode. " +
                "Keep exploring with read-only tools — exit plan mode to make changes.";
        }
        // Bash: check for safe commands
        if ("bash".equals(toolName)) {
            return planModeBashBlockReason(arguments);
        }
        // All other writer tools: blocked
        return "\"" + toolName + "\" is a writer tool and plan mode is read-only. " +
            "Keep exploring with read-only tools, then exit plan mode to make changes.";
    }

    /** Check if a bash command is safe in plan mode. Returns null if allowed. */
    private String planModeBashBlockReason(String arguments) {
        // Extract command from JSON args
        String command = null;
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
            if (json.has("command")) command = json.get("command").asText();
            else if (json.has("cmd")) command = json.get("cmd").asText();
        } catch (Exception e) {
            return "bash arguments are not valid JSON";
        }
        if (command == null || command.isBlank()) return null; // no command to check

        String trimmed = command.strip();
        String lower = trimmed.toLowerCase();

        // Reject commands with shell metacharacters
        for (String meta : PLAN_MODE_BASH_META) {
            if (lower.contains(meta)) {
                return "bash command in plan mode must not contain shell operators (\"" +
                    meta + "\"). Use separate read-only calls.";
            }
        }

        // Check against safe command prefix whitelist
        for (String safe : PLAN_MODE_SAFE_BASH) {
            if (lower.startsWith(safe.toLowerCase())) {
                // Verify shell-argument boundary: safe prefix must end with
                // whitespace or be the entire command
                if (lower.length() == safe.length()) return null;
                char next = lower.charAt(safe.length());
                if (Character.isWhitespace(next)) return null;
            }
        }

        return "bash command \"" + abbreviate(trimmed, 60) +
            "\" is not in the safe read-only list. Use read-only tools for " +
            "exploration, then exit plan mode to run this command.";
    }

    private static String abbreviate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ─────────────────────────────────────────────
    //  v0.14.1: Storm Breaker + Repeat Guard (Reasonix parity)
    // ─────────────────────────────────────────────

    /**
     * Build a signature for repeat detection — only for write tools.
     * Returns null for read-only tools or unsupported write tools.
     * Mirrors Reasonix's repeatSuccessSignature.
     */
    private static String repeatSignature(String toolName, String arguments) {
        switch (toolName) {
            case "write_file", "edit_file", "multi_edit", "move_file":
                return toolName + "\0" + canonicalArgs(arguments);
            case "bash":
                // Only track bash commands that write files
                String cmd = extractBashCommand(arguments);
                if (cmd != null && isWriteCommand(cmd)) {
                    return "bash\0" + normalizeCommand(cmd);
                }
                return null;
            default:
                return null; // task, unknown tools — don't track
        }
    }

    private static String canonicalArgs(String args) {
        // Normalize whitespace for signature comparison
        return args.replaceAll("\\s+", " ").trim();
    }

    private static String extractBashCommand(String arguments) {
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
            if (json.has("command")) return json.get("command").asText();
            if (json.has("cmd")) return json.get("cmd").asText();
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isWriteCommand(String cmd) {
        String lower = cmd.toLowerCase().trim();
        return lower.startsWith("echo ") || lower.startsWith("mkdir ") ||
               lower.contains(">") || lower.contains(">>") ||
               lower.startsWith("mv ") || lower.startsWith("cp ") ||
               lower.startsWith("rm ") || lower.startsWith("del ") ||
               lower.startsWith("mvn ") || lower.startsWith("javac ") ||
               lower.startsWith("git commit") || lower.startsWith("git add");
    }

    private static String normalizeCommand(String cmd) {
        return cmd.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    /** Record a successful write tool call for repeat detection. */
    private void recordRepeatSuccess(String toolName, String arguments, boolean readOnly) {
        if (readOnly) return; // Reasonix: only track write tools
        String sig = repeatSignature(toolName, arguments);
        if (sig == null) return; // not a tracked write tool
        if (repeatSuccessCounts == null) repeatSuccessCounts = new HashMap<>();
        repeatSuccessCounts.merge(sig, 1, Integer::sum);
    }

    /**
     * Track consecutive tool failures and append storm warning to result.
     * Mirrors Reasonix's applyStormBreaker which appends to results[0].
     */
    private String appendStormWarning(String toolName, String toolResult) {
        String currentSig = toolName + "\n" + truncateForSig(toolResult);
        if (stormSig != null && currentSig.equals(stormSig)) {
            stormCount++;
        } else {
            stormSig = currentSig;
            stormCount = 1;
        }
        if (stormCount >= STORM_THRESHOLD) {
            stormCount = 0; // reset to avoid re-triggering immediately
            return toolResult + "\n\n[loop guard] \"" + toolName +
                "\" has now failed " + STORM_THRESHOLD +
                " times in a row with the same error. Re-sending it — even with the wording " +
                "changed — will not help. Change approach: use a different tool, verify the " +
                "arguments, or explain the blocker in your final answer.";
        }
        return toolResult;
    }

    /** Truncate error text for signature comparison; strip variable parts. */
    private static String truncateForSig(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("\\d+", "N")
            .replaceAll("0x[0-9a-fA-F]+", "0xN");
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }
}
