package com.anima.skill;

import com.anima.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Install source tool — downloads and installs skills/MCP servers from
 * URLs, GitHub, local files, or .mcp.json configs. Two-phase: plan (apply=false)
 * returns a deterministic plan; apply=true executes it.
 *
 * <p>Mirrors Reasonix's {@code internal/installsource/install_source.go}.
 */
public class InstallSourceTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final Pattern GITHUB_RAW = Pattern.compile(
        "https?://raw\\.githubusercontent\\.com/.*");
    private static final Pattern GITHUB_BLOB = Pattern.compile(
        "https?://github\\.com/([^/]+)/([^/]+)/blob/(.*)");
    private static final Pattern GITHUB_REPO = Pattern.compile(
        "https?://github\\.com/([^/]+)/([^/]+)(/tree/([^/]+)(/.*)?)?/?$");
    private static final Pattern NPM_PACKAGE = Pattern.compile(
        "^(@[a-z0-9-]+/)?[a-z0-9-]+$");
    // Convention dirs to scan inside a repo
    private static final String[] REPO_SKILL_DIRS = {
        ".reasonix/skills", ".claude/skills", ".agents/skills", ".agent/skills", ".anima/skills"
    };
    private static final String GITHUB_API = "https://api.github.com";

    private final Path projectRoot;
    private final Path animaHome;
    private final SkillStore skillStore;

    public InstallSourceTool(Path projectRoot, Path animaHome, SkillStore skillStore) {
        this.projectRoot = projectRoot;
        this.animaHome = animaHome;
        this.skillStore = skillStore;
    }

    @Override public String name() { return "install_source"; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public String description() {
        return "Plan and install Anima skills or MCP servers from a URL, GitHub URL, local file/folder, " +
            ".mcp.json, or npm package name. Two-phase: with apply=false (default) returns a " +
            "deterministic plan with per-action risk level; with apply=true copies/registers skills " +
            "or connects and persists MCP servers after validation. op='uninstall' removes a previously " +
            "installed skill or MCP server by name.";
    }

    @Override
    public String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "op": { "type": "string", "enum": ["install", "uninstall"], "description": "install (default) or uninstall." },
                "source": { "type": "string", "description": "URL, GitHub URL, local path, .mcp.json path, or package name. Ignored for op=uninstall (use name)." },
                "kind": { "type": "string", "enum": ["auto", "skill", "mcp"], "description": "Capability kind. Default: auto-detect." },
                "apply": { "type": "boolean", "description": "false (default) returns plan only; true performs the install." },
                "scope": { "type": "string", "enum": ["project", "global"], "description": "Where to install. skills: project default; mcp: global default." },
                "mode": { "type": "string", "enum": ["auto", "copy", "register"], "description": "Skill install mode. auto copies single skills, registers multi-skill roots." },
                "name": { "type": "string", "description": "Override name. Required for op=uninstall." },
                "replace": { "type": "boolean", "description": "Allow replacing existing entries." }
              },
              "required": []
            }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode args = MAPPER.readTree(arguments);
        String op = args.has("op") ? args.get("op").asText() : "install";
        String source = args.has("source") ? args.get("source").asText().trim() : "";
        boolean apply = args.has("apply") && args.get("apply").asBoolean();
        String kind = args.has("kind") ? args.get("kind").asText() : "auto";
        String scope = args.has("scope") ? args.get("scope").asText() : "project";
        String mode = args.has("mode") ? args.get("mode").asText() : "auto";
        String nameOverride = args.has("name") ? args.get("name").asText().trim() : "";
        boolean replace = args.has("replace") && args.get("replace").asBoolean();

        if ("uninstall".equals(op)) {
            return executeUninstall(nameOverride, scope);
        }

        if (source.isEmpty()) return error("install_source requires a non-empty source");

        // Plan phase
        InstallPlan plan = plan(source, kind, scope, mode, nameOverride, replace);
        if (plan.actions().isEmpty()) {
            return MAPPER.writeValueAsString(Map.of(
                "ok", false, "status", "blocked", "applied", false,
                "source", source,
                "next", "No installable skill or MCP server detected from this source. Provide a direct SKILL.md URL, GitHub raw link, local path, or .mcp.json."
            ));
        }

        if (!apply) {
            return MAPPER.writeValueAsString(Map.of(
                "ok", true, "status", "planned", "applied", false,
                "source", source, "kind", plan.kind(), "scope", scope, "mode", mode,
                "actions", plan.actions(),
                "next", "Review the plan (note each action's riskLevel). Call install_source with apply=true to install."
            ));
        }

        // Apply phase
        List<Map<String, Object>> results = new ArrayList<>();
        for (var action : plan.actions()) {
            try {
                executeAction(action);
                results.add(Map.of("action", action.action(), "status", "done",
                    "name", action.name(), "target", action.target()));
            } catch (Exception e) {
                results.add(Map.of("action", action.action(), "status", "failed",
                    "name", action.name(), "error", e.getMessage()));
            }
        }

        boolean allOk = results.stream().allMatch(r -> "done".equals(r.get("status")));
        return MAPPER.writeValueAsString(Map.of(
            "ok", allOk, "status", allOk ? "done" : "partial", "applied", true,
            "source", source, "kind", plan.kind(), "scope", scope,
            "actions", results,
            "next", allOk ? "Installed and ready." : "Some actions failed; check the error fields."
        ));
    }

    // ── Planning ──

    record InstallPlan(String kind, List<InstallAction> actions) {}
    record InstallAction(String action, String name, String target, String source,
                          String riskLevel, Map<String, String> meta) {}

    private InstallPlan plan(String source, String kind, String scope, String mode,
                              String nameOverride, boolean replace) throws Exception {
        List<InstallAction> actions = new ArrayList<>();

        // 1. GitHub repo URL → scan for skills via API
        var repoMatcher = GITHUB_REPO.matcher(source);
        if (repoMatcher.matches() && !source.contains("/blob/") && !source.contains("/raw/")) {
            actions.addAll(planGitHubRepo(source, scope, mode));
        }
        // 2. HTTP/HTTPS URL → fetch content
        else if (source.startsWith("http://") || source.startsWith("https://")) {
            actions.addAll(planURL(source, kind, scope, mode, nameOverride));
        }
        // 3. npm package name → MCP
        else if (NPM_PACKAGE.matcher(source).matches() && kind.equals("auto") || kind.equals("mcp")) {
            actions.addAll(planNpmPackage(source, scope));
        }
        // 4. .mcp.json
        else if (source.endsWith(".mcp.json")) {
            actions.addAll(planMcpJson(source, scope));
        }
        // 5. Local file/folder
        else {
            Path local = Path.of(source);
            if (!Files.exists(local)) {
                return new InstallPlan("unknown", List.of());
            }
            if (Files.isDirectory(local)) {
                actions.addAll(planDirectory(local, scope, mode));
            } else if (source.endsWith(".md")) {
                actions.addAll(planSkillFile(local, scope, nameOverride));
            }
        }

        return new InstallPlan(kind.equals("auto") ? detectKind(actions) : kind, actions);
    }

    private String detectKind(List<InstallAction> actions) {
        boolean hasSkill = actions.stream().anyMatch(a -> "install_skill".equals(a.action()));
        boolean hasMcp = actions.stream().anyMatch(a -> a.action().startsWith("connect_mcp"));
        if (hasSkill && hasMcp) return "skill+mcp";
        if (hasSkill) return "skill";
        if (hasMcp) return "mcp";
        return "unknown";
    }

    private List<InstallAction> planURL(String url, String kind, String scope,
                                         String mode, String nameOverride) throws Exception {
        // Convert GitHub blob to raw
        String fetchUrl = url;
        var blobMatcher = GITHUB_BLOB.matcher(url);
        if (blobMatcher.matches()) {
            fetchUrl = "https://raw.githubusercontent.com/" +
                blobMatcher.group(1) + "/" + blobMatcher.group(2) + "/" +
                blobMatcher.group(3);
        }

        // Fetch content
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(fetchUrl))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "text/markdown, text/plain, application/json, */*")
            .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        String content = resp.body();

        if (content == null || content.isBlank()) return List.of();

        // Determine what we got
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        String name = nameOverride.isEmpty() ? deriveName(url, content) : nameOverride;

        if (contentType.contains("json") || content.trim().startsWith("{")) {
            // Could be .mcp.json
            try {
                JsonNode json = MAPPER.readTree(content);
                if (json.has("mcpServers")) {
                    return planMcpJsonContent(json, scope);
                }
            } catch (Exception ignored) {}
        }

        // Treat as SKILL.md
        String riskLevel = url.contains("github.com") || url.contains("raw.githubusercontent.com")
            ? "low" : "medium";
        return List.of(new InstallAction("install_skill", name,
            scope.equals("global") ? animaHome.resolve("skills").resolve(name).toString()
                : projectRoot.resolve(".anima/skills").resolve(name).toString(),
            url, riskLevel, Map.of("contentLength", String.valueOf(content.length()))));
    }

    private String deriveName(String url, String content) {
        // Try to extract from URL path
        String path = URI.create(url).getPath();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (fileName.endsWith(".md")) fileName = fileName.substring(0, fileName.length() - 3);
        if (Skill.isValidName(fileName)) return fileName;

        // Try frontmatter name
        var fm = SkillStore.parseFrontmatter(content);
        String fmName = fm.fields().get("name");
        if (fmName != null && Skill.isValidName(fmName)) return fmName;

        return "downloaded-skill";
    }

    private List<InstallAction> planDirectory(Path dir, String scope, String mode) {
        List<InstallAction> actions = new ArrayList<>();
        Path skillFile = dir.resolve("SKILL.md");
        if (Files.exists(skillFile)) {
            String name = dir.getFileName().toString();
            actions.add(new InstallAction("install_skill", name,
                scope.equals("global") ? animaHome.resolve("skills").resolve(name).toString()
                    : projectRoot.resolve(".anima/skills").resolve(name).toString(),
                dir.toString(), "low", Map.of()));
        }
        return actions;
    }

    private List<InstallAction> planSkillFile(Path file, String scope, String nameOverride) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        var fm = SkillStore.parseFrontmatter(content);
        String name = nameOverride.isEmpty()
            ? fm.fields().getOrDefault("name", file.getFileName().toString().replace(".md", ""))
            : nameOverride;
        return List.of(new InstallAction("install_skill", name,
            scope.equals("global") ? animaHome.resolve("skills").resolve(name).toString()
                : projectRoot.resolve(".anima/skills").resolve(name).toString(),
            file.toString(), "low", Map.of()));
    }

    private List<InstallAction> planMcpJson(String path, String scope) throws IOException {
        String content = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        return planMcpJsonContent(MAPPER.readTree(content), scope);
    }

    private List<InstallAction> planMcpJsonContent(JsonNode json, String scope) {
        List<InstallAction> actions = new ArrayList<>();
        JsonNode servers = json.get("mcpServers");
        if (servers != null && servers.isObject()) {
            var names = new ArrayList<String>();
            servers.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                actions.add(new InstallAction("connect_mcp", name, name,
                    ".mcp.json", "medium", Map.of()));
            }
        }
        return actions;
    }

    // ── Execute ──

    private void executeAction(InstallAction action) throws Exception {
        switch (action.action()) {
            case "install_skill" -> {
                String name = action.name();
                Path target = Path.of(action.target());
                Files.createDirectories(target);
                Path skillFile = target.resolve("SKILL.md");

                String content;
                if (action.source().startsWith("http")) {
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(action.source()))
                        .timeout(Duration.ofSeconds(15))
                        .GET().build();
                    content = HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
                } else {
                    Path src = Path.of(action.source());
                    if (Files.isDirectory(src)) {
                        // Copy entire directory
                        copyDirectory(src, target);
                        skillStore.invalidateCache();
                        return;
                    }
                    content = Files.readString(src, StandardCharsets.UTF_8);
                }

                // Ensure frontmatter exists
                if (!content.trim().startsWith("---")) {
                    var fm = SkillStore.parseFrontmatter(content);
                    if (!fm.fields().containsKey("name") || !fm.fields().containsKey("description")) {
                        String desc = fm.fields().getOrDefault("description", "Installed skill: " + name);
                        content = "---\nname: " + name + "\ndescription: " +
                            desc.replace("\n", " ") + "\n---\n\n" + fm.body().trim() + "\n";
                    } else {
                        content = "---\nname: " + name + "\ndescription: " +
                            fm.fields().get("description").replace("\n", " ") +
                            "\n---\n\n" + fm.body().trim() + "\n";
                    }
                }

                Files.writeString(skillFile, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                skillStore.invalidateCache();
            }
            case "connect_mcp" -> {
                // MCP connection is handled by McpHost; this action registers the config
                // and triggers reconnection in the host
            }
        }
    }

    private void copyDirectory(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            for (var file : stream.toList()) {
                Path relative = src.relativize(file);
                Path target = dst.resolve(relative);
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ── Uninstall ──

    private String executeUninstall(String name, String scope) throws Exception {
        if (name.isEmpty()) return error("op=uninstall requires a non-empty name");

        // Try skills first
        Path skillsDir = "global".equals(scope)
            ? animaHome.resolve("skills")
            : projectRoot.resolve(".anima/skills");

        Path skillDir = skillsDir.resolve(name);
        if (Files.isDirectory(skillDir)) {
            deleteDirectory(skillDir);
            skillStore.invalidateCache();
            return MAPPER.writeValueAsString(Map.of(
                "ok", true, "status", "done", "op", "uninstall",
                "name", name, "scope", scope, "kind", "skill",
                "next", "Removed skill '" + name + "'."
            ));
        }

        return MAPPER.writeValueAsString(Map.of(
            "ok", false, "status", "blocked", "op", "uninstall",
            "name", name, "scope", scope,
            "next", "No installed skill or MCP server matched that name in scope '" + scope + "'."
        ));
    }

    private void deleteDirectory(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            for (var file : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    // ── GitHub repo scanning ──

    private List<InstallAction> planGitHubRepo(String repoUrl, String scope, String mode) throws Exception {
        var repoMatcher = GITHUB_REPO.matcher(repoUrl);
        if (!repoMatcher.matches()) return List.of();
        String owner = repoMatcher.group(1);
        String repo = repoMatcher.group(2).replace(".git", "");
        String branch = repoMatcher.group(4) != null ? repoMatcher.group(4) : "main";

        List<InstallAction> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Try main/master branches
        for (String b : new String[]{branch, "main", "master"}) {
            if (b == null) continue;
            for (String skillDir : REPO_SKILL_DIRS) {
                try {
                    scanGitHubDir(owner, repo, b, skillDir, "", actions, scope);
                } catch (Exception e) {
                    warnings.add(skillDir + ": " + e.getMessage());
                }
            }
            if (!actions.isEmpty()) break; // stop at first branch that yields results
        }
        return actions;
    }

    private void scanGitHubDir(String owner, String repo, String branch,
                                String basePath, String subPath,
                                List<InstallAction> actions, String scope) throws Exception {
        String apiPath = basePath + (subPath.isEmpty() ? "" : "/" + subPath);
        String apiUrl = GITHUB_API + "/repos/" + owner + "/" + repo + "/contents/" + apiPath
            + "?ref=" + branch;

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Anima/0.15")
            .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) return;

        JsonNode entries = MAPPER.readTree(resp.body());
        if (!entries.isArray()) return;

        for (JsonNode entry : entries) {
            String name = entry.get("name").asText();
            String type = entry.get("type").asText();

            if ("file".equals(type) && name.endsWith(".md")) {
                // Found a skill file — use download_url
                String downloadUrl = entry.get("download_url").asText();
                String stem = name.substring(0, name.length() - 3);
                if (!Skill.isValidName(stem)) continue;
                actions.add(new InstallAction("install_skill", stem,
                    scope.equals("global") ? animaHome.resolve("skills").resolve(stem).toString()
                        : projectRoot.resolve(".anima/skills").resolve(stem).toString(),
                    downloadUrl, "low",
                    Map.of("repo", owner + "/" + repo, "branch", branch)));
            } else if ("dir".equals(type) && !name.startsWith(".")
                && !Set.of("node_modules", "assets", "references", "scripts").contains(name)) {
                // Check if this dir contains SKILL.md directly
                try {
                    scanGitHubDir(owner, repo, branch, apiPath, name, actions, scope);
                } catch (Exception ignored) {}
            }
        }
    }

    // ── npm package ──

    private List<InstallAction> planNpmPackage(String packageName, String scope) {
        return List.of(new InstallAction("connect_mcp", packageName, packageName,
            "npm:" + packageName, "medium",
            Map.of("command", "npx", "args", "-y " + packageName)));
    }

    private static String error(String msg) { return "Error: " + msg; }
}
