package com.anima.agent;

/**
 * Decides whether a tool call may proceed. Called before every tool execution.
 * Headless mode: auto-approves everything. Interactive mode: asks the user.
 */
public interface PermissionGate {
    /**
     * @return true if the tool call is allowed, false to deny
     */
    boolean allow(String toolName, String arguments);
}
