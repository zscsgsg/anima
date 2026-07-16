package com.anima.tool;

import com.anima.agent.Evidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Evidence-driven step completion — the counterpart to todo_write.
 * Mirrors Reasonix's complete_step tool.
 *
 * <p>The model cites evidence (bash receipts, file changes, diffs) that it
 * observed in the current turn to justify marking a step as complete.
 * The host verifies the cited evidence actually happened before accepting.
 *
 * <p>This forms the "plan → execute → evidence → sign-off" closed loop
 * with todo_write (plan) and tools (execute).
 */
public class CompleteStepTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ThreadLocal holder for the current turn's evidence ledger. */
    private static final ThreadLocal<Evidence> CURRENT_EVIDENCE = new ThreadLocal<>();

    /** Set by AgentLoop at the start of each run(). */
    public static void setCurrentEvidence(Evidence evidence) {
        CURRENT_EVIDENCE.set(evidence);
    }

    /** Clear after the run completes. */
    public static void clearCurrentEvidence() {
        CURRENT_EVIDENCE.remove();
    }

    @Override
    public String name() { return "complete_step"; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String description() {
        return "Sign off a completed step with evidence. " +
               "After finishing a step from your todo_write plan, call this to record that it is done. " +
               "You MUST provide evidence: cite the tool calls that prove the step was completed " +
               "(e.g. bash commands run, files changed, diffs applied). " +
               "A step with no evidence will be rejected. " +
               "Use this INSTEAD of marking an item completed in todo_write — the host will " +
               "advance the todo list automatically when you sign off.";
    }

    @Override
    public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "step": {
              "type": "string",
              "description": "Description of the completed step, matching a todo_write item."
            },
            "evidence": {
              "type": "string",
              "description": "Evidence proving the step is done. Cite specific tool calls from this turn: e.g. 'bash: mvn test passed', 'edit_file: fixed NPE in UserService.java', 'read_file: verified config.properties'. Be concrete — mention file names, command outputs."
            }
          },
          "required": ["step", "evidence"]
        }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode root = MAPPER.readTree(arguments);
        if (!root.has("step") || root.get("step").asText().isBlank()) {
            return "Error: 'step' is required — describe what was completed.";
        }
        if (!root.has("evidence") || root.get("evidence").asText().isBlank()) {
            return "Error: 'evidence' is required — cite the tool calls that prove this step is done. " +
                   "Without evidence, the host cannot verify your claim.";
        }

        String step = root.get("step").asText().strip();
        String evidenceText = root.get("evidence").asText().strip();

        Evidence ledger = CURRENT_EVIDENCE.get();
        if (ledger == null) {
            // No evidence ledger available — accept with a warning (headless/test mode)
            return "Step signed off: \"" + step + "\" (no evidence ledger; accepted without verification).";
        }

        // Verify: there must be at least some successful tool calls this turn
        if (ledger.successCount() == 0) {
            return "Error: cannot complete step \"" + step + "\" — no successful tool calls " +
                   "recorded in this turn. Evidence must come from actual tool executions " +
                   "(bash, read_file, edit_file, etc.) in the current turn.";
        }

        // Verify: the evidence text should mention at least one tool name that was
        // actually called this turn
        boolean foundMatch = false;
        StringBuilder availableTools = new StringBuilder();
        for (Evidence.Receipt receipt : ledger.all()) {
            if (availableTools.length() > 0) availableTools.append(", ");
            availableTools.append(receipt.toolName());
            if (receipt.success() && evidenceText.toLowerCase().contains(receipt.toolName().toLowerCase())) {
                foundMatch = true;
            }
        }

        if (!foundMatch) {
            return "Error: the evidence you cited does not match any tool calls from this turn. " +
                   "Available tools this turn: " + availableTools + ". " +
                   "Re-read your evidence and cite the actual tool calls that happened.";
        }

        int totalReceipts = ledger.size();
        int successCount = ledger.successCount();
        return "Step signed off: \"" + step + "\". " +
               "Evidence verified — " + successCount + "/" + totalReceipts +
               " tool calls succeeded this turn.";
    }
}
