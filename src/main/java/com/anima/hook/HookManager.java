package com.anima.hook;

import com.anima.agent.AgentLoop.ToolHooks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Hook system — shell-command lifecycle hooks around the agent loop.
 * Mirrors Reasonix's hook system: PreToolUse / PostToolUse / Stop / UserPromptSubmit.
 *
 * <p>Configuration: .anima/settings.json (project) and ~/.anima/settings.json (global).
 * Project hooks fire before global ones. A hook's exit code determines its verdict:
 * 0 = pass, 2 = block (PreToolUse only), other = warn.
 *
 * <pre>{@code
 * {
 *   "hooks": {
 *     "PreToolUse": [
 *       { "match": "bash", "command": "echo blocked && exit 2" }
 *     ],
 *     "PostToolUse": [
 *       { "match": "*", "command": "echo 'tool ran'" }
 *     ],
 *     "Stop": [
 *       { "command": "echo 'turn done'" }
 *     ]
 *   }
 * }
 * }</pre>
 */
public class HookManager implements ToolHooks {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Event {
        PreToolUse,
        PostToolUse,
        UserPromptSubmit,
        Stop
    }

    /**
     * One hook configuration entry.
     */
    public record HookConfig(
            String match,      // anchored regex matching tool name; "" or "*" = all
            String command,    // shell command to run
            int timeoutMs      // per-hook timeout, 0 = default 5000ms
    ) {
        public boolean matches(String toolName) {
            if (match == null || match.isEmpty() || "*".equals(match)) return true;
            try {
                return Pattern.compile("^(?:" + match + ")$").matcher(toolName).find();
            } catch (Exception e) {
                return false; // malformed regex → never fire
            }
        }

        public int effectiveTimeout() {
            return timeoutMs > 0 ? timeoutMs : 5000;
        }
    }

    private final List<ResolvedHook> hooks = new ArrayList<>();

    private record ResolvedHook(Event event, HookConfig config, Path source) {}

    // ── Loading ──

    /**
     * Load hooks from project and global settings files.
     */
    public static HookManager load(Path projectRoot) {
        HookManager mgr = new HookManager();

        // Project hooks (.anima/settings.json)
        Path projectSettings = projectRoot.resolve(".anima").resolve("settings.json");
        if (Files.exists(projectSettings)) {
            mgr.loadFile(projectSettings);
        }

        // Global hooks (~/.anima/settings.json)
        Path homeDir = Path.of(System.getProperty("user.home"));
        Path globalSettings = homeDir.resolve(".anima").resolve("settings.json");
        if (Files.exists(globalSettings)) {
            mgr.loadFile(globalSettings);
        }

        return mgr;
    }

    private void loadFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(content);
            JsonNode hooksNode = root.get("hooks");
            if (hooksNode == null) return;

            for (Event event : Event.values()) {
                JsonNode eventHooks = hooksNode.get(event.name());
                if (eventHooks == null || !eventHooks.isArray()) continue;

                for (JsonNode entry : eventHooks) {
                    String match = entry.has("match") ? entry.get("match").asText() : "";
                    String command = entry.has("command") ? entry.get("command").asText() : "";
                    if (command.isEmpty()) continue;
                    int timeout = entry.has("timeout") ? entry.get("timeout").asInt() : 0;

                    hooks.add(new ResolvedHook(event, new HookConfig(match, command, timeout), file));
                }
            }
        } catch (IOException e) {
            // Malformed file → no hooks loaded (never crash on config error)
            System.err.println("[HookManager] Failed to load " + file + ": " + e.getMessage());
        }
    }

    // ── ToolHooks implementation ──

    @Override
    public String preToolUse(String toolName, String arguments) {
        for (ResolvedHook rh : hooks) {
            if (rh.event != Event.PreToolUse) continue;
            if (!rh.config.matches(toolName)) continue;

            HookResult result = runHook(rh, toolName, arguments, null);
            if (result.blocked) {
                return "blocked by hook (" + rh.config.command + "): " + result.stdout;
            }
        }
        return null; // no block
    }

    @Override
    public void postToolUse(String toolName, String arguments, String result) {
        for (ResolvedHook rh : hooks) {
            if (rh.event != Event.PostToolUse) continue;
            if (!rh.config.matches(toolName)) continue;
            runHook(rh, toolName, arguments, result);
        }
    }

    @Override
    public void onUserPromptSubmit(String text) {
        for (ResolvedHook rh : hooks) {
            if (rh.event != Event.UserPromptSubmit) continue;
            runHook(rh, null, text, null);
        }
    }

    @Override
    public void onStop(String finalText) {
        for (ResolvedHook rh : hooks) {
            if (rh.event != Event.Stop) continue;
            runHook(rh, null, null, finalText);
        }
    }

    // ── Hook execution ──

    private record HookResult(boolean blocked, String stdout) {}

    private HookResult runHook(ResolvedHook rh, String toolName, String args, String toolResult) {
        try {
            String payload = buildPayload(rh.event, toolName, args, toolResult);
            ProcessBuilder pb = buildProcess(rh.config.command);
            pb.directory(Path.of("").toAbsolutePath().toFile());

            Process proc = pb.start();

            // Write payload to stdin
            try (OutputStream os = proc.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
                os.write('\n');
                os.flush();
            }

            // Read stdout/stderr with timeout
            boolean finished = proc.waitFor(rh.config.effectiveTimeout(), TimeUnit.MILLISECONDS);
            String stdout = "";
            String stderr = "";
            try {
                stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                stderr = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {}

            if (!finished) {
                proc.destroyForcibly();
                // Timeout on a PreToolUse = block (safety-first)
                boolean block = rh.event == Event.PreToolUse;
                return new HookResult(block, "hook timed out after " + rh.config.effectiveTimeout() + "ms");
            }

            int exitCode = proc.exitValue();
            if (exitCode == 0) return new HookResult(false, stdout);
            if (exitCode == 2 && rh.event == Event.PreToolUse) {
                return new HookResult(true, !stderr.isEmpty() ? stderr : stdout);
            }
            // Non-zero exit on non-blocking event = warn only
            if (!stderr.isEmpty()) {
                System.err.println("[HookManager] Hook exited " + exitCode + ": " + rh.config.command + " → " + stderr);
            }
            return new HookResult(false, stdout);
        } catch (Exception e) {
            // Spawn failure on PreToolUse = block (can't verify, so deny)
            boolean block = rh.event == Event.PreToolUse;
            return new HookResult(block, "hook spawn failed: " + e.getMessage());
        }
    }

    private ProcessBuilder buildProcess(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new ProcessBuilder("cmd", "/c", command);
        }
        return new ProcessBuilder("sh", "-c", command);
    }

    private String buildPayload(Event event, String toolName, String args, String toolResult) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"event\":\"").append(escapeJson(event.name())).append("\"");
        sb.append(",\"cwd\":\"").append(escapeJson(Path.of("").toAbsolutePath().toString())).append("\"");
        if (toolName != null) {
            sb.append(",\"toolName\":\"").append(escapeJson(toolName)).append("\"");
        }
        if (args != null) {
            sb.append(",\"toolArgs\":").append(truncateArg(args, 512));
        }
        if (toolResult != null) {
            String truncated = toolResult.length() > 1024 ? toolResult.substring(0, 1024) + "..." : toolResult;
            sb.append(",\"toolResult\":\"").append(escapeJson(truncated)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String truncateArg(String arg, int max) {
        if (arg.length() <= max) return arg;
        return arg.substring(0, max) + "\"}";
    }

    // ── Introspection ──

    /** List all loaded hooks for display (/hooks command). */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (hooks.isEmpty()) {
            lines.add("No hooks configured.");
            lines.add("Create .anima/settings.json or ~/.anima/settings.json with a \"hooks\" object.");
            return lines;
        }
        lines.add("Loaded " + hooks.size() + " hook(s):");
        Map<Event, List<ResolvedHook>> grouped = new LinkedHashMap<>();
        for (ResolvedHook rh : hooks) {
            grouped.computeIfAbsent(rh.event, k -> new ArrayList<>()).add(rh);
        }
        for (var entry : grouped.entrySet()) {
            lines.add("  [" + entry.getKey() + "]");
            for (ResolvedHook rh : entry.getValue()) {
                String match = rh.config.match.isEmpty() ? "*" : rh.config.match;
                lines.add("    " + match + " → " + rh.config.command +
                        "  (from " + rh.source.getFileName() + ")");
            }
        }
        return lines;
    }

    public boolean isEmpty() { return hooks.isEmpty(); }
}
