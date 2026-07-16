package com.anima.tool;

import com.anima.agent.AgentLoop;
import com.anima.agent.AgentLoop.LoopCallback;
import com.anima.llm.LLMProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ParallelTasksTool — dispatch multiple sub-agent tasks concurrently and
 * collect all results. Supports optional DAG dependency graph (depends_on).
 * Mirrors Reasonix's parallel_tasks tool.
 *
 * <p>Each sub-task runs as a foreground sub-agent in its own thread, with
 * results aggregated after all complete. Dependency tasks can read the
 * results of their predecessors via on-disk "wisdom" files.
 */
public class ParallelTasksTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(
        Thread.ofVirtual().name("parallel-task-", 0).factory()
    );

    private final TaskTool taskTool;
    private final LLMProvider provider;
    private final ToolRegistry parentRegistry;
    private final String workspaceRoot;
    private final int defaultMaxSteps;
    private final String parentSystemPrompt;

    public ParallelTasksTool(TaskTool taskTool, LLMProvider provider,
                             ToolRegistry parentRegistry, String workspaceRoot,
                             int defaultMaxSteps, String parentSystemPrompt) {
        this.taskTool = taskTool;
        this.provider = provider;
        this.parentRegistry = parentRegistry;
        this.workspaceRoot = workspaceRoot;
        this.defaultMaxSteps = defaultMaxSteps;
        this.parentSystemPrompt = parentSystemPrompt;
    }

    @Override public String name() { return "parallel_tasks"; }

    @Override public boolean isReadOnly() { return false; }

    @Override public String description() {
        return "Dispatch multiple sub-agent tasks concurrently and collect their results. " +
               "Each task runs in its own sub-agent in parallel. Blocks until all complete. " +
               "Supports depends_on for task dependency ordering.";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "tasks": {
              "type": "array",
              "description": "Array of sub-tasks to run in parallel.",
              "minItems": 2,
              "items": {
                "type": "object",
                "properties": {
                  "prompt": {
                    "type": "string",
                    "description": "The task prompt for the sub-agent."
                  },
                  "description": {
                    "type": "string",
                    "description": "Optional short label (3-7 words)."
                  },
                  "tools": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Optional tool whitelist for this sub-agent."
                  },
                  "max_steps": {
                    "type": "integer",
                    "description": "Optional max tool-call rounds.",
                    "minimum": 1
                  },
                  "depends_on": {
                    "type": "array",
                    "items": {"type": "integer"},
                    "description": "Zero-based indices of tasks this one depends on. Will not start until those complete."
                  }
                },
                "required": ["prompt"]
              }
            }
          },
          "required": ["tasks"]
        }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode root = MAPPER.readTree(arguments);
        if (!root.has("tasks") || !root.get("tasks").isArray()) {
            return "Error: 'tasks' array is required";
        }
        var tasksNode = root.get("tasks");
        int n = tasksNode.size();
        if (n == 0) return "Error: at least one task is required";
        if (n == 1) return "Error: parallel_tasks with a single task is equivalent to task; use task instead";

        // Parse task items
        List<ParallelTaskItem> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            JsonNode tn = tasksNode.get(i);
            if (!tn.has("prompt") || tn.get("prompt").asText().isBlank()) {
                return "Error: task " + (i + 1) + ": prompt is required";
            }
            String prompt = tn.get("prompt").asText();
            String desc = tn.has("description") ? tn.get("description").asText() : "task-" + (i + 1);
            List<String> toolList = null;
            if (tn.has("tools") && tn.get("tools").isArray()) {
                toolList = new ArrayList<>();
                for (JsonNode t : tn.get("tools")) toolList.add(t.asText());
            }
            int maxSteps = tn.has("max_steps") ? tn.get("max_steps").asInt() : 0;
            if (maxSteps <= 0) maxSteps = Math.max(defaultMaxSteps / 2, 5);

            List<Integer> deps = new ArrayList<>();
            if (tn.has("depends_on") && tn.get("depends_on").isArray()) {
                for (JsonNode d : tn.get("depends_on")) deps.add(d.asInt());
            }
            items.add(new ParallelTaskItem(i, prompt, desc, toolList, maxSteps, deps));
        }

        // Validate dependencies (no cycles, valid indices)
        String depErr = validateDependencies(items, n);
        if (depErr != null) return depErr;

        // Execute with dependency ordering
        return executeParallel(items, n);
    }

    private String executeParallel(List<ParallelTaskItem> items, int n) throws Exception {
        // Track dependency states
        int[] remaining = new int[n];   // unmet dependency count
        boolean[] done = new boolean[n];
        String[] outputs = new String[n];
        Exception[] errors = new Exception[n];

        for (var item : items) remaining[item.index] = item.dependsOn.size();

        // Completion channel
        BlockingQueue<PTaskResult> doneQueue = new LinkedBlockingQueue<>();

        // Wisdom directory for inter-task communication
        Path wisdomDir = Files.createTempDirectory("parallel-wisdom-");
        try {
            CountDownLatch allDone = new CountDownLatch(n);

            // Seed: submit tasks with no dependencies
            for (var item : items) {
                if (remaining[item.index] == 0) {
                    submitTask(item, wisdomDir, doneQueue, allDone);
                }
            }

            // Process completions, unblock dependents
            int completed = 0;
            while (completed < n) {
                PTaskResult r = doneQueue.take();
                completed++;
                done[r.index] = true;
                outputs[r.index] = r.output;
                errors[r.index] = r.error;

                // Write wisdom file for dependent tasks
                writeWisdom(wisdomDir, r.index, r.output, r.error);

                // Unblock tasks whose last dependency just completed
                for (var item : items) {
                    if (done[item.index] || remaining[item.index] == 0) continue;
                    for (int dep : item.dependsOn) {
                        if (dep == r.index) remaining[item.index]--;
                    }
                    if (remaining[item.index] == 0) {
                        submitTask(item, wisdomDir, doneQueue, allDone);
                    }
                }
            }

            allDone.await();

            // Build result summary
            var sb = new StringBuilder();
            sb.append("Completed ").append(n).append(" parallel tasks:\n\n");
            for (int i = 0; i < n; i++) {
                sb.append("── ").append(items.get(i).description).append(" ──\n");
                if (errors[i] != null) {
                    sb.append("[FAILED] ").append(errors[i].getMessage()).append("\n\n");
                } else {
                    String out = outputs[i] != null ? outputs[i] : "(no output)";
                    // Truncate very long outputs
                    if (out.length() > 2000) out = out.substring(0, 1997) + "...";
                    sb.append(out).append("\n\n");
                }
            }
            return sb.toString().trim();
        } finally {
            // Clean up wisdom directory
            try { Files.walk(wisdomDir).sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }
    }

    private void submitTask(ParallelTaskItem item, Path wisdomDir,
                            BlockingQueue<PTaskResult> doneQueue, CountDownLatch allDone) {
        EXECUTOR.submit(() -> {
            try {
                // Build prompt: prepend wisdom references for dependent tasks
                String prompt = item.prompt;
                if (!item.dependsOn.isEmpty() && Files.exists(wisdomDir)) {
                    var entries = Files.list(wisdomDir).collect(Collectors.toList());
                    if (!entries.isEmpty()) {
                        var sb = new StringBuilder();
                        sb.append("Previous task results are available at ");
                        sb.append(wisdomDir.toString());
                        sb.append(" (use read_file to inspect them before starting).\n\n");
                        sb.append(prompt);
                        prompt = sb.toString();
                    }
                }

                // Build sub-registry
                ToolRegistry subReg = taskTool.buildSubRegistry(item.toolWhitelist);

                // Build system prompt
                String sysPrompt = parentSystemPrompt + "\n\n" + TaskTool.SUBAGENT_SYSTEM;

                // Run sub-agent in isolated session
                var subAgent = new AgentLoop(provider, subReg, item.maxSteps, sysPrompt, null);
                var result = new Object() {
                    String answer = null;
                    Throwable error = null;
                };

                subAgent.run(prompt, new LoopCallback() {
                    @Override public void onUserMessage(String t) {}
                    @Override public void onThinking(String t) {}
                    @Override public void onResponse(String t) {}
                    @Override public void onToolStart(String n, String a) {}
                    @Override public void onToolPermissionDenied(String n, String a) {}
                    @Override public void onToolResult(String n, String r) {}
                    @Override public void onUsage(LLMProvider.Usage u) {}
                    @Override public void onComplete(String text) { result.answer = text; }
                    @Override public void onError(Throwable e) { result.error = e; }
                });

                if (result.error != null) {
                    doneQueue.put(new PTaskResult(item.index, null,
                        new RuntimeException(result.error.getMessage(), result.error)));
                } else {
                    doneQueue.put(new PTaskResult(item.index, result.answer, null));
                }
            } catch (Exception e) {
                try {
                    doneQueue.put(new PTaskResult(item.index, null, e));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                allDone.countDown();
            }
        });
    }

    private void writeWisdom(Path dir, int index, String output, Exception error) {
        try {
            Path file = dir.resolve("task-" + (index + 1) + ".md");
            String content;
            if (error != null) {
                content = "# Task " + (index + 1) + " Result\n\nFAILED: " + error.getMessage();
            } else {
                content = "# Task " + (index + 1) + " Result\n\n" +
                    (output != null ? output.trim() : "(no output)");
            }
            Files.writeString(file, content);
        } catch (Exception ignored) {}
    }

    private String validateDependencies(List<ParallelTaskItem> items, int n) {
        for (var item : items) {
            for (int dep : item.dependsOn) {
                if (dep < 0 || dep >= n) {
                    return "Error: task " + (item.index + 1) +
                        ": depends_on " + dep + " out of range (0-" + (n - 1) + ")";
                }
                if (dep == item.index) {
                    return "Error: task " + (item.index + 1) + ": self-referencing depends_on";
                }
            }
        }
        // Cycle detection via DFS
        int[] state = new int[n]; // 0=unvisited, 1=visiting, 2=done
        for (int i = 0; i < n; i++) {
            if (hasCycle(items, state, i)) {
                return "Error: task dependency cycle detected";
            }
        }
        return null;
    }

    private boolean hasCycle(List<ParallelTaskItem> items, int[] state, int i) {
        if (state[i] == 1) return true;
        if (state[i] == 2) return false;
        state[i] = 1;
        for (int dep : items.get(i).dependsOn) {
            if (hasCycle(items, state, dep)) return true;
        }
        state[i] = 2;
        return false;
    }

    // ── Data types ──

    private record ParallelTaskItem(int index, String prompt, String description,
                                     List<String> toolWhitelist, int maxSteps,
                                     List<Integer> dependsOn) {}

    private record PTaskResult(int index, String output, Exception error) {}
}
