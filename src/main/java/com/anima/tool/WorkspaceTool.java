package com.anima.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Workspace info tool — returns workspace directory structure and metadata.
 * Mirrors Reasonix's workspace tool.
 */
public class WorkspaceTool implements Tool {

    private final Path workspaceRoot;

    public WorkspaceTool(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Override public String name() { return "workspace"; }

    @Override public boolean isReadOnly() { return true; }

    @Override
    public String description() {
        return "Return information about the current workspace: root path, " +
            "top-level directory listing, git branch (if applicable), and " +
            "file counts broken down by extension. Use this to orient yourself " +
            "in a new project.";
    }

    @Override
    public String schema() {
        return """
            {
              "type": "object",
              "properties": {}
            }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Workspace: ").append(workspaceRoot).append("\n\n");

        // OS / Java info
        sb.append("OS: ").append(System.getProperty("os.name"))
          .append(" ").append(System.getProperty("os.version")).append("\n");
        sb.append("Java: ").append(System.getProperty("java.version")).append("\n");

        // Git info
        Path gitDir = workspaceRoot.resolve(".git");
        if (Files.exists(gitDir)) {
            sb.append("Git: detected\n");
            try {
                Path headFile = gitDir.resolve("HEAD");
                if (Files.exists(headFile)) {
                    String head = Files.readString(headFile).trim();
                    sb.append("Branch: ").append(head.replace("ref: refs/heads/", "")).append("\n");
                }
            } catch (Exception ignored) {}
        }

        // Top-level listing
        sb.append("\nTop-level:\n");
        try (var entries = Files.list(workspaceRoot)) {
            entries.sorted().forEach(e -> {
                String type = Files.isDirectory(e) ? " [dir]" : "";
                sb.append("  ").append(e.getFileName()).append(type).append("\n");
            });
        } catch (IOException e) {
            sb.append("  (error reading directory: ").append(e.getMessage()).append(")\n");
        }

        return sb.toString().trim();
    }
}
