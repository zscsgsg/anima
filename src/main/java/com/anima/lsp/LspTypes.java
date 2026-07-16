package com.anima.lsp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LSP types — Position, Range, Location, Diagnostic, plus URI utils.
 * Mirrors Reasonix lsp/position.go + lsp/results.go.
 */
final class LspTypes {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final String ENC_UTF8 = "utf-8";
    static final String ENC_UTF16 = "utf-16";

    // ——— types ———

    record Position(int line, int character) {
        ObjectNode toJson() {
            return JSON.createObjectNode().put("line", line).put("character", character);
        }
    }

    record Range(Position start, Position end) {
        ObjectNode toJson() {
            var node = JSON.createObjectNode();
            node.set("start", start.toJson());
            node.set("end", end.toJson());
            return node;
        }
    }

    record Location(String uri, Range range) {}

    record Diagnostic(Range range, int severity, String message, String source) {}

    // ——— URI utils ———

    static String pathToUri(String p) {
        Path path = Paths.get(p).normalize();
        return path.toUri().toString();
    }

    static String uriToPath(String uri) {
        try {
            return Paths.get(new URI(uri)).toString();
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    // ——— locate: find symbol on a line, return LSP Position ———

    static Position locate(String content, int line1, String symbol, String enc) {
        String[] lines = content.split("\n", -1);
        if (line1 < 1 || line1 > lines.length) {
            throw new IllegalArgumentException("line " + line1 + " out of range (file has " + lines.length + " lines)");
        }
        String text = lines[line1 - 1].replace("\r", "");
        int col = text.indexOf(symbol);
        if (col < 0) {
            throw new IllegalArgumentException("symbol '" + symbol + "' not found on line " + line1);
        }
        return new Position(line1 - 1, encodeChar(text.substring(0, col), enc));
    }

    static int encodeChar(String prefix, String enc) {
        if (ENC_UTF8.equals(enc)) return prefix.getBytes(StandardCharsets.UTF_8).length;
        // Java String is internally UTF-16; codePointCount gives correct value
        return prefix.codePointCount(0, prefix.length());
    }

    // ——— result parsing ———

    static List<Location> parseLocations(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.asText().isEmpty()) return List.of();

        if (raw.isArray()) {
            ArrayNode arr = (ArrayNode) raw;
            if (arr.size() == 0) return List.of();

            // Try Location[]
            var first = arr.get(0);
            if (first.has("uri") && !first.get("uri").isNull()) {
                var out = new ArrayList<Location>();
                for (var el : arr) {
                    out.add(parseLocation(el));
                }
                return out;
            }
            // Try LocationLink[]
            if (first.has("targetUri")) {
                var out = new ArrayList<Location>();
                for (var el : arr) {
                    String uri = el.get("targetUri").asText();
                    Range range = parseRange(el.get("targetRange"));
                    out.add(new Location(uri, range));
                }
                return out;
            }
            return List.of();
        }

        // Single Location
        if (raw.has("uri") && !raw.get("uri").isNull()) {
            return List.of(parseLocation(raw));
        }
        return List.of();
    }

    private static Location parseLocation(JsonNode node) {
        return new Location(node.get("uri").asText(), parseRange(node.get("range")));
    }

    static Range parseRange(JsonNode node) {
        var start = node.get("start");
        var end = node.get("end");
        return new Range(
            new Position(start.get("line").asInt(), start.get("character").asInt()),
            new Position(end.get("line").asInt(), end.get("character").asInt())
        );
    }

    static String parseHover(JsonNode raw) {
        if (raw == null || !raw.has("contents")) return "";
        return markedToText(raw.get("contents"));
    }

    private static String markedToText(JsonNode raw) {
        if (raw == null || raw.isNull()) return "";
        if (raw.isTextual()) return raw.asText();
        if (raw.isObject()) {
            if (raw.has("value") && !raw.get("value").isNull()) return raw.get("value").asText();
        }
        if (raw.isArray()) {
            var sb = new StringBuilder();
            for (var el : raw) {
                String t = markedToText(el);
                if (!t.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return "";
    }

    // ——— diagnostics formatting ———

    private static final Map<Integer, String> SEVERITY = Map.of(
        1, "error", 2, "warning", 3, "info", 4, "hint"
    );

    static String formatDiagnostics(String relPath, List<Diagnostic> diags) {
        if (diags.isEmpty()) return "no diagnostics for " + relPath;
        var sb = new StringBuilder(diags.size() + " diagnostic(s) in " + relPath + ":\n");
        for (var d : diags) {
            String sev = SEVERITY.getOrDefault(d.severity(), "error");
            String src = d.source() != null && !d.source().isEmpty() ? " [" + d.source() + "]" : "";
            sb.append(String.format("%d:%d %s%s %s\n",
                d.range().start().line() + 1, d.range().start().character() + 1,
                sev, src, d.message().trim()));
        }
        return sb.toString().stripTrailing();
    }

    // ——— location formatting ———

    static String formatLocations(String wsRoot, String kind, List<Location> locs) throws IOException {
        if (locs.isEmpty()) return "no " + kind + " found";
        // Sort by URI then line
        locs.sort((a, b) -> {
            int cmp = a.uri().compareTo(b.uri());
            return cmp != 0 ? cmp : Integer.compare(a.range().start().line(), b.range().start().line());
        });
        var sb = new StringBuilder(locs.size() + " " + kind + "(s):\n");
        Path wsPath = Paths.get(wsRoot).normalize();
        for (var loc : locs) {
            String path = uriToPath(loc.uri());
            int line = loc.range().start().line() + 1;
            String rel = relPath(wsPath, Paths.get(path));
            sb.append(rel).append(":").append(line);
            String snippet = readLine(path, loc.range().start().line());
            if (snippet != null && !snippet.isEmpty()) {
                sb.append("  ").append(snippet.trim());
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String relPath(Path wsRoot, Path path) {
        try {
            Path rel = wsRoot.relativize(path);
            return rel.toString().replace("\\", "/");
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    private static String readLine(String path, int line0) {
        try {
            String content = Files.readString(Paths.get(path));
            String[] lines = content.split("\n", -1);
            if (line0 >= 0 && line0 < lines.length) {
                return lines[line0].trim();
            }
        } catch (IOException ignored) {}
        return "";
    }
}
