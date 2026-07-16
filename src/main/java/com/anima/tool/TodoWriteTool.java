package com.anima.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Structured task list tracking — mirrors Reasonix's todo_write.
 * The agent sends the COMPLETE list every call; it replaces the previous one.
 * Exactly one item in_progress at a time; flip to completed when done.
 *
 * <p>ReadOnly: true — no filesystem effect, just records the plan.
 */
public class TodoWriteTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "todo_write"; }
    @Override public boolean isReadOnly() { return true; }

    @Override public String description() {
        return """
            Create and update a structured task list for your current coding session. \
            Send the COMPLETE list every time — it replaces the previous list. \
            Keep exactly one item in_progress at a time. Flip to completed the moment \
            it's done (don't batch completions). Skip for trivial single-step tasks. \
            Two levels: level 0 = phase/milestone, level 1 = sub-step of the phase above. \
            Each item: content (imperative), status (pending|in_progress|completed), \
            activeForm (present-continuous shown while working), level (0 or 1, optional).""";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "todos": {
              "type": "array",
              "description": "The complete task list, in order. Replaces any previous list.",
              "items": {
                "type": "object",
                "properties": {
                  "content": {
                    "type": "string",
                    "description": "Imperative description of the task, e.g. 'Add the parser'."
                  },
                  "status": {
                    "type": "string",
                    "enum": ["pending", "in_progress", "completed"],
                    "description": "Task state. At most one in_progress at a time."
                  },
                  "activeForm": {
                    "type": "string",
                    "description": "Present-continuous form shown while in progress, e.g. 'Adding the parser'."
                  },
                  "level": {
                    "type": "integer",
                    "enum": [0, 1],
                    "description": "0 = phase/milestone, 1 = sub-step. Omit for a flat list."
                  }
                },
                "required": ["content", "status"]
              }
            }
          },
          "required": ["todos"]
        }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode root = MAPPER.readTree(arguments);
        if (!root.has("todos") || !root.get("todos").isArray()) {
            return "Error: 'todos' array is required";
        }

        int done = 0, active = 0, pending = 0;
        StringBuilder out = new StringBuilder("Task list updated:\n");

        var todos = root.get("todos");
        for (int i = 0; i < todos.size(); i++) {
            JsonNode item = todos.get(i);
            String content = item.has("content") ? item.get("content").asText() : "";
            String status = item.has("status") ? item.get("status").asText() : "pending";

            if (content.isEmpty()) {
                return "Error: todo " + (i + 1) + ": content is required";
            }

            switch (status) {
                case "completed" -> done++;
                case "in_progress" -> active++;
                default -> pending++;
            }

            String prefix = status.equals("in_progress") ? "🔄" :
                           status.equals("completed") ? "✅" : "⏳";
            String levelStr = item.has("level") ? "  ".repeat(item.get("level").asInt()) : "";
            out.append(levelStr).append(prefix).append(" ").append(content).append("\n");
        }

        out.append("\n").append(done).append(" completed, ")
           .append(active).append(" in progress, ")
           .append(pending).append(" pending");

        if (active > 1) {
            out.append("\n⚠ More than one task in_progress — keep exactly one at a time.");
        }

        return out.toString();
    }
}
