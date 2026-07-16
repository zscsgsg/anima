package com.anima.lsp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal JSON-RPC 2.0 transport over LSP Content-Length frames.
 * Mirrors Reasonix lsp/jsonrpc.go — conn + readLoop.
 */
class LspJsonRpc implements Closeable {

    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final int MAX_FRAME_BYTES = 64 << 20; // 64 MiB

    private final OutputStream out;
    private final Object writeLock = new Object();
    private final Map<Long, Incoming> pending = new ConcurrentHashMap<>();
    private long nextId = 0;

    private final Thread readThread;
    private volatile boolean closed;
    private volatile Exception readError;
    private final CountDownLatch readDone = new CountDownLatch(1);

    // Callbacks for server→client messages
    final NotifyHandler onNotify;
    final RequestHandler onRequest;

    interface NotifyHandler {
        void handle(String method, ObjectNode params) throws Exception;
    }

    interface RequestHandler {
        Object handle(long id, String method, ObjectNode params) throws Exception;
    }

    LspJsonRpc(InputStream in, OutputStream out, NotifyHandler onNotify, RequestHandler onRequest) {
        this.out = out;
        this.onNotify = onNotify;
        this.onRequest = onRequest;
        this.readThread = new Thread(() -> readLoop(new BufferedInputStream(in)), "lsp-read");
        this.readThread.setDaemon(true);
        this.readThread.start();
    }

    // ——— call ———

    ObjectNode call(long timeoutMs, String method, ObjectNode params) throws Exception {
        long id;
        synchronized (this) { id = ++nextId; }
        var incoming = new Incoming();
        pending.put(id, incoming);

        var msg = JSON.createObjectNode()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method);
        if (params != null) msg.set("params", params);
        writeMsg(msg);

        try {
            if (!incoming.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pending.remove(id);
                throw new IOException("LSP call timeout: " + method);
            }
        } catch (InterruptedException e) {
            pending.remove(id);
            throw new IOException("LSP call interrupted: " + method, e);
        }
        pending.remove(id);

        if (incoming.error != null) {
            throw new IOException("LSP error " + incoming.error.get("code").asInt()
                + ": " + incoming.error.get("message").asText());
        }
        return incoming.result != null ? (ObjectNode) incoming.result : JSON.createObjectNode();
    }

    // ——— notify ———

    void notify(String method, ObjectNode params) throws Exception {
        var msg = JSON.createObjectNode()
            .put("jsonrpc", "2.0")
            .put("method", method);
        if (params != null) msg.set("params", params);
        writeMsg(msg);
    }

    void reply(long id, ObjectNode result) throws Exception {
        var msg = JSON.createObjectNode()
            .put("jsonrpc", "2.0")
            .put("id", id);
        msg.set("result", result);
        writeMsg(msg);
    }

    // ——— write ———

    private void writeMsg(ObjectNode msg) throws Exception {
        byte[] json = JSON.writeValueAsBytes(msg);
        synchronized (writeLock) {
            var header = ("Content-Length: " + json.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            out.write(header);
            out.write(json);
            out.flush();
        }
    }

    // ——— read loop ———

    @SuppressWarnings("java:S3776")
    private void readLoop(BufferedInputStream in) {
        try {
            while (!closed) {
                byte[] body = readFrame(in);
                if (body == null) break;

                var root = (ObjectNode) JSON.readTree(body);
                var method = root.has("method") ? root.get("method").asText() : null;
                var idNode = root.get("id");

                if (method != null && idNode != null && !idNode.isNull()) {
                    // server→client request
                    if (onRequest != null) {
                        try {
                            var result = onRequest.handle(idNode.asLong(), method,
                                root.has("params") ? (ObjectNode) root.get("params") : null);
                            if (result != null) reply(idNode.asLong(), (ObjectNode) result);
                        } catch (Exception ignored) {
                            reply(idNode.asLong(), JSON.createObjectNode().put("result", (String) null));
                        }
                    }
                } else if (method != null) {
                    // server notification (e.g. publishDiagnostics)
                    if (onNotify != null) {
                        try {
                            onNotify.handle(method,
                                root.has("params") ? (ObjectNode) root.get("params") : null);
                        } catch (Exception ignored) { /* drop malformed notifications */ }
                    }
                } else if (idNode != null && !idNode.isNull()) {
                    // response to one of our calls
                    long id = idNode.asLong();
                    var inc = pending.get(id);
                    if (inc != null) {
                        inc.result = root.get("result");
                        inc.error = root.has("error") ? (ObjectNode) root.get("error") : null;
                        inc.latch.countDown();
                    }
                }
            }
        } catch (Exception e) {
            readError = e;
            // Release all pending calls
            for (var inc : pending.values()) {
                inc.latch.countDown();
            }
        } finally {
            readDone.countDown();
        }
    }

    private byte[] readFrame(BufferedInputStream in) throws IOException {
        int n = -1;
        while (true) {
            String line = readLine(in);
            if (line == null) return null;
            if (line.isEmpty()) break;
            if (line.startsWith("Content-Length:")) {
                try {
                    n = Integer.parseInt(line.substring("Content-Length:".length()).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("Bad Content-Length: " + line, e);
                }
            }
        }
        if (n < 0) throw new IOException("Missing Content-Length header");
        if (n > MAX_FRAME_BYTES) throw new IOException("Content-Length " + n + " exceeds " + MAX_FRAME_BYTES + "-byte cap");
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("Unexpected EOF in LSP frame");
            off += r;
        }
        return buf;
    }

    private String readLine(BufferedInputStream in) throws IOException {
        var sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) return sb.isEmpty() ? null : sb.toString();
            if (c == '\r') {
                in.mark(1);
                if (in.read() != '\n') in.reset();
                return sb.toString();
            }
            if (c == '\n') return sb.toString();
            sb.append((char) c);
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        try { readDone.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        try { out.close(); } catch (Exception ignored) {}
        try { readThread.interrupt(); } catch (Exception ignored) {}
    }

    private static class Incoming {
        final CountDownLatch latch = new CountDownLatch(1);
        com.fasterxml.jackson.databind.JsonNode result;
        ObjectNode error;
    }
}
