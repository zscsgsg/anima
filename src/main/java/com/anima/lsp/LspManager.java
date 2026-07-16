package com.anima.lsp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static com.anima.lsp.LspTypes.*;

/**
 * Multi-language LSP server pool — lazy-spawns one server per language,
 * reuses across turns, routes queries by file extension.
 * Mirrors Reasonix lsp/manager.go.
 */
public class LspManager implements AutoCloseable {

    private final String wsRoot;
    private final Map<String, ServerSpec> specs;
    private final Map<String, String> extIndex;  // ".java" → "java"

    private final ConcurrentHashMap<String, LspClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CountDownLatch> starting = new ConcurrentHashMap<>();

    public LspManager(String wsRoot, Map<String, ServerSpec> specs) {
        this.wsRoot = wsRoot;
        this.specs = specs;
        this.extIndex = new LinkedHashMap<>();
        for (var entry : specs.entrySet()) {
            for (String ext : entry.getValue().extensions) {
                extIndex.put(ext.toLowerCase(), entry.getKey());
            }
        }
    }

    /**
     * Reasonix-style default server specs — 14 languages.
     * Servers resolve on PATH; nothing is bundled.
     */
    public static Map<String, ServerSpec> defaultSpecs() {
        var m = new LinkedHashMap<String, ServerSpec>();
        m.put("java",       new ServerSpec("jdtls",          new String[0],  new String[0], "java",       new String[]{".java"},
            "install eclipse.jdt.ls: brew install jdtls"));
        m.put("go",         new ServerSpec("gopls",          new String[0],  new String[0], "go",         new String[]{".go"},
            "go install golang.org/x/tools/gopls@latest"));
        m.put("rust",       new ServerSpec("rust-analyzer",  new String[0],  new String[0], "rust",       new String[]{".rs"},
            "rustup component add rust-analyzer"));
        m.put("typescript", new ServerSpec("typescript-language-server", new String[]{"--stdio"}, new String[0], "typescript",
            new String[]{".ts", ".tsx", ".js", ".jsx"}, "npm i -g typescript-language-server typescript"));
        m.put("python",     new ServerSpec("pyright-langserver", new String[]{"--stdio"}, new String[0], "python",
            new String[]{".py", ".pyi"}, "npm i -g pyright"));
        m.put("cpp",        new ServerSpec("clangd",         new String[0],  new String[0], "cpp",        new String[]{".c",".h",".cc",".cpp",".hpp"},
            "install clangd (LLVM): apt install clangd / brew install llvm"));
        m.put("csharp",     new ServerSpec("csharp-ls",      new String[0],  new String[0], "csharp",     new String[]{".cs"},
            "dotnet tool install --global csharp-ls"));
        m.put("kotlin",     new ServerSpec("kotlin-language-server", new String[0], new String[0], "kotlin",
            new String[]{".kt", ".kts"}, "brew install kotlin-language-server"));
        return m;
    }

    @Override
    public void close() {
        for (var c : clients.values()) {
            try { c.close(); } catch (Exception ignored) {}
        }
        clients.clear();
    }

    // ——— resolve: lazy-spawn with concurrent start gate ———

    private LspClient resolve(String filePath) throws Exception {
        String ext = extension(filePath);
        String lang = extIndex.get(ext.toLowerCase());
        if (lang == null) throw new IOException("no LSP configured for " + ext);

        var spec = specs.get(lang);
        if (spec == null || spec.command.isEmpty()) throw new IOException("no LSP configured for " + lang);

        // Fast path: already running
        var c = clients.get(lang);
        if (c != null) return c;

        // Concurrent start gate
        var latch = starting.get(lang);
        if (latch != null) {
            latch.await();
            return resolve(filePath); // retry
        }

        latch = new CountDownLatch(1);
        starting.put(lang, latch);
        try {
            c = spawn(spec);
            clients.put(lang, c);
            return c;
        } finally {
            starting.remove(lang);
            latch.countDown();
        }
    }

    private LspClient spawn(ServerSpec spec) throws Exception {
        String bin = findOnPath(spec.command);
        if (bin == null) {
            throw new IOException("LSP server '" + spec.command + "' not found. " + spec.installHint);
        }
        return new LspClient(bin, spec.args, spec.env, spec.languageId, wsRoot);
    }

    // ——— prepare: resolve + sync + locate ———

    private Prepared prepare(String file, int line, String symbol) throws Exception {
        String abs = absPath(file);
        var c = resolve(abs);
        String uri = LspTypes.pathToUri(abs);
        c.ensureSynced(uri, abs);
        String content = Files.readString(Path.of(abs));
        Position pos = LspTypes.locate(content, line, symbol, ENC_UTF16);
        return new Prepared(c, uri, pos);
    }

    private record Prepared(LspClient client, String uri, Position pos) {}

    // ——— public query methods ———

    public String definition(String file, int line, String symbol) throws Exception {
        var p = prepare(file, line, symbol);
        var raw = p.client.query("textDocument/definition", p.uri, p.pos);
        return LspTypes.formatLocations(wsRoot, "definition", parseLocations(raw));
    }

    public String references(String file, int line, String symbol) throws Exception {
        var p = prepare(file, line, symbol);
        var raw = p.client.references(p.uri, p.pos);
        return LspTypes.formatLocations(wsRoot, "reference", parseLocations(raw));
    }

    public String hover(String file, int line, String symbol) throws Exception {
        var p = prepare(file, line, symbol);
        var raw = p.client.query("textDocument/hover", p.uri, p.pos);
        String h = parseHover(raw);
        return h.isEmpty() ? "no hover information" : h;
    }

    public String diagnostics(String file) throws Exception {
        String abs = absPath(file);
        var c = resolve(abs);
        String uri = LspTypes.pathToUri(abs);
        c.ensureSynced(uri, abs);
        var diags = c.waitDiagnostics(uri, c.docVersion(uri));
        return LspTypes.formatDiagnostics(relPath(abs), diags);
    }

    // ——— helpers ———

    private String absPath(String p) {
        Path path = Paths.get(p);
        if (path.isAbsolute()) return path.normalize().toString();
        return Paths.get(wsRoot, p).normalize().toString();
    }

    private String relPath(String p) {
        try {
            Path ws = Paths.get(wsRoot).normalize();
            return ws.relativize(Paths.get(p).normalize()).toString().replace("\\", "/");
        } catch (IllegalArgumentException e) {
            return p;
        }
    }

    private static String extension(String p) {
        int dot = p.lastIndexOf('.');
        return dot >= 0 ? p.substring(dot) : "";
    }

    private static String findOnPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String pathExt = System.getenv("PATHEXT");
        if (pathExt == null) pathExt = ".COM;.EXE;.BAT;.CMD";

        for (String dir : pathEnv.split(File.pathSeparator)) {
            // Try exact match
            File f = new File(dir, command);
            if (f.canExecute()) return f.getAbsolutePath();
            // Try with extensions
            for (String ext : pathExt.split(";")) {
                f = new File(dir, command + ext.trim());
                if (f.exists()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * LSP server launch spec — mirrors Reasonix's ServerSpec.
     */
    public record ServerSpec(String command, String[] args, String[] env,
                              String languageId, String[] extensions, String installHint) {}
}
