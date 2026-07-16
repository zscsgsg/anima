package com.anima.lsp;

import com.anima.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * LSP tool adapters — wraps LspManager's queries as Tool interface implementations.
 * 4 tools: lsp_definition, lsp_references, lsp_hover, lsp_diagnostics.
 * All ReadOnly so they can be dispatched in parallel.
 * Mirrors Reasonix lsp/tool.go.
 */
public final class LspTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LspTool() {}

    /** Return the 4 LSP tools for the given manager. */
    public static List<Tool> tools(LspManager m) {
        return List.of(
            new PosTool("lsp_definition",
                "Jump to where a symbol is defined. Give the file path, the 1-based line number, and the symbol text on that line.",
                m::definition),
            new PosTool("lsp_references",
                "List every reference to a symbol across the workspace. Give the file, the 1-based line, and the symbol text.",
                m::references),
            new PosTool("lsp_hover",
                "Show the type signature and documentation for a symbol. Give the file, the 1-based line, and the symbol text.",
                m::hover),
            new DiagTool(m)
        );
    }

    // ——— position-based tools (definition / references / hover) ———

    private record PosTool(String name, String description, PosFn fn) implements Tool {
        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public boolean isReadOnly() { return true; }

        @Override public String schema() {
            return """
            {
              "type": "object",
              "properties": {
                "file": {
                  "type": "string",
                  "description": "Path to the source file, relative to the workspace root or absolute."
                },
                "line": {
                  "type": "integer",
                  "description": "1-based line number the symbol appears on."
                },
                "symbol": {
                  "type": "string",
                  "description": "The exact symbol text on that line, e.g. \"executeBatch\". Used to locate the column."
                }
              },
              "required": ["file", "line", "symbol"]
            }
            """;
        }

        @Override public String execute(String arguments) throws Exception {
            var json = JSON.readTree(arguments);
            String file = json.get("file").asText();
            int line = json.get("line").asInt(-1);
            String symbol = json.has("symbol") ? json.get("symbol").asText() : "";
            if (file.isEmpty() || line < 1 || symbol.isEmpty())
                return "Error: file, line (>=1), and symbol are required";
            return fn.apply(file, line, symbol);
        }

        interface PosFn {
            String apply(String file, int line, String symbol) throws Exception;
        }
    }

    // ——— diagnostics tool ———

    private record DiagTool(LspManager m) implements Tool {
        @Override public String name() { return "lsp_diagnostics"; }
        @Override public boolean isReadOnly() { return true; }
        @Override public String description() {
            return "Report compiler/linter diagnostics (errors, warnings) for a file from its language server. Use after editing to check the change compiles.";
        }

        @Override public String schema() {
            return """
            {
              "type": "object",
              "properties": {
                "file": {
                  "type": "string",
                  "description": "Path to the source file, relative to the workspace root or absolute."
                }
              },
              "required": ["file"]
            }
            """;
        }

        @Override public String execute(String arguments) throws Exception {
            var json = JSON.readTree(arguments);
            String file = json.get("file").asText();
            if (file.isEmpty()) return "Error: file is required";
            return m.diagnostics(file);
        }
    }
}
