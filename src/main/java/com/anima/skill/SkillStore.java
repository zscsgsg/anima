package com.anima.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Discovers, loads, and manages skills across scopes.
 * Mirrors Reasonix's {@code internal/skill/skill.go Store}.
 *
 * <p>Discovery order (higher priority wins on name collision):
 * <ol>
 *   <li>Project: {@code .anima/skills/} under workspace root</li>
 *   <li>Global: {@code ~/.anima/skills/} under user home</li>
 *   <li>Builtin: shipped with Anima</li>
 * </ol>
 *
 * <p>Each skill can be either:
 * <ul>
 *   <li>Directory-layout: {@code <name>/SKILL.md} with frontmatter</li>
 *   <li>Flat-file: {@code <name>.md} with skill frontmatter markers</li>
 * </ul>
 */
public class SkillStore {

    private static final String SKILLS_DIR = "skills";
    private static final String SKILL_FILE = "SKILL.md";
    // Convention directories scanned for skills (mirrors Reasonix's ConventionDirs)
    private static final String[] CONVENTION_DIRS = {".anima", ".reasonix", ".agents", ".agent", ".claude"};
    private static final int DEFAULT_MAX_DEPTH = 3;
    private static final Set<String> SKIP_SCAN_DIRS = Set.of("assets", "node_modules", "references", "scripts");

    private final Path projectRoot;
    private final Path animaHomeDir;
    private final Set<String> disabledNames;
    private final int maxDepth;
    private final boolean disableBuiltins;

    // Cache: loaded skills indexed by name
    private volatile List<Skill> cachedList;

    private SkillStore(Builder builder) {
        this.projectRoot = builder.projectRoot;
        this.animaHomeDir = builder.animaHomeDir;
        this.disabledNames = Set.copyOf(builder.disabledNames);
        this.maxDepth = builder.maxDepth > 0 && builder.maxDepth <= 5 ? builder.maxDepth : DEFAULT_MAX_DEPTH;
        this.disableBuiltins = builder.disableBuiltins;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        Path projectRoot;
        Path animaHomeDir;
        Set<String> disabledNames = Set.of();
        int maxDepth = DEFAULT_MAX_DEPTH;
        boolean disableBuiltins;

        public Builder projectRoot(Path path) { this.projectRoot = path; return this; }
        public Builder animaHomeDir(Path path) { this.animaHomeDir = path; return this; }
        public Builder disabledNames(Set<String> names) { this.disabledNames = names; return this; }
        public Builder maxDepth(int depth) { this.maxDepth = depth; return this; }
        public Builder disableBuiltins(boolean disable) { this.disableBuiltins = disable; return this; }

        public SkillStore build() { return new SkillStore(this); }
    }

    // ── Discovery roots ──

    private List<DiscoveryRoot> roots() {
        List<DiscoveryRoot> roots = new ArrayList<>();
        // Project scope: scan all convention dirs under project root
        if (projectRoot != null) {
            for (String c : CONVENTION_DIRS) {
                Path dir = projectRoot.resolve(c).resolve(SKILLS_DIR);
                roots.add(new DiscoveryRoot(dir, Skill.Scope.PROJECT, c.equals(".claude")));
            }
        }
        // Home scope: anima home skills dir + all convention dirs under user home
        if (animaHomeDir != null) {
            Path homeSkills = animaHomeDir.resolve(SKILLS_DIR);
            roots.add(new DiscoveryRoot(homeSkills, Skill.Scope.GLOBAL, false));
        }
        Path userHome = Path.of(System.getProperty("user.home"));
        for (String c : CONVENTION_DIRS) {
            Path dir = userHome.resolve(c).resolve(SKILLS_DIR);
            // Skip if same as animaHome skills dir (already added)
            if (animaHomeDir != null && dir.normalize().equals(animaHomeDir.resolve(SKILLS_DIR).normalize())) {
                continue;
            }
            roots.add(new DiscoveryRoot(dir, Skill.Scope.GLOBAL, c.equals(".claude")));
        }
        return roots;
    }

    // ── List / Read ──

    /** List all discoverable skills, sorted by name for cache-stable prompt prefix. */
    public List<Skill> list() {
        if (cachedList != null) return cachedList;

        Map<String, Skill> byName = new LinkedHashMap<>();

        // Project & global roots (higher priority first)
        for (DiscoveryRoot root : roots()) {
            if (!Files.isDirectory(root.dir)) continue;
            for (Skill sk : discoverRoot(root)) {
                if (disabledNames.contains(sk.name())) continue;
                byName.putIfAbsent(sk.name(), sk);
            }
        }

        // Builtins (lowest priority)
        if (!disableBuiltins) {
            for (Skill sk : BuiltinSkills.all()) {
                if (disabledNames.contains(sk.name())) continue;
                byName.putIfAbsent(sk.name(), sk);
            }
        }

        List<Skill> result = new ArrayList<>(byName.values());
        result.sort(Comparator.comparing(Skill::name));
        cachedList = result;
        return result;
    }

    /** Invalidate the cache (call after install or external file change). */
    public void invalidateCache() { cachedList = null; }

    /** Read one skill by name. Returns empty if not found. */
    public Optional<Skill> read(String name) {
        if (!Skill.isValidName(name)) return Optional.empty();
        if (disabledNames.contains(name)) return Optional.empty();
        return list().stream().filter(s -> s.name().equals(name)).findFirst();
    }

    public boolean hasProjectScope() { return projectRoot != null; }

    // ── Discovery ──

    private List<Skill> discoverRoot(DiscoveryRoot root) {
        List<Skill> out = new ArrayList<>();
        scanDir(root.dir, root.scope, 1, new HashSet<>(), out);
        return out;
    }

    private void scanDir(Path dir, Skill.Scope scope, int depth,
                         Set<Path> seen, List<Skill> out) {
        try {
            Path key = dir.toRealPath();
            if (!seen.add(key)) return;
        } catch (IOException e) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                Skill sk = readEntry(entry, scope);
                if (sk != null) {
                    out.add(sk);
                    continue;
                }
                if (depth >= maxDepth) continue;
                if (Files.isDirectory(entry)) {
                    String name = entry.getFileName().toString();
                    if (!name.startsWith(".") && !SKIP_SCAN_DIRS.contains(name.toLowerCase())) {
                        scanDir(entry, scope, depth + 1, seen, out);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private Skill readEntry(Path entry, Skill.Scope scope) {
        String name = entry.getFileName().toString();
        if (Files.isDirectory(entry)) {
            if (!Skill.isValidName(name)) return null;
            Path skillFile = entry.resolve(SKILL_FILE);
            if (!Files.isRegularFile(skillFile)) return null;
            return parse(skillFile, name, scope);
        }
        if (Files.isRegularFile(entry) && name.toLowerCase().endsWith(".md")) {
            String stem = name.substring(0, name.length() - 3);
            if (!Skill.isValidName(stem)) return null;
            return parse(entry, stem, scope);
        }
        return null;
    }

    // ── Parsing ──

    private Skill parse(Path file, String stem, Skill.Scope scope) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
            // Strip BOM
            if (content.startsWith("\uFEFF")) content = content.substring(1);

            FrontmatterResult fm = parseFrontmatter(content);

            String name = stem;
            String fmName = fm.fields().get("name");
            if (fmName != null && Skill.isValidName(fmName)) {
                name = fmName;
            }

            String desc = fm.fields().getOrDefault("description", "").trim();
            String body = fm.body().trim();

            var runAs = Skill.RunAs.parse(fm.fields().getOrDefault("runas",
                fm.fields().getOrDefault("context", null)));

            String model = fm.fields().getOrDefault("model", "").trim();
            String effort = fm.fields().getOrDefault("effort", "").trim();

            List<String> allowedTools = parseListField(fm.fields().get("allowed-tools"));

            return new Skill(name, desc, body, scope,
                file.toAbsolutePath().toString(),
                allowedTools, runAs,
                model.isEmpty() ? null : model,
                effort.isEmpty() ? null : effort);

        } catch (IOException e) {
            return null;
        }
    }

    // ── Frontmatter parsing ──

    /**
     * Parses a ---fenced YAML-like frontmatter block.
     * Mirrors Reasonix's {@code internal/frontmatter/frontmatter.go Split}.
     */
    static FrontmatterResult parseFrontmatter(String content) {
        Map<String, String> fm = new LinkedHashMap<>();
        String[] lines = content.split("\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return new FrontmatterResult(fm, content);
        }

        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) { end = i; break; }
        }
        if (end < 0) return new FrontmatterResult(fm, content);

        // Parse key: value pairs between fences
        for (int i = 1; i < end; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon < 0) continue;

            String key = line.substring(0, colon).trim().toLowerCase();
            String val = line.substring(colon + 1).trim();
            // Strip outer quotes
            if (val.length() >= 2 &&
                ((val.startsWith("\"") && val.endsWith("\"")) ||
                 (val.startsWith("'") && val.endsWith("'")))) {
                val = val.substring(1, val.length() - 1);
            }

            if (val.isEmpty()) {
                // Empty value: parse list items
                List<String> items = new ArrayList<>();
                while (i + 1 < end) {
                    String nextLine = lines[i + 1].trim();
                    if (nextLine.startsWith("-")) {
                        String item = nextLine.substring(1).trim();
                        item = item.replaceAll("^[\"']|[\"']$", "");
                        items.add(item);
                        i++;
                    } else {
                        break;
                    }
                }
                if (!items.isEmpty()) {
                    fm.put(key, String.join(", ", items));
                }
                continue;
            }

            fm.put(key, val);
        }

        String body = String.join("\n", Arrays.copyOfRange(lines, end + 1, lines.length));
        return new FrontmatterResult(fm, body);
    }

    record FrontmatterResult(Map<String, String> fields, String body) {}

    private static List<String> parseListField(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    // ── Create / Install ──

    /**
     * Writes a new skill file as {@code <name>/SKILL.md} at the target scope.
     * Returns the written file path. Refuses to overwrite existing skills.
     */
    public String createWithContent(String name, Skill.Scope scope, String content) throws IOException {
        if (!Skill.isValidName(name)) {
            throw new IOException("Invalid skill name: " + name);
        }

        Path root;
        switch (scope) {
            case PROJECT -> {
                if (projectRoot == null) throw new IOException("Project scope requires a workspace");
                root = projectRoot.resolve(".anima").resolve(SKILLS_DIR);
            }
            default -> {
                root = animaHomeDir != null ? animaHomeDir.resolve(SKILLS_DIR)
                    : Path.of(System.getProperty("user.home"), ".anima", SKILLS_DIR);
            }
        }

        Path flat = root.resolve(name + ".md");
        Path folder = root.resolve(name).resolve(SKILL_FILE);

        if (Files.exists(flat)) throw new IOException("Skill '" + name + "' already exists at " + flat);
        if (Files.exists(folder)) throw new IOException("Skill '" + name + "' already exists at " + folder);

        Files.createDirectories(folder.getParent());
        Files.writeString(folder, content, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        invalidateCache();
        return folder.toAbsolutePath().toString();
    }

    /**
     * Renders a skill file with proper frontmatter.
     */
    public static String renderSkillFile(String name, String desc, String body,
                                          Skill.RunAs runAs, String model,
                                          String effort, List<String> allowedTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("description: ").append(desc).append("\n");
        if (runAs == Skill.RunAs.SUBAGENT) {
            sb.append("runAs: subagent\n");
            if (model != null && !model.isEmpty()) sb.append("model: ").append(model).append("\n");
            if (effort != null && !effort.isEmpty()) sb.append("effort: ").append(effort).append("\n");
            if (allowedTools != null && !allowedTools.isEmpty()) {
                sb.append("allowed-tools: ").append(String.join(", ", allowedTools)).append("\n");
            }
        }
        sb.append("---\n\n");
        sb.append(body.stripTrailing()).append("\n");
        return sb.toString();
    }

    // ── Helper types ──

    record DiscoveryRoot(Path dir, Skill.Scope scope, boolean requireFlatMarker) {}
}
