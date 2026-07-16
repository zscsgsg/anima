package com.anima.lsp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.anima.lsp.LspTypes.*;

/**
 * One language server subprocess — initialize it, keep its documents in sync,
 * query definitions/references/hover, and receive diagnostics notifications.
 * Mirrors Reasonix lsp/client.go.
 */
class LspClient implements Closeable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long INIT_TIMEOUT_MS = 30_000;
    private static final long CALL_TIMEOUT_MS = 15_000;
    private static final long DIAG_WAIT_MS = 2_000;

    private final Process process;
    private final LspJsonRpc rpc;
    private final String root;
    private final String langId;
    private String posEnc = ENC_UTF16;

    private final ConcurrentHashMap<String, DocState> docs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<LspTypes.Diagnostic>> diags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> diagVer = new ConcurrentHashMap<>();

    LspClient(String bin, String[] args, String[] env, String langId, String root) throws Exception {
        this.langId = langId;
        this.root = root;

        var pb = new ProcessBuilder();
        var cmd = new ArrayList<String>();
        cmd.add(bin);
        if (args != null) for (String a : args) cmd.add(a);
        pb.command(cmd);
        if (env != null) for (String e : env) pb.environment().put(e.split("=", 2)[0], e.split("=", 2)[1]);
        pb.directory(new File(root));
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        this.process = pb.start();

        this.rpc = new LspJsonRpc(
            process.getInputStream(),
            process.getOutputStream(),
            (method, params) -> { if (method.equals("textDocument/publishDiagnostics")) cacheDiags(params); },
            (id, method, params) -> {
                if (method.equals("workspace/configuration")) {
                    return JSON.createArrayNode(); // empty array reply
                }
                return null;
            }
        );

        // LSP initialize handshake
        initialize();
    }

    private void initialize() throws Exception {
        var params = JSON.createObjectNode();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", LspTypes.pathToUri(root));
        var caps = JSON.createObjectNode();
        var general = JSON.createObjectNode();
        var encs = JSON.createArrayNode();
        encs.add(ENC_UTF8);
        encs.add(ENC_UTF16);
        general.set("positionEncodings", encs);
        caps.set("general", general);
        var textDoc = JSON.createObjectNode();
        textDoc.set("publishDiagnostics", JSON.createObjectNode().put("versionSupport", true));
        textDoc.set("hover", JSON.createObjectNode().set("contentFormat",
            JSON.createArrayNode().add("plaintext").add("markdown")));
        caps.set("textDocument", textDoc);
        params.set("capabilities", caps);

        var resp = rpc.call(INIT_TIMEOUT_MS, "initialize", params);
        if (resp.has("capabilities") && resp.get("capabilities").has("positionEncoding")) {
            posEnc = resp.get("capabilities").get("positionEncoding").asText();
        }
        if (posEnc.isEmpty()) posEnc = ENC_UTF16;
        rpc.notify("initialized", JSON.createObjectNode());
    }

    // ——— document sync ———

    void ensureSynced(String uri, String path) throws Exception {
        var fi = new File(path);
        if (!fi.exists()) throw new IOException("file not found: " + path);
        var state = docs.get(uri);
        if (state != null && fi.length() == state.size && fi.lastModified() == state.mod) return;

        String content = Files.readString(Path.of(path));

        if (state == null) {
            var textDoc = JSON.createObjectNode();
            var td = JSON.createObjectNode();
            td.put("uri", uri);
            td.put("languageId", langId);
            td.put("version", 1);
            td.put("text", content);
            textDoc.set("textDocument", td);
            rpc.notify("textDocument/didOpen", textDoc);
            docs.put(uri, new DocState(1, fi.length(), fi.lastModified()));
        } else {
            int ver = state.version + 1;
            var textDoc = JSON.createObjectNode();
            var td = JSON.createObjectNode();
            td.put("uri", uri);
            td.put("version", ver);
            textDoc.set("textDocument", td);
            var changes = JSON.createArrayNode();
            var change = JSON.createObjectNode();
            change.put("text", content);
            changes.add(change);
            textDoc.set("contentChanges", changes);
            rpc.notify("textDocument/didChange", textDoc);
            docs.put(uri, new DocState(ver, fi.length(), fi.lastModified()));
        }
    }

    int docVersion(String uri) {
        var state = docs.get(uri);
        return state != null ? state.version : 0;
    }

    // ——— diagnostics ———

    private void cacheDiags(ObjectNode params) {
        String uri = params.get("uri").asText();
        var list = new ArrayList<LspTypes.Diagnostic>();
        for (var el : params.get("diagnostics")) {
            var r = parseRange(el.get("range"));
            int sev = el.has("severity") ? el.get("severity").asInt() : 1;
            String msg = el.has("message") ? el.get("message").asText() : "";
            String src = el.has("source") ? el.get("source").asText() : "";
            list.add(new LspTypes.Diagnostic(r, sev, msg, src));
        }
        diags.put(uri, list);
        if (params.has("version")) {
            diagVer.put(uri, params.get("version").asInt());
        } else {
            var state = docs.get(uri);
            if (state != null) diagVer.put(uri, state.version);
        }
    }

    List<LspTypes.Diagnostic> waitDiagnostics(String uri, int minVer) {
        long deadline = System.currentTimeMillis() + DIAG_WAIT_MS;
        while (true) {
            Integer ver = diagVer.get(uri);
            if (ver != null && ver >= minVer) return diags.getOrDefault(uri, List.of());
            if (System.currentTimeMillis() > deadline) return diags.getOrDefault(uri, List.of());
            try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return diags.getOrDefault(uri, List.of());
    }

    // ——— queries ———

    ObjectNode query(String method, String uri, Position pos) throws Exception {
        var params = JSON.createObjectNode();
        var td = JSON.createObjectNode();
        td.put("uri", uri);
        params.set("textDocument", td);
        params.set("position", pos.toJson());
        return callRetry(method, params);
    }

    ObjectNode references(String uri, Position pos) throws Exception {
        var params = JSON.createObjectNode();
        var td = JSON.createObjectNode();
        td.put("uri", uri);
        params.set("textDocument", td);
        params.set("position", pos.toJson());
        var ctx = JSON.createObjectNode();
        ctx.put("includeDeclaration", true);
        params.set("context", ctx);
        return callRetry("textDocument/references", params);
    }

    private ObjectNode callRetry(String method, ObjectNode params) throws Exception {
        for (int i = 0; i < 5; i++) {
            try {
                return rpc.call(CALL_TIMEOUT_MS, method, params);
            } catch (IOException e) {
                if (i >= 4 || !e.getMessage().contains("-32801")) throw e;
                Thread.sleep(400); // ContentModified → retry after reindex
            }
        }
        throw new IOException("LSP retry exhausted: " + method);
    }

    @Override
    public void close() throws IOException {
        try { rpc.call(2000, "shutdown", JSON.createObjectNode()); } catch (Exception ignored) {}
        try { rpc.notify("exit", JSON.createObjectNode()); } catch (Exception ignored) {}
        rpc.close();
        if (process.isAlive()) process.destroyForcibly();
        try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    private record DocState(int version, long size, long mod) {}
}
