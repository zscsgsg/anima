package com.anima.session;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Session lifecycle manager — auto-save, auto-load, and multi-session switching.
 *
 * <p>Storage layout:
 * <pre>
 * .anima/
 *   sessions/
 *     anima-20260626-143022.jsonl
 *     anima-20260626-150511.jsonl
 * </pre>
 */
public class SessionManager {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private static final String PREFIX = "anima-";
    private static final String SUFFIX = ".jsonl";

    private final Path sessionsDir;
    private Path currentFile;

    /** Create a SessionManager rooted at the given working directory. */
    public SessionManager(Path cwd) {
        this.sessionsDir = cwd.resolve(".anima").resolve("sessions");
    }

    /** The current session file path, or null if never saved. */
    public Path currentFile() { return currentFile; }

    /** The session name (filename without path/extension). */
    public String currentName() {
        if (currentFile == null) return null;
        String name = currentFile.getFileName().toString();
        return name.substring(PREFIX.length(), name.length() - SUFFIX.length());
    }

    /** Save the history to the current session file, creating one if needed. */
    public void save(List<ChatMessage> history) throws IOException {
        if (currentFile == null) {
            String name = PREFIX + FMT.format(Instant.now()) + SUFFIX;
            currentFile = sessionsDir.resolve(name);
        }
        SessionStore.save(history, currentFile);
    }

    /** Load the most recent session. Returns empty list if no sessions exist. */
    public List<ChatMessage> loadLatest() throws IOException {
        List<Path> files = SessionStore.listSessions(sessionsDir);
        if (files.isEmpty()) return new ArrayList<>();

        currentFile = files.get(0); // newest first
        return SessionStore.load(currentFile);
    }

    /** Load a session by name (e.g. "20260626-143022"). */
    public List<ChatMessage> loadByName(String name) throws IOException {
        Path file = sessionsDir.resolve(PREFIX + name + SUFFIX);
        if (!Files.exists(file)) {
            throw new IOException("Session not found: " + name);
        }
        currentFile = file;
        return SessionStore.load(file);
    }

    /** List all saved sessions with metadata. */
    public List<SessionInfo> listSessions() throws IOException {
        List<Path> files = SessionStore.listSessions(sessionsDir);
        List<SessionInfo> infos = new ArrayList<>();
        for (Path f : files) {
            String name = f.getFileName().toString();
            name = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
            try {
                int msgCount = SessionStore.countMessages(f);
                String preview = SessionStore.previewFirstUser(f);
                boolean isCurrent = f.equals(currentFile);
                infos.add(new SessionInfo(name, preview, msgCount, isCurrent));
            } catch (IOException e) {
                infos.add(new SessionInfo(name, "(error reading)", 0, false));
            }
        }
        return infos;
    }

    /** Start a new session (auto-saves current first). */
    public void newSession() {
        currentFile = null; // next save creates a new file
    }

    /** Delete a session by name. */
    public void deleteSession(String name) throws IOException {
        Path file = sessionsDir.resolve(PREFIX + name + SUFFIX);
        if (file.equals(currentFile)) {
            throw new IOException("Cannot delete the active session. Use /new first.");
        }
        Files.deleteIfExists(file);
    }

    /** Whether any sessions exist. */
    public boolean hasSessions() throws IOException {
        return !SessionStore.listSessions(sessionsDir).isEmpty();
    }

    // ── Info record ──

    public record SessionInfo(String name, String preview, int messageCount, boolean isCurrent) {
        @Override
        public String toString() {
            String marker = isCurrent ? " *" : "  ";
            return marker + " " + name + "  (" + messageCount + " msgs)  " + preview;
        }
    }
}
