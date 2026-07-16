package com.anima.plugin;

import com.anima.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP (Model Context Protocol) plugin host — connects to external MCP servers
 * via stdio JSON-RPC and adapts their tools to the Tool interface.
 * Mirrors Reasonix's {@code internal/plugin/plugin.go Host}.
 *
 * <p>Configuration: reads Claude Code-compatible .mcp.json from workspace root.
 */
public class McpHost implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final int DEFAULT_CALL_TIMEOUT_SEC = 60;
    private static final int DEFAULT_START_TIMEOUT_SEC = 10;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();
    private final List<McpTool> pluginTools = new CopyOnWriteArrayList<>();
    private final List<McpPrompt> prompts = new CopyOnWriteArrayList<>();
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<Void>> spawnGuards = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private Path cacheDir;
    private Path workspaceDir;

    /** An MCP prompt discovered from a server, surfaced as a slash command. */
    public record McpPrompt(String serverName, String name, String description) {}

    /** Start MCP servers from workspace .mcp.json config with lazy loading + cache. */
    public static McpHost fromWorkspace(Path workspaceDir) throws IOException {
        McpHost host = new McpHost();
        host.workspaceDir = workspaceDir;
        host.cacheDir = workspaceDir.resolve(".anima").resolve("mcp-cache");
        Files.createDirectories(host.cacheDir);

        Path mcpJson = workspaceDir.resolve(".mcp.json");
        if (Files.exists(mcpJson)) {
            List<McpSpec> specs = parseMcpJson(mcpJson);
            ExecutorService bg = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mcp-lazy-" + r.hashCode());
                t.setDaemon(true); return t;
            });
            for (McpSpec spec : specs) {
                // Try cache first for instant tool registration
                List<McpTool> cachedTools = host.loadCache(spec);
                if (!cachedTools.isEmpty()) {
                    for (var t : cachedTools) host.pluginTools.add(t);
                }
                // Kick lazy spawn in background
                bg.submit(() -> {
                    try { host.connectLazy(spec); }
                    catch (Exception e) { host.failures.add(spec.name() + ": " + e.getMessage()); }
                });
            }
            bg.shutdown();
        }
        return host;
    }

    /** Number of connected servers. */
    public int serverCount() { return connections.size(); }

    /** All discovered tools from connected MCP servers. */
    public List<McpTool> tools() { return new ArrayList<>(pluginTools); }

    /** Connected server names. */
    public List<String> serverNames() {
        return new ArrayList<>(connections.keySet());
    }

    /** Startup failures. */
    public List<String> failures() { return new ArrayList<>(failures); }

    /** All discovered MCP prompts (surfaced as slash commands). */
    public List<McpPrompt> prompts() { return new ArrayList<>(prompts); }

    /** Connect to one MCP server. Supports stdio and http/streamable-http transports. */
    public void connect(McpSpec spec) throws IOException {
        if (connections.containsKey(spec.name())) {
            throw new IOException("MCP server '" + spec.name() + "' already connected");
        }
        String type = spec.type() != null ? spec.type().toLowerCase() : "stdio";
        var conn = switch (type) {
            case "http", "streamable-http", "sse" -> new HttpMcpConnection(spec);
            default -> new StdioMcpConnection(spec);
        };
        conn.initialize();
        var tools = conn.listTools();
        for (var tool : tools) {
            pluginTools.add(tool);
        }
        // Discover prompts
        try {
            var discoveredPrompts = conn.listPrompts();
            for (var p : discoveredPrompts) {
                prompts.add(new McpPrompt(spec.name(), p.name(), p.description()));
            }
        } catch (Exception ignored) {
            // Prompts are optional
        }
        connections.put(spec.name(), conn);
    }

    /** Disconnect a server by name. */
    public void disconnect(String name) {
        var conn = connections.remove(name);
        if (conn != null) {
            pluginTools.removeIf(t -> t.parentName().equals(name));
            try { conn.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public void close() {
        closed = true;
        for (var conn : connections.values()) { try { conn.close(); } catch (IOException ignored) {} }
        connections.clear();
        pluginTools.clear();
    }

    // ── MCP JSON config parsing ──

    private static List<McpSpec> parseMcpJson(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        JsonNode root = MAPPER.readTree(content);
        JsonNode servers = root.get("mcpServers");
        if (servers == null || !servers.isObject()) return List.of();

        List<McpSpec> specs = new ArrayList<>();
        var names = new ArrayList<String>();
        servers.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);

        for (String name : names) {
            JsonNode s = servers.get(name);
            String type = s.has("type") ? s.get("type").asText() : "stdio";
            String command = s.has("command") ? s.get("command").asText() : "";
            String url = s.has("url") ? s.get("url").asText() : "";
            // HTTP servers: use URL as command
            if ("http".equals(type) || "streamable-http".equals(type) || "sse".equals(type)) {
                command = url.isEmpty() ? command : url;
            }
            List<String> args = new ArrayList<>();
            if (s.has("args") && s.get("args").isArray()) {
                for (JsonNode a : s.get("args")) args.add(a.asText());
            }
            Map<String, String> env = new LinkedHashMap<>();
            if (s.has("env") && s.get("env").isObject()) {
                var envFields = new ArrayList<String>();
                s.get("env").fieldNames().forEachRemaining(envFields::add);
                for (String k : envFields) env.put(k, s.get("env").get(k).asText());
            }
            specs.add(new McpSpec(name, type, command, args, env));
        }
        return specs;
    }

    // ── Inner types ──

    /** MCP server specification. */
    public record McpSpec(String name, String type, String command,
                           List<String> args, Map<String, String> env) {}

    /** MCP tool adapter implementing the Tool interface. */
    public record McpTool(String parentName, String toolName, String description,
                           String schema, McpConnection connection) implements Tool {
        @Override public String name() { return toolName; }
        @Override public String description() { return description; }
        @Override public String schema() { return schema; }
        @Override public boolean isReadOnly() { return false; }

        @Override
        public String execute(String arguments) throws Exception {
            return connection.callTool(toolName, arguments);
        }
    }

    /** Represents a single MCP prompt. */
    record PromptInfo(String name, String description) {}

    // ── Connection interface ──

    interface McpConnection extends Closeable {
        void initialize() throws IOException;
        List<McpTool> listTools() throws IOException;
        default List<PromptInfo> listPrompts() throws IOException { return List.of(); }
        String callTool(String toolName, String arguments) throws IOException;
    }

    /** Stdio JSON-RPC 2.0 connection (subprocess). */
    class StdioMcpConnection implements McpConnection {
        private final McpSpec spec;
        private Process process;
        private BufferedWriter stdin;
        private BufferedReader stdout;
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private volatile boolean closed;

        StdioMcpConnection(McpSpec spec) { this.spec = spec; }

        @Override
        public void initialize() throws IOException {
            List<String> cmd = new ArrayList<>();
            cmd.add(spec.command());
            cmd.addAll(spec.args());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            spec.env().forEach(pb.environment()::put);
            pb.redirectErrorStream(false);
            process = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            Thread reader = new Thread(this::readLoop, "mcp-" + spec.name());
            reader.setDaemon(true);
            reader.start();

            sendRequest("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "Anima", "version", "0.15.0")
            ));
            sendNotification("notifications/initialized", Map.of());
        }

        @Override public List<McpTool> listTools() throws IOException { return discoverTools(); }
        @Override public String callTool(String toolName, String arguments) throws IOException { return invokeTool(toolName, arguments); }

        private List<McpTool> discoverTools() throws IOException {
            JsonNode result = sendRequest("tools/list", Map.of());
            JsonNode tools = result.get("tools");
            if (tools == null || !tools.isArray()) return List.of();
            List<McpTool> list = new ArrayList<>();
            for (JsonNode t : tools) {
                String name = t.get("name").asText();
                String desc = t.has("description") ? t.get("description").asText() : "";
                String schema = t.has("inputSchema") ? t.get("inputSchema").toString() : "{}";
                list.add(new McpTool(spec.name(), "mcp__" + spec.name() + "__" + name, desc, schema, this));
            }
            return list;
        }

        @Override public List<PromptInfo> listPrompts() throws IOException {
            try {
                JsonNode result = sendRequest("prompts/list", Map.of());
                JsonNode prompts = result.get("prompts");
                if (prompts == null || !prompts.isArray()) return List.of();
                List<PromptInfo> list = new ArrayList<>();
                for (JsonNode p : prompts) {
                    list.add(new PromptInfo(p.get("name").asText(),
                        p.has("description") ? p.get("description").asText() : ""));
                }
                return list;
            } catch (Exception e) { return List.of(); }
        }

        private String invokeTool(String toolName, String arguments) throws IOException {
            String prefix = "mcp__" + spec.name() + "__";
            String originalName = toolName.startsWith(prefix) ? toolName.substring(prefix.length()) : toolName;
            JsonNode result = sendRequest("tools/call", Map.of("name", originalName, "arguments", MAPPER.readTree(arguments)));
            if (result.has("content") && result.get("content").isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode c : result.get("content")) { if (c.has("text")) sb.append(c.get("text").asText()); }
                return sb.toString();
            }
            return result.toString();
        }

        JsonNode sendRequest(String method, Object params) throws IOException {
            int id = nextId.getAndIncrement();
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0"); request.put("id", id); request.put("method", method);
            if (params != null) request.set("params", MAPPER.valueToTree(params));
            var future = new CompletableFuture<JsonNode>();
            pending.put(id, future);
            synchronized (stdin) { stdin.write(request.toString().replace("\n", "")); stdin.newLine(); stdin.flush(); }
            try { return future.get(DEFAULT_CALL_TIMEOUT_SEC, TimeUnit.SECONDS); }
            catch (TimeoutException e) { pending.remove(id); throw new IOException("MCP call timeout"); }
            catch (Exception e) { pending.remove(id); throw new IOException("MCP call interrupted", e); }
        }

        void sendNotification(String method, Object params) throws IOException {
            ObjectNode notif = MAPPER.createObjectNode();
            notif.put("jsonrpc", "2.0"); notif.put("method", method);
            if (params != null) notif.set("params", MAPPER.valueToTree(params));
            synchronized (stdin) { stdin.write(notif.toString().replace("\n", "")); stdin.newLine(); stdin.flush(); }
        }

        private void readLoop() {
            try { String line; while ((line = stdout.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode msg = MAPPER.readTree(line);
                if (msg.has("id")) { var f = pending.remove(msg.get("id").asInt());
                    if (f != null) f.complete(msg.has("error") ? null : msg.get("result")); }
            }} catch (IOException e) { if (!closed) pending.values().forEach(f -> f.completeExceptionally(e)); pending.clear(); }
        }

        @Override public void close() { closed = true; try { stdin.close(); } catch (Exception ignored) {} try { stdout.close(); } catch (Exception ignored) {} if (process != null) process.destroy(); }
    }

    /** HTTP Streamable JSON-RPC 2.0 connection. */
    class HttpMcpConnection implements McpConnection {
        private final McpSpec spec;
        private final AtomicInteger nextId = new AtomicInteger(1);
        private String sessionId;
        private volatile boolean closed;
        private java.net.URL url;

        HttpMcpConnection(McpSpec spec) { this.spec = spec; }

        @Override public void initialize() throws IOException {
            try {
                url = URI.create(spec.command().isEmpty() ? "http://localhost:8080" : spec.command()).toURL();
                sendRequest("initialize", Map.of("protocolVersion", PROTOCOL_VERSION, "capabilities", Map.of(), "clientInfo", Map.of("name", "Anima", "version", "0.15.0")));
                sendNotification("notifications/initialized", Map.of());
            } catch (Exception e) { throw new IOException("HTTP MCP init failed: " + e.getMessage(), e); }
        }

        @Override public List<McpTool> listTools() throws IOException {
            JsonNode result = sendRequest("tools/list", Map.of());
            JsonNode tools = result.get("tools");
            if (tools == null || !tools.isArray()) return List.of();
            List<McpTool> list = new ArrayList<>();
            for (JsonNode t : tools) {
                String name = t.get("name").asText();
                String desc = t.has("description") ? t.get("description").asText() : "";
                String schema = t.has("inputSchema") ? t.get("inputSchema").toString() : "{}";
                list.add(new McpTool(spec.name(), "mcp__" + spec.name() + "__" + name, desc, schema, this));
            }
            return list;
        }

        @Override public String callTool(String toolName, String arguments) throws IOException {
            String prefix = "mcp__" + spec.name() + "__";
            String originalName = toolName.startsWith(prefix) ? toolName.substring(prefix.length()) : toolName;
            JsonNode result = sendRequest("tools/call", Map.of("name", originalName, "arguments", MAPPER.readTree(arguments)));
            if (result.has("content") && result.get("content").isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode c : result.get("content")) { if (c.has("text")) sb.append(c.get("text").asText()); }
                return sb.toString();
            }
            return result.toString();
        }

        JsonNode sendRequest(String method, Object params) throws IOException {
            int id = nextId.getAndIncrement();
            ObjectNode body = MAPPER.createObjectNode();
            body.put("jsonrpc", "2.0"); body.put("id", id); body.put("method", method);
            if (params != null) body.set("params", MAPPER.valueToTree(params));
            try {
                var builder = HttpRequest.newBuilder().uri(url.toURI())
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .timeout(Duration.ofSeconds(DEFAULT_CALL_TIMEOUT_SEC))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
                if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
                HttpResponse<String> resp = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                String sid = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
                if (sid != null) sessionId = sid;
                JsonNode r = MAPPER.readTree(resp.body());
                if (r.has("error")) throw new IOException("MCP error: " + r.get("error"));
                return r.get("result");
            } catch (Exception e) {
                throw new IOException("HTTP MCP request failed: " + e.getMessage(), e);
            }
        }

        void sendNotification(String method, Object params) throws IOException {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("jsonrpc", "2.0"); body.put("method", method);
            if (params != null) body.set("params", MAPPER.valueToTree(params));
            try {
                var builder = HttpRequest.newBuilder().uri(url.toURI())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
                HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {}
        }

        @Override public void close() { closed = true; }
    }

    // ── Schema cache ──

    private String specFingerprint(McpSpec spec) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(("type:" + spec.type() + "\n").getBytes());
            md.update(("cmd:" + spec.command() + "\n").getBytes());
            for (var a : spec.args()) md.update(("arg:" + a + "\n").getBytes());
            var keys = new ArrayList<>(spec.env().keySet());
            Collections.sort(keys);
            for (var k : keys) md.update(("env:" + k + "=" + spec.env().get(k) + "\n").getBytes());
            return bytesToHex(md.digest());
        } catch (Exception e) { return ""; }
    }

    private List<McpTool> loadCache(McpSpec spec) {
        if (cacheDir == null) return List.of();
        String hash = specFingerprint(spec);
        if (hash.isEmpty()) return List.of();
        Path cacheFile = cacheDir.resolve(spec.name() + ".json");
        if (!Files.exists(cacheFile)) return List.of();
        try {
            JsonNode cached = MAPPER.readTree(Files.readString(cacheFile));
            if (!hash.equals(cached.has("hash") ? cached.get("hash").asText() : "")) return List.of();
            JsonNode tools = cached.get("tools");
            if (tools == null || !tools.isArray()) return List.of();
            List<McpTool> list = new ArrayList<>();
            for (JsonNode t : tools) {
                String name = t.get("name").asText();
                String desc = t.has("description") ? t.get("description").asText() : "";
                String schema = t.has("schema") ? t.get("schema").toString() : "{}";
                String nsName = "mcp__" + spec.name() + "__" + name;
                list.add(new McpTool(spec.name(), nsName, desc, schema, null));
            }
            return list;
        } catch (Exception e) { return List.of(); }
    }

    private void saveCache(McpSpec spec, List<McpTool> tools) {
        if (cacheDir == null) return;
        String hash = specFingerprint(spec);
        if (hash.isEmpty()) return;
        try {
            var arr = MAPPER.createArrayNode();
            for (var t : tools) {
                if (t.connection() == null) continue;
                var obj = MAPPER.createObjectNode();
                String raw = t.toolName().replace("mcp__" + spec.name() + "__", "");
                obj.put("name", raw);
                obj.put("description", t.description());
                obj.set("schema", MAPPER.readTree(t.schema()));
                arr.add(obj);
            }
            var root = MAPPER.createObjectNode();
            root.put("hash", hash);
            root.set("tools", arr);
            Files.writeString(cacheDir.resolve(spec.name() + ".json"), root.toString());
        } catch (Exception ignored) {}
    }

    /** Lazy-connect with spawn guard. */
    void connectLazy(McpSpec spec) {
        String name = spec.name();
        // Spawn guard: only one goroutine per server
        var existing = spawnGuards.putIfAbsent(name, new CompletableFuture<>());
        if (existing != null) return; // another goroutine is handling it
        try {
            if (closed) return;
            connect(spec);
            // Save cache after successful connect
            var serverTools = pluginTools.stream()
                .filter(t -> t.parentName().equals(name)).toList();
            saveCache(spec, serverTools);
            spawnGuards.get(name).complete(null);
        } catch (Exception e) {
            failures.add(name + ": " + e.getMessage());
            spawnGuards.get(name).completeExceptionally(e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
