package com.anima.agent;

/**
 * Three-state permission decision (mirrors Reasonix's Allow/Ask/Deny).
 *
 * <pre>
 * ALLOW — execute immediately, no prompt
 * ASK   — defer to interactive approver (or auto-allow in headless mode)
 * DENY  — hard block, the tool NEVER runs
 * </pre>
 */
public enum PermissionDecision {
    ALLOW,
    ASK,
    DENY;

    public boolean isAllowed() { return this == ALLOW; }
    public boolean isDenied()  { return this == DENY; }
    public boolean needsAsk()  { return this == ASK; }
}
