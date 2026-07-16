package com.anima.agent;

/**
 * Decides whether a tool call may proceed.
 * Returns a three-state decision: ALLOW (run immediately), ASK (prompt user),
 * or DENY (hard block — model sees "blocked" and can adapt).
 *
 * <p>Implementations:
 * <ul>
 *   <li>Headless mode: ASK resolves to ALLOW (no interactive user)</li>
 *   <li>Interactive mode: ASK prompts the user inline</li>
 * </ul>
 */
public interface PermissionGate {
    /**
     * Check permission for a tool call.
     *
     * @param toolName  the tool being called
     * @param arguments raw JSON arguments from the LLM
     * @param readOnly  whether the tool itself is read-only
     * @return the decision — ALLOW, ASK, or DENY
     */
    PermissionDecision check(String toolName, String arguments, boolean readOnly);
}
