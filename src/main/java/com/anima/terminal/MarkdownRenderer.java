package com.anima.terminal;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.regex.Pattern;

/**
 * Streaming Markdown renderer with code block detection + syntax highlighting.
 */
public class MarkdownRenderer {

    private static final Pattern CODE_START = Pattern.compile("```\\s*(\\w+)?\\s*");
    private static final Pattern CODE_END   = Pattern.compile("```\\s*");

    private final TerminalWriter out;
    private boolean inCodeBlock = false;
    private String codeLang = "";
    private StringBuilder codeBuffer = new StringBuilder();

    public MarkdownRenderer(TerminalWriter out) { this.out = out; }

    /**
     * Feed a streaming token. Handles code block detection inline.
     */
    public void feed(String token) {
        if (inCodeBlock) {
            codeBuffer.append(token);
            // Check if code block ends
            int endIdx = codeBuffer.indexOf("\n```");
            if (endIdx == -1) endIdx = codeBuffer.indexOf("```");
            if (endIdx >= 0) {
                String code = codeBuffer.substring(0, endIdx);
                codeBuffer.setLength(0);
                inCodeBlock = false;
                flushCodeBlock(code);
                // echo remaining text after ```
                String after = codeBuffer.toString()
                    .replaceFirst("```\\s*", "");
                codeBuffer.setLength(0);
                if (!after.isEmpty()) feed(after);
            }
            return;
        }

        // Normal mode: detect ``` start
        codeBuffer.append(token);
        int startIdx = codeBuffer.indexOf("```");
        if (startIdx >= 0) {
            // Echo text before ```
            String before = codeBuffer.substring(0, startIdx);
            if (!before.isEmpty()) out.respondStream(before);

            // Parse language
            String rest = codeBuffer.substring(startIdx + 3);
            var m = CODE_START.matcher(rest);
            if (m.find()) {
                codeLang = m.group(1) != null ? m.group(1) : "";
                rest = rest.substring(m.end());
            }
            codeBuffer.setLength(0);
            codeBuffer.append(rest);
            inCodeBlock = true;
        } else {
            // Flush buffer periodically to keep streaming smooth
            out.respondStream(token);
            codeBuffer.setLength(0);
        }
    }

    /** Called when streaming ends. Flush any remaining buffer. */
    public void flush() {
        if (inCodeBlock) {
            flushCodeBlock(codeBuffer.toString());
            codeBuffer.setLength(0);
            inCodeBlock = false;
        } else if (codeBuffer.length() > 0) {
            out.respondStream(codeBuffer.toString());
            codeBuffer.setLength(0);
        }
    }

    private void flushCodeBlock(String code) {
        // Border top
        String lang = codeLang.isEmpty() ? "" : " " + codeLang;
        printDim(" ╭──" + lang + " ─" + "─".repeat(60) + "╮");

        // Highlight lines
        for (String line : code.split("\n", -1)) {
            printDim(" │ ");
            if (codeLang.contains("java")) highlightJava(line);
            else if (codeLang.contains("xml") || codeLang.contains("html")) highlightXml(line);
            else if (codeLang.contains("json") || codeLang.contains("yaml")) highlightJson(line);
            else highlightPlain(line);
            out.printRaw("\n");
        }

        // Border bottom
        printDim(" ╰" + "─".repeat(65) + "╯");
        codeLang = "";
    }

    // ── Highlighters ──

    private static final String[] JAVA_KW = {
        "abstract","assert","boolean","break","byte","case","catch","char","class",
        "const","continue","default","do","double","else","enum","extends","final",
        "finally","float","for","goto","if","implements","import","instanceof","int",
        "interface","long","native","new","package","private","protected","public",
        "return","short","static","strictfp","super","switch","synchronized","this",
        "throw","throws","transient","try","void","volatile","while","var","record",
        "sealed","permits","yield"
    };
    private static final String[] XML_TAGS = {
        "project","groupId","artifactId","version","name","description","properties",
        "dependencies","dependency","dependencyManagement","build","plugin","plugins",
        "modelVersion","packaging","scope","configuration","execution","executions",
        "parent","modules","type","classifier","optional","exclusions","exclusion"
    };

    private void highlightJava(String line) {
        // Split by token boundaries
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);

            // String literal
            if (c == '"') {
                int end = line.indexOf('"', i + 1);
                if (end == -1) { out.printStyled(line.substring(i), color(AttributedStyle.GREEN)); break; }
                out.printStyled(line.substring(i, end + 1), color(AttributedStyle.GREEN));
                i = end + 1;
                continue;
            }

            // Comment
            if (c == '/' && i + 1 < line.length()) {
                if (line.charAt(i + 1) == '/') {
                    out.printStyled(line.substring(i), color(AttributedStyle.WHITE + 8));
                    return;
                }
                if (line.charAt(i + 1) == '*') {
                    int end = line.indexOf("*/", i + 2);
                    if (end == -1) { out.printStyled(line.substring(i), color(AttributedStyle.WHITE + 8)); return; }
                    out.printStyled(line.substring(i, end + 2), color(AttributedStyle.WHITE + 8));
                    i = end + 2;
                    continue;
                }
            }

            // Annotation
            if (c == '@' && (i == 0 || !Character.isJavaIdentifierPart(line.charAt(i - 1)))) {
                int end = i + 1;
                while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) end++;
                out.printStyled(line.substring(i, end), color(AttributedStyle.CYAN));
                i = end;
                continue;
            }

            // Number
            if (Character.isDigit(c) && (i == 0 || !Character.isJavaIdentifierPart(line.charAt(i - 1)))) {
                int end = i;
                while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.' || line.charAt(end) == '_' || line.charAt(end) == 'L' || line.charAt(end) == 'f')) end++;
                out.printStyled(line.substring(i, end), color(AttributedStyle.MAGENTA));
                i = end;
                continue;
            }

            // Keyword or identifier
            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) end++;
                String word = line.substring(i, end);
                boolean isKw = false;
                for (String kw : JAVA_KW) { if (kw.equals(word)) { isKw = true; break; } }
                out.printStyled(word, isKw ? color(AttributedStyle.BLUE).bold() : color(AttributedStyle.WHITE));
                i = end;
                continue;
            }

            out.printRaw(String.valueOf(c));
            i++;
        }
    }

    private void highlightXml(String line) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '<') {
                int end = line.indexOf('>', i + 1);
                if (end == -1) { out.printStyled(line.substring(i), color(AttributedStyle.YELLOW)); break; }
                String tag = line.substring(i, end + 1);
                // Check for known tag
                String tagName = tag.replaceAll("[</>?]", "").replaceAll("\\s.*", "");
                boolean known = false;
                for (String t : XML_TAGS) { if (t.equals(tagName)) { known = true; break; } }
                out.printStyled(tag, color(known ? AttributedStyle.CYAN : AttributedStyle.YELLOW));
                i = end + 1;
                continue;
            }
            if (c == '"') {
                int end = line.indexOf('"', i + 1);
                if (end == -1) { out.printRaw(line.substring(i)); break; }
                out.printStyled(line.substring(i, end + 1), color(AttributedStyle.GREEN));
                i = end + 1;
                continue;
            }
            out.printRaw(String.valueOf(c));
            i++;
        }
    }

    private void highlightJson(String line) {
        // Simple: keys in blue, strings in green, numbers in magenta
        boolean inString = false;
        boolean afterColon = false;
        StringBuilder buf = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"' && !inString) {
                inString = true; buf.setLength(0);
            } else if (c == '"' && inString) {
                String val = buf.toString();
                out.printStyled("\"" + val + "\"", afterColon ? color(AttributedStyle.GREEN) : color(AttributedStyle.BLUE));
                inString = false; afterColon = false; buf.setLength(0);
            } else if (inString) {
                buf.append(c);
            } else if (c == ':') {
                out.printRaw(":");
                afterColon = true;
            } else {
                if (Character.isDigit(c)) out.printStyled(String.valueOf(c), color(AttributedStyle.MAGENTA));
                else out.printRaw(String.valueOf(c));
            }
        }
        if (inString && buf.length() > 0) {
            out.printStyled("\"" + buf + "\"", color(AttributedStyle.GREEN));
        }
    }

    private void highlightPlain(String line) { out.printRaw(line); }

    // ── Helpers ──

    private void printDim(String text) {
        out.printStyled(text, color(AttributedStyle.WHITE + 8));
    }

    private static AttributedStyle color(int col) {
        return AttributedStyle.DEFAULT.foreground(col);
    }
}
