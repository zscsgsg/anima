package com.anima.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Snapshot-based edit safety net — mirrors Reasonix's checkpoint package.
 * Before a writer tool changes a file, the agent records the file's pre-edit
 * content here, keyed to the current user turn. The user can then rewind the
 * workspace (and conversation) to an earlier turn via /rewind.
 *
 * <p>Git-free by design: snapshots live beside the session, never touch the
 * user's git, and work in a non-git directory. Only the first touch of each
 * file per turn is recorded.
 */
public class CheckpointStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path ckptDir;             // <session>.ckpt/
    private final Path workspaceRoot;
    private final Map<Integer, Checkpoint> done = new TreeMap<>();
    private Checkpoint current;
    private final Set<String> seenThisTurn = new HashSet<>();

    public CheckpointStore(Path sessionsDir, String sessionName, Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        if (sessionsDir != null && sessionName != null) {
            this.ckptDir = sessionsDir.resolve(sessionName + ".ckpt");
        } else {
            this.ckptDir = null;
        }
        load();
    }

    // ── Data types ──

    public record FileSnap(String path, String content) {
        /** content == null means the file didn't exist — restore deletes it. */
        public boolean isCreate() { return content == null; }
    }

    public record Checkpoint(int turn, Instant time, String prompt, int msgIndex,
                              List<FileSnap> files) {
        public static Checkpoint create(int turn, String prompt, int msgIndex) {
            return new Checkpoint(turn, Instant.now(), prompt, msgIndex, new ArrayList<>());
        }
    }

    public record Meta(int turn, Instant time, String prompt, List<String> paths) {}

    // ── Lifecycle ──

    /** Begin a new checkpoint for a user turn. Finalizes the previous one. */
    public void beginTurn(int turn, String prompt, int msgIndex) {
        if (current != null) {
            done.put(current.turn(), current);
        }
        current = Checkpoint.create(turn, prompt, msgIndex);
        seenThisTurn.clear();
        persist(current);
    }

    /** Next turn number: one past the highest existing (0 when empty). */
    public int nextTurn() {
        if (done.isEmpty() && current == null) return 0;
        int max = 0;
        for (int t : done.keySet()) max = Math.max(max, t);
        if (current != null) max = Math.max(max, current.turn());
        return max + 1;
    }

    /**
     * Record the pre-edit state of a file a writer is about to change.
     * Only the first touch per turn is kept. Thread-safe.
     */
    public synchronized void snapshot(String relativePath) {
        if (current == null) return;
        if (seenThisTurn.contains(relativePath)) return;
        seenThisTurn.add(relativePath);

        Path abs = workspaceRoot.resolve(relativePath).normalize();
        // Security: never escape workspace
        if (!abs.startsWith(workspaceRoot)) return;

        if (Files.exists(abs)) {
            try {
                String content = Files.readString(abs);
                current.files().add(new FileSnap(relativePath, content));
                persist(current);
            } catch (IOException ignored) {
                // Can't read — don't snapshot
            }
        } else {
            // File doesn't exist yet — record as create (restore deletes it)
            current.files().add(new FileSnap(relativePath, null));
            persist(current);
        }
    }

    // ── Listing ──

    /** List all checkpoint metadata (oldest first) for the rewind picker. */
    public synchronized List<Meta> list() {
        List<Meta> out = new ArrayList<>();
        for (Checkpoint c : done.values()) {
            out.add(toMeta(c));
        }
        if (current != null) {
            // Current in-progress turn: include the turn itself but NOT its files
            out.add(new Meta(current.turn(), current.time(), current.prompt(), List.of()));
        }
        return out;
    }

    private Meta toMeta(Checkpoint c) {
        List<String> paths = c.files().stream().map(FileSnap::path).collect(Collectors.toList());
        return new Meta(c.turn(), c.time(), c.prompt(), paths);
    }

    // ── Rewind ──

    /**
     * Restore the workspace to its state at the start of turn {@code fromTurn}.
     * Returns the paths written and deleted.
     */
    public synchronized RewindResult restoreCode(int fromTurn) {
        // Find earliest snapshot per path across checkpoints >= fromTurn
        Map<String, FileSnap> earliest = new LinkedHashMap<>();
        List<Checkpoint> all = new ArrayList<>(done.values());
        if (current != null) all.add(current);

        for (Checkpoint c : all) {
            if (c.turn() < fromTurn) continue;
            for (FileSnap f : c.files()) {
                earliest.putIfAbsent(f.path(), f);
            }
        }

        List<String> written = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (FileSnap snap : earliest.values()) {
            Path abs = workspaceRoot.resolve(snap.path()).normalize();
            if (!abs.startsWith(workspaceRoot)) continue; // safety

            if (snap.content() == null) {
                // File was created after this turn — delete it
                try {
                    Files.deleteIfExists(abs);
                    deleted.add(snap.path());
                } catch (IOException ignored) {}
            } else {
                try {
                    Files.createDirectories(abs.getParent());
                    Files.writeString(abs, snap.content());
                    written.add(snap.path());
                } catch (IOException ignored) {}
            }
        }
        return new RewindResult(written, deleted);
    }

    public record RewindResult(List<String> written, List<String> deleted) {
        public boolean isEmpty() { return written.isEmpty() && deleted.isEmpty(); }
    }

    // ── Persistence ──

    private void load() {
        if (ckptDir == null || !Files.exists(ckptDir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(ckptDir, "turn-*.json")) {
            for (Path p : ds) {
                try {
                    Checkpoint c = MAPPER.readValue(p.toFile(), Checkpoint.class);
                    done.put(c.turn(), c);
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    private void persist(Checkpoint c) {
        if (ckptDir == null) return;
        try {
            Files.createDirectories(ckptDir);
            Path file = ckptDir.resolve("turn-" + c.turn() + ".json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), c);
        } catch (IOException ignored) {}
    }

    /** Get the msgIndex for a turn (conversation rewind boundary). */
    public synchronized Integer msgIndexFor(int turn) {
        Checkpoint c = done.get(turn);
        if (c != null) return c.msgIndex();
        if (current != null && current.turn() == turn) return current.msgIndex();
        return null;
    }

    /** Absolute path to the checkpoint directory for display. */
    public Path ckptDir() { return ckptDir; }
}
