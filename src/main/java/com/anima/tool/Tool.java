package com.anima.tool;

/**
 * A capability the AI model can invoke.
 * Mirrors Reasonix's Tool interface design.
 */
public interface Tool {
    /** Unique tool name exposed to the model (e.g. "read_file", "bash"). */
    String name();

    /** Human-readable description for the model's tool-selection prompt. */
    String description();

    /** JSON Schema string describing the tool's parameters. */
    String schema();

    /** Execute the tool with raw JSON arguments. Returns result text for the model. */
    String execute(String arguments) throws Exception;
}
