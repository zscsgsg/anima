package com.anima.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads project memory hierarchically at session start, mirroring Reasonix's
 * {@code internal/memory/doc.go}. The composed block is byte-stable across
 * turns — critical for DeepSeek's automatic prefix cache.
 *
 * <h3>Discovery order (ascending precedence):</h3>
 * <ol>
 *   <li>User-global: ~/.anima/ANIMA.md (or AGENTS.md/CLAUDE.md/REASONIX.md)</li>
 *   <li>Ancestors: ANIMA.md/AGENTS.md/CLAUDE.md from .git root down to cwd</li>
 *   <li>Project root: ./ANIMA.md (or AGENTS.md/CLAUDE.md/REASONIX.md)</li>
 *   <li>Local overrides: ./ANIMA.local.md (or AGENTS.local.md/CLAUDE.local.md)</li>
 * </ol>
 *
 * <p>@import supports recursive resolution up to 5 levels with cycle detection
 * and ~ expansion.
 */
public class ProjectMemory {

    private static final Pattern IMPORT_PATTERN = Pattern.compile("^@(.+)$");
    private static final int MAX_IMPORT_DEPTH = 5;

    // Recognized memory filenames (Reasonix-compatible)
    private static final String[] DOC_NAMES = {
        "ANIMA.md", "AGENTS.md", "CLAUDE.md", "REASONIX.md"
    };
    private static final String[] LOCAL_NAMES = {
        "ANIMA.local.md", "AGENTS.local.md", "CLAUDE.local.md", "REASONIX.local.md"
    };

    private final String block;
    private final List<Source> sources;
    private final boolean empty;

    private ProjectMemory(String block, List<Source> sources) {
        this.block = block;
        this.sources = sources;
        this.empty = sources.isEmpty();
    }

    /**
     * Load project memory from the given working directory.
     * Never throws — missing files just mean less memory.
     */
    public static ProjectMemory load(Path cwd) {
        List<Source> sources = new ArrayList<>();

        // 1. User-global memory (lowest precedence)
        Path userHome = Path.of(System.getProperty("user.home"));
        Path userAnima = userHome.resolve(".anima");
        for (String name : DOC_NAMES) {
            Path f = userAnima.resolve(name);
            if (Files.isRegularFile(f)) {
                sources.add(new Source(f.toString(), "user",
                    readWithImports(f, new HashSet<>(), 0)));
            }
        }

        // 2. Ancestor chain: from .git root down to cwd (outermost first)
        List<Path> ancestors = ancestorChain(cwd);
        Set<Path> seenDirs = new HashSet<>();
        for (Path dir : ancestors) {
            if (!seenDirs.add(dir.toAbsolutePath().normalize())) continue;
            String scope = dir.toAbsolutePath().normalize()
                .equals(cwd.toAbsolutePath().normalize()) ? "project" : "ancestor";
            for (String name : DOC_NAMES) {
                Path f = dir.resolve(name);
                if (Files.isRegularFile(f)) {
                    sources.add(new Source(f.toString(), scope,
                        readWithImports(f, new HashSet<>(), 0)));
                }
            }
        }

        // 3. Project-local overrides (highest precedence)
        for (String name : LOCAL_NAMES) {
            Path f = cwd.resolve(name);
            if (Files.isRegularFile(f)) {
                sources.add(new Source(f.toString(), "local",
                    readWithImports(f, new HashSet<>(), 0)));
            }
        }

        if (sources.isEmpty()) {
            return new ProjectMemory("", List.of());
        }

        String block = compose(sources);
        return new ProjectMemory(block, sources);
    }

    /** The cache-stable memory block to prepend to the system prompt. */
    public String block() { return block; }

    /** Whether no memory files were found. */
    public boolean isEmpty() { return empty; }

    /** List of loaded memory sources (for /memory display). */
    public List<Source> sources() { return sources; }

    /** A memory source file. */
    public record Source(String path, String scope, String body) {}

    // ── Ancestor chain ──

    /**
     * Walk from cwd up to the nearest .git root, returning directories
     * from outermost (git root) to innermost (cwd).
     */
    private static List<Path> ancestorChain(Path cwd) {
        Path abs = cwd.toAbsolutePath().normalize();
        Path gitRoot = findGitRoot(abs);
        if (gitRoot == null) {
            // No .git found: just return cwd as the single "root"
            return List.of(abs);
        }

        List<Path> chain = new ArrayList<>();
        // Walk from cwd up to gitRoot
        for (Path dir = abs; ; dir = dir.getParent()) {
            chain.add(dir);
            if (dir.equals(gitRoot)) break;
            if (dir.getParent() == null || dir.getParent().equals(dir)) break;
        }
        // Reverse to get outermost → innermost
        Collections.reverse(chain);
        return chain;
    }

    /** Find the nearest ancestor containing .git (inclusive of start). */
    private static Path findGitRoot(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d.toAbsolutePath().normalize();
            if (d.getParent() == null || d.getParent().equals(d)) break;
        }
        return null;
    }

    // ── Import resolution ──

    /**
     * Read a file, expanding @path imports inline with recursive resolution.
     * Supports ~ expansion and cycle detection.
     */
    private static String readWithImports(Path file, Set<Path> seen, int depth) {
        try {
            Path abs = file.toAbsolutePath().normalize();
            if (!seen.add(abs)) return "<!-- @import cycle: " + file + " -->\n";
            if (depth >= MAX_IMPORT_DEPTH) {
                return "<!-- @import depth limit: " + file + " -->\n";
            }

            String raw = Files.readString(file);
            StringBuilder out = new StringBuilder();
            for (String line : raw.split("\n", -1)) {
                Matcher m = IMPORT_PATTERN.matcher(line.trim());
                if (m.matches()) {
                    String importPath = m.group(1).trim();
                    Path resolved = resolveImport(importPath, file.getParent());
                    if (resolved != null && Files.isRegularFile(resolved)) {
                        try {
                            out.append(readWithImports(resolved, seen, depth + 1))
                               .append('\n');
                        } catch (Exception ignored) {
                            out.append("[无法导入: ").append(importPath).append("]\n");
                        }
                    } else {
                        out.append("[导入文件不存在: ").append(importPath).append("]\n");
                    }
                } else {
                    out.append(line).append('\n');
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "[无法读取: " + file + " — " + e.getMessage() + "]";
        }
    }

    /** Resolve @import path: ~ → user home, absolute → as-is, else relative. */
    private static Path resolveImport(String path, Path baseDir) {
        if (path.startsWith("~")) {
            String rest = path.substring(1).replaceFirst("^[/\\\\]+", "");
            return Path.of(System.getProperty("user.home")).resolve(rest).normalize();
        }
        Path p = Path.of(path);
        if (p.isAbsolute()) return p.normalize();
        return baseDir.resolve(path).normalize();
    }

    // ── Compose ──

    /** Compose sources into a single Markdown section for the system prompt. */
    private static String compose(List<Source> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 项目记忆\n\n");
        sb.append("以下是从项目记忆文件加载的持久化上下文。这是该项目的长期指导说明。\n");

        for (Source s : sources) {
            sb.append("\n## ").append(s.path()).append(" (").append(s.scope()).append(")\n\n");
            sb.append(s.body().stripTrailing()).append('\n');
        }
        return sb.toString();
    }
}
