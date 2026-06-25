package com.anima.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Executes a shell command and returns stdout + stderr.
 */
public class BashTool implements Tool {

    @Override
    public String name() { return "bash"; }

    @Override
    public String description() {
        return "Execute a shell command. Use this to run build commands, tests, git operations, or any CLI tool.";
    }

    @Override
    public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "The shell command to execute (e.g. 'mvn --version', 'git status')"
            }
          },
          "required": ["command"]
        }
        """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        String command = json.get("command").asText();

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb = new ProcessBuilder();
        if (isWindows) {
            pb.command("cmd", "/c", command);
        } else {
            pb.command("sh", "-c", command);
        }
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() + line.length() > 32_000) {
                    output.append("\n... (truncated)");
                    proc.destroyForcibly();
                    break;
                }
                output.append(line).append("\n");
            }
        }

        boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            output.append("\n[Command timed out after 60s]");
        }
        return output.toString().trim();
    }
}
