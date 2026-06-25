package com.anima.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * Per-session tool registry. Thread-safe for the single-user demo case.
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> list() {
        return new ArrayList<>(tools.values());
    }

    public List<ToolSpecification> schemas() {
        List<ToolSpecification> list = new ArrayList<>();
        for (Tool t : tools.values()) {
            list.add(ToolSpecification.builder()
                .name(t.name())
                .description(t.description())
                .build());
        }
        return list;
    }
}
