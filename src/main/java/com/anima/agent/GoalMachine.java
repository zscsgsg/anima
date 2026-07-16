package com.anima.agent;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GoalMachine — finite-state machine for long-running autonomous goal pursuit.
 * Mirrors Reasonix's control/goal.go.
 *
 * <p>States: STOPPED → RUNNING → COMPLETE / BLOCKED / STOPPED
 *
 * <p>When a goal is active, the agent pursues it across multiple turns until:
 * <ul>
 *   <li>The model signals [goal:complete] with all todos done</li>
 *   <li>The model signals [goal:blocked:reason] 3 times with the same reason</li>
 *   <li>The continuation limit (50 turns) is reached</li>
 *   <li>The model goes idle (2 turns without tool calls)</li>
 *   <li>The user manually stops the goal</li>
 * </ul>
 */
public class GoalMachine {

    // Constants
    private static final int MAX_AUTO_TURNS = 50;
    private static final int MAX_IDLE_TURNS = 2;

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETE = "complete";
    public static final String STATUS_BLOCKED = "blocked";
    public static final String STATUS_STOPPED = "stopped";

    // Continuation prompts
    public static final String CONTINUE_PROMPT =
        "Continue pursuing the active goal. If it is complete, provide the concise final result " +
        "and end with [goal:complete]. If it is truly blocked on a user-owned decision after " +
        "trying sensible defaults, end with [goal:blocked:<short reason>]. Otherwise do the " +
        "next useful work and end with [goal:continue].";

    public static final String SELF_CHECK_PROMPT =
        "The agent signaled goal completion and all tasks are marked done. Before finalizing, " +
        "perform a brief quality self-check:\n" +
        "1. Verify any changed files compile or parse correctly\n" +
        "2. Run the relevant tests if applicable\n" +
        "3. Confirm the original requirements are met\n" +
        "If everything checks out, signal [goal:complete]. If issues are found, fix them and " +
        "signal [goal:complete] when done.";

    // ── State ──

    private final String statePath; // persisted goal-state sidecar path (null = no persistence)

    private String goal = "";
    private String status = STATUS_STOPPED;
    private int turns = 0;
    private int blocks = 0;
    private String blockReason = "";
    private String interceptMsg = "";
    private int intercepts = 0;
    private boolean selfCheckDone = false;
    private int idleTurns = 0;

    public GoalMachine(String statePath) {
        this.statePath = statePath;
    }

    // ── Public API ──

    /** Whether a goal is currently running. */
    public synchronized boolean isActive() {
        return !goal.isEmpty() && STATUS_RUNNING.equals(status);
    }

    /** Get the current goal text. */
    public synchronized String goalText() { return goal; }

    /** Get the current status for display. */
    public synchronized String statusText() {
        return status.isEmpty() ? STATUS_STOPPED : status;
    }

    /** Get the next continuation prompt, or null if the goal is done. */
    public synchronized String nextTurnPrompt() {
        if (!isActive()) return null;

        // Check for intercept (completion blocked by incomplete todos)
        if (!interceptMsg.isEmpty()) {
            String msg = interceptMsg;
            interceptMsg = "";
            return msg;
        }
        return CONTINUE_PROMPT;
    }

    /**
     * Set (start) or clear (stop) the goal.
     * Returns a notice message, or null if unchanged.
     */
    public synchronized String setGoal(String newGoal) {
        newGoal = newGoal != null ? newGoal.trim() : "";
        if (newGoal.isEmpty()) {
            if (goal.isEmpty() && STATUS_STOPPED.equals(status)) return null;
            goal = "";
            status = STATUS_STOPPED;
            reset();
            return "goal cleared";
        }
        if (goal.equals(newGoal) && STATUS_RUNNING.equals(status)) return null;
        goal = newGoal;
        status = STATUS_RUNNING;
        reset();
        return null; // goal set silently
    }

    /**
     * Advance the FSM after one turn. Caller provides:
     * @param toolCalled whether the last turn made any tool calls
     * @param incompleteTodos list of incomplete todo items (for intercept check)
     * @return a notice if the goal reached a terminal state, or null to continue
     */
    public synchronized String advance(boolean toolCalled, List<String> incompleteTodos) {
        if (!isActive()) return null;

        turns++;

        // Idle detection
        if (toolCalled) {
            idleTurns = 0;
        } else {
            idleTurns++;
            if (idleTurns >= MAX_IDLE_TURNS) {
                idleTurns = 0;
                interceptMsg = "No tool calls in recent turns. Either make progress with tools " +
                    "or signal [goal:blocked:<reason>].";
            }
        }

        // Continuation limit
        if (turns >= MAX_AUTO_TURNS) {
            String notice = "goal continuation limit reached (" + MAX_AUTO_TURNS + " turns)";
            status = STATUS_BLOCKED;
            blockReason = notice;
            return notice;
        }

        return null; // continue
    }

    /**
     * Handle a model-completion signal. Call after the model's last turn.
     * @param lastMessage the model's final message text
     * @param incompleteTodos list of incomplete todo items
     * @return a notice if the goal reached a terminal state, or null
     */
    public synchronized String handleSignal(String lastMessage, List<String> incompleteTodos) {
        if (!isActive()) return null;

        var parsed = parseMarker(lastMessage);
        if (!parsed.found()) return null;

        switch (parsed.status()) {
            case STATUS_COMPLETE:
                // Block if todos are incomplete
                if (!incompleteTodos.isEmpty() && intercepts == 0) {
                    intercepts++;
                    interceptMsg = buildIncompleteMsg(incompleteTodos);
                    return null;
                }
                // Self-check before final completion
                if (!selfCheckDone) {
                    selfCheckDone = true;
                    interceptMsg = SELF_CHECK_PROMPT;
                    return null;
                }
                // Complete!
                intercepts = 0;
                selfCheckDone = false;
                idleTurns = 0;
                status = STATUS_COMPLETE;
                goal = "";
                blocks = 0;
                blockReason = "";
                interceptMsg = "";
                return "goal complete";

            case STATUS_BLOCKED:
                String reason = cleanBlockReason(parsed.reason());
                if (reason.isEmpty()) reason = "blocked";
                if (sameBlock(blockReason, reason)) {
                    blocks++;
                } else {
                    blocks = 1;
                    blockReason = reason;
                }
                if (blocks >= 3) {
                    status = STATUS_BLOCKED;
                    String notice = "goal blocked: " + reason;
                    return notice;
                }
                // Still blocking — inject continue prompt
                interceptMsg = CONTINUE_PROMPT;
                return null;

            default:
                // [goal:continue] or no marker — reset transient state
                blocks = 0;
                blockReason = "";
                intercepts = 0;
                selfCheckDone = false;
                idleTurns = 0;
                return null;
        }
    }

    // ── Helpers ──

    private void reset() {
        turns = 0;
        blocks = 0;
        blockReason = "";
        interceptMsg = "";
        intercepts = 0;
        selfCheckDone = false;
        idleTurns = 0;
    }

    private record Marker(String status, String reason, boolean found) {
        static Marker none() { return new Marker("", "", false); }
    }

    /** Parse [goal:complete], [goal:continue], [goal:blocked:reason] from model output. */
    public static Marker parseMarker(String text) {
        if (text == null) return Marker.none();
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase();
            if ("[goal:complete]".equals(lower)) {
                return new Marker(STATUS_COMPLETE, "", true);
            }
            if ("[goal:continue]".equals(lower)) {
                return new Marker(STATUS_RUNNING, "", true);
            }
            String blockedPrefix = "[goal:blocked:";
            if (lower.startsWith(blockedPrefix) && line.endsWith("]")) {
                String reason = line.substring(blockedPrefix.length(), line.length() - 1).trim();
                return new Marker(STATUS_BLOCKED, reason, true);
            }
            return Marker.none(); // only check last non-empty line
        }
        return Marker.none();
    }

    private String buildIncompleteMsg(List<String> todos) {
        var sb = new StringBuilder();
        sb.append("Goal signaled complete but the following tasks are still incomplete:\n");
        for (String t : todos) {
            sb.append("  - ").append(t).append("\n");
        }
        sb.append("Fix or use todo_write/complete_step to mark done, then [goal:complete] again.");
        return sb.toString();
    }

    private static String cleanBlockReason(String reason) {
        return reason.replaceAll("[：:，,。.；;!！?？\\-_\\[\\]()（）]+$", "").trim();
    }

    private static boolean sameBlock(String a, String b) {
        return normalizeReason(a).equals(normalizeReason(b));
    }

    private static String normalizeReason(String s) {
        s = s.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff]", " ");
        return Arrays.stream(s.split("\\s+")).filter(w -> !w.isEmpty())
            .collect(Collectors.joining(" "));
    }
}
