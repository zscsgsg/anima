package com.anima.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A capability the AI model can invoke.
 * Mirrors Reasonix's Tool interface design.
 */
public interface Tool {
    ObjectMapper MAPPER = new ObjectMapper();

    /** Unique tool name exposed to the model (e.g. "read_file", "bash"). */
    String name();

    /** Human-readable description for the model's tool-selection prompt. */
    String description();

    /** JSON Schema string describing the tool's parameters. */
    String schema();

    /** Execute the tool with raw JSON arguments. Returns result text for the model. */
    String execute(String arguments) throws Exception;

    /**
     * JSON Schema as a JsonNode for passing to ToolSpecification.parameters().
     * Default implementation parses {@link #schema()}.
     */
    default JsonNode parametersSchema() {
        try {
            return MAPPER.readTree(schema());
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /**
     * Whether this tool is read-only (no filesystem/world side effects).
     * Read-only tools skip permission checks. Default is false (safe).
     */
    default boolean isReadOnly() { return false; }
}
