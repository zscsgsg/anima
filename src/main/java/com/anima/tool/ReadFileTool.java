package com.anima.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a file with line numbers (like Reasonix: "   42→text").
 * Line numbers make whitespace/indentation visible so edit_file can copy exact text.
 * Supports offset/limit for paging large files.
 */
public class ReadFileTool implements Tool {

    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_CONTENT = 100_000; // hard cap on returned chars

    @Override
    public String name() { return "read_file"; }

    @Override public boolean isReadOnly() { return true; }

    @Override
    public String description() {
        return "Read a file with line numbers (e.g. '   42→text'). Lines are 1-based. " +
               "The line number prefix makes whitespace and indentation clearly visible " +
               "so you can copy exact text for edit_file. Use offset/limit to page large files.";
    }

    @Override
    public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "file_path": {
              "type": "string",
              "description": "Relative or absolute path to the file (alias: path)"
            },
            "path": {
              "type": "string",
              "description": "Relative or absolute path to the file (alias: file_path)"
            },
            "offset": {
              "type": "integer",
              "description": "1-based line number to start reading from (default: 1)",
              "minimum": 1
            },
            "limit": {
              "type": "integer",
              "description": "Maximum lines to return (default: 2000)",
              "minimum": 1
            }
          }
        }
        """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        String pathStr = json.has("path") ? json.get("path").asText() :
                         json.has("file_path") ? json.get("file_path").asText() :
                         json.has("file") ? json.get("file").asText() : null;
        if (pathStr == null) {
            return "Error: missing 'path', 'file_path', or 'file' argument";
        }
        int offset = json.has("offset") ? json.get("offset").asInt() : 1;
        int limit = json.has("limit") ? json.get("limit").asInt() : DEFAULT_LIMIT;
        if (offset < 1) offset = 1;
        if (limit < 1) limit = DEFAULT_LIMIT;

        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(pathStr).normalize();
        }
        if (!Files.exists(path)) {
            return "Error: file not found: " + path;
        }
        if (Files.isDirectory(path)) {
            return "Error: " + path.getFileName() + " is a directory, not a file — use ls to list it";
        }

        // Read with lenient UTF-8 — replace malformed bytes instead of crashing.
        // Windows files may contain non-UTF-8 sequences (GBK leftovers, em-dash corruption).
        String content;
        try {
            content = Files.readString(path);
        } catch (java.nio.charset.MalformedInputException | java.nio.charset.UnmappableCharacterException e) {
            // Fall back to lenient reading: replace invalid bytes with replacement char
            byte[] raw = Files.readAllBytes(path);
            content = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                .decode(java.nio.ByteBuffer.wrap(raw)).toString();
        }

        String[] allLines = content.split("\n", -1);
        int totalLines = allLines.length;
        if (totalLines == 0) return "(empty file)";
        if (offset > totalLines) return "(offset " + offset + " is past EOF — file has " + totalLines + " lines)";

        int startIdx = offset - 1; // 0-based
        int endIdx = Math.min(startIdx + limit, totalLines);

        // Calculate width for line number padding (e.g. 4 chars for lines 1-9999)
        int maxLineNum = offset + (endIdx - startIdx) - 1;
        int width = String.valueOf(Math.max(maxLineNum, 1)).length();

        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < endIdx; i++) {
            int lineNum = i + 1;
            String prefix = String.format("%" + width + "d→", lineNum);
            sb.append(prefix).append(allLines[i]).append('\n');
        }

        if (endIdx < totalLines) {
            sb.append("\n[more lines below; pass offset=").append(endIdx + 1).append(" to continue, total ").append(totalLines).append(" lines]");
        }

        String result = sb.toString();
        if (result.length() > MAX_CONTENT) {
            result = result.substring(0, MAX_CONTENT) + "\n... (truncated at " + MAX_CONTENT + " chars)";
        }
        return result;
    }
}
