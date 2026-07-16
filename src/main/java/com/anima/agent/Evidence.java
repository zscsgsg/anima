package com.anima.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-turn evidence ledger — records every tool call the Agent makes
 * so complete_step can verify that cited evidence actually happened.
 *
 * <p>Mirrors Reasonix's evidence.Ledger design.
 * Reset at the start of each user turn; tools read it via Agent context.
 */
public class Evidence {

    /**
     * A single tool-call receipt recorded by the host (not the model).
     */
    public record Receipt(
            String toolName,
            String arguments,
            boolean success,
            boolean readOnly
    ) {
        /** Short summary for display/debug. */
        public String summary() {
            String status = success ? "OK" : "FAIL";
            return String.format("[%s] %s %s", status, toolName,
                    arguments.length() > 60 ? arguments.substring(0, 57) + "..." : arguments);
        }
    }

    private final List<Receipt> receipts = new ArrayList<>();

    /** Record a tool execution result. */
    public void record(Receipt receipt) {
        receipts.add(receipt);
    }

    /** Record a tool execution by name + args + success. */
    public void record(String toolName, String arguments, boolean success, boolean readOnly) {
        record(new Receipt(toolName, arguments, success, readOnly));
    }

    /** All receipts for this turn, unmodifiable. */
    public List<Receipt> all() {
        return Collections.unmodifiableList(receipts);
    }

    /** Check if there's any successful receipt matching the given tool name. */
    public boolean hasSuccessful(String toolName) {
        for (Receipt r : receipts) {
            if (r.toolName.equals(toolName) && r.success) return true;
        }
        return false;
    }

    /** Check if a command (bash arg) was run successfully this turn. */
    public boolean hasBashCommand(String commandPrefix) {
        for (Receipt r : receipts) {
            if (r.toolName.equals("bash") && r.success
                    && r.arguments != null && r.arguments.contains(commandPrefix)) {
                return true;
            }
        }
        return false;
    }

    /** Check if a file was read/written this turn. */
    public boolean hasFileOperation(String toolName, String pathContains) {
        for (Receipt r : receipts) {
            if (r.toolName.equals(toolName) && r.success
                    && r.arguments != null && r.arguments.contains(pathContains)) {
                return true;
            }
        }
        return false;
    }

    /** Count of successful receipts. */
    public int successCount() {
        int c = 0;
        for (Receipt r : receipts) if (r.success) c++;
        return c;
    }

    /** Count of failed receipts. */
    public int failureCount() {
        int c = 0;
        for (Receipt r : receipts) if (!r.success) c++;
        return c;
    }

    /** Clear all receipts (called at the start of each new user turn). */
    public void reset() {
        receipts.clear();
    }

    /** Number of receipts recorded. */
    public int size() {
        return receipts.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Evidence[").append(receipts.size()).append("]:");
        for (Receipt r : receipts) {
            sb.append("\n  ").append(r.summary());
        }
        return sb.toString();
    }
}
