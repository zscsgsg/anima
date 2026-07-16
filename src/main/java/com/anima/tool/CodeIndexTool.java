package com.anima.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/**
 * Lightweight built-in code symbol index. Supports 14 languages via regex +
 * Go AST parsing (when available). The model can use this to find where a
 * class, method, type, or function is defined without reading every file.
 *
 * <p>Two actions:
 * <ul>
 *   <li>{@code outline} — list all symbols under a file or directory</li>
 *   <li>{@code search}  — find symbol definition candidates by name/substring</li>
 * </ul>
 *
 * <p>Mirrors Reasonix's codeindex.go.
 */
public class CodeIndexTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_FILE_SIZE = 1 << 20;  // 1 MB
    private static final int MAX_FILES = 2000;

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "vendor", "__pycache__", ".idea", ".vscode",
        ".next", "dist", "build", "target", "coverage", ".qoder"
    );

    @Override public String name() { return "code_index"; }
    @Override public boolean isReadOnly() { return true; }

    @Override public String description() {
        return "Semantic code symbol search — understands code structure, not just text. Use this instead of grep when you need to: (1) distinguish methods from fields from classes, (2) count or enumerate all methods/classes in a project, (3) find where a symbol is DEFINED (not just mentioned). action=outline lists every symbol (class/method/field/func) in a file or directory with its kind. action=search finds symbols by name across the codebase. Supports Java, Go, Python, JS/TS, Rust, C/C++, Kotlin, C# and more.";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["outline", "search"],
              "description": "outline lists symbols under path; search finds symbol definition candidates by name."
            },
            "path": {
              "type": "string",
              "description": "File or directory path to inspect (default \".\")."
            },
            "query": {
              "type": "string",
              "description": "Symbol name or substring for action=search."
            },
            "kind": {
              "type": "string",
              "description": "Optional symbol kind filter: class, interface, method, func, type, struct, enum, trait, const, var, field."
            },
            "limit": {
              "type": "integer",
              "description": "Maximum symbols to return (default 100, max 200).",
              "minimum": 1
            }
          },
          "required": ["action"]
        }
        """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = new ObjectMapper().readTree(arguments);
        String action = json.has("action") ? json.get("action").asText().toLowerCase() : "";
        String pathStr = json.has("path") ? json.get("path").asText() : ".";
        String query = json.has("query") ? json.get("query").asText() : "";
        String kindFilter = json.has("kind") ? json.get("kind").asText() : "";
        int limit = json.has("limit") ? json.get("limit").asInt(DEFAULT_LIMIT) : DEFAULT_LIMIT;
        if (limit <= 0) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;

        if (!action.equals("outline") && !action.equals("search")) {
            return "Error: action must be 'outline' or 'search'";
        }
        if (action.equals("search") && query.isBlank()) {
            return "Error: query is required for action=search";
        }

        Path root = Path.of(pathStr);
        if (!root.isAbsolute()) root = Path.of("").toAbsolutePath().resolve(pathStr).normalize();

        int collectLimit = (action.equals("outline") && (kindFilter.isEmpty() && query.isEmpty()))
            ? limit : 0; // collect all when filtering, then filter

        var symbols = new ArrayList<Symbol>();
        boolean truncated = collect(root, collectLimit, action.equals("outline"), symbols);

        // Apply post-collection filters
        if (!kindFilter.isEmpty() || !query.isEmpty()) {
            symbols = filter(symbols, query, kindFilter);
            if (symbols.size() > limit) {
                symbols = new ArrayList<>(symbols.subList(0, limit));
                truncated = true;
            }
        } else if (symbols.size() > limit) {
            symbols = new ArrayList<>(symbols.subList(0, limit));
            truncated = true;
        }

        return format(symbols, truncated);
    }

    // ——— Collection ———

    private boolean collect(Path root, int limit, boolean outline, List<Symbol> out) throws IOException {
        if (Files.isRegularFile(root)) {
            if (supportedFile(root)) { parseFile(root, out); return true; }
            return false;
        }
        if (!Files.isDirectory(root)) {
            return false;
        }

        var files = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir != root && SKIP_DIRS.contains(dir.getFileName().toString()))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (supportedFile(file)) {
                    files.add(file);
                    if (files.size() >= MAX_FILES) return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.SKIP_SUBTREE;
            }
        });

        files.sort(Comparator.comparing(Path::toString));
        boolean truncated = files.size() >= MAX_FILES;

        for (Path file : files) {
            try {
                parseFile(file, out);
                if (outline && limit > 0 && out.size() >= limit) {
                    truncated = true;
                    break;
                }
            } catch (Exception ignored) {}
        }
        return truncated;
    }

    private void parseFile(Path path, List<Symbol> out) throws IOException {
        if (Files.size(path) > MAX_FILE_SIZE) return;
        String ext = extension(path).toLowerCase();

        if (ext.equals(".go")) {
            parseGo(path, out);
        } else {
            parseText(path, out);
        }
    }

    private void parseGo(Path path, List<Symbol> out) throws IOException {
        // Use regex-based Go parsing (no external dependency)
        String content = Files.readString(path);
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripLeading();
            String trimmed = line.stripTrailing();
            // func declarations
            Matcher m = RE_GO_FUNC.matcher(trimmed);
            if (m.find()) {
                String kind = m.group(1) != null ? "method" : "func";
                String name = m.group(2);
                if (name != null && !name.isEmpty()) {
                    out.add(new Symbol(name, kind, relPath(path), i + 1, ""));
                }
                continue;
            }
            m = RE_GO_TYPE.matcher(trimmed);
            if (m.find()) {
                out.add(new Symbol(m.group(1), m.group(2), relPath(path), i + 1, ""));
            }
        }
    }

    // Go regex patterns
    private static final Pattern RE_GO_FUNC = Pattern.compile(
        "^func\\s+(?:\\([^)]*\\*?\\w+\\)\\s+)?(\\w+)\\s*\\(");
    private static final Pattern RE_GO_TYPE = Pattern.compile(
        "^type\\s+(\\w+)\\s+(struct|interface)\\s*\\{?");

    private void parseText(Path path, List<Symbol> out) throws IOException {
        String ext = extension(path).toLowerCase();
        String content = Files.readString(path);
        String[] lines = content.split("\n", -1);
        String rel = relPath(path);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripLeading();
            Symbol sym = matchLine(line, ext);
            if (sym != null) {
                sym.file = rel;
                sym.line = i + 1;
                out.add(sym);
            }
        }
    }

    private Symbol matchLine(String line, String ext) {
        return switch (ext) {
            case ".java", ".kt", ".kts", ".cs" -> matchJavaLike(line, ext);
            case ".py" -> matchPython(line);
            case ".js", ".jsx", ".ts", ".tsx" -> matchJavaScript(line);
            case ".rs" -> matchRust(line);
            case ".c", ".cc", ".cpp", ".h", ".hpp" -> matchCpp(line);
            default -> null;
        };
    }

    // ——— Java / Kotlin / C# ———
    private Symbol matchJavaLike(String line, String ext) {
        // class, interface, enum, record, @interface (annotation)
        Matcher m = RE_JAVA_TYPE.matcher(line);
        if (m.find()) {
            String kind = m.group(1).toLowerCase();
            String name = m.group(2);
            if (isKeyword(name)) return null;
            return new Symbol(name, kind, "", 0, m.group(0).trim());
        }
        // method: visibility? static? returnType name (
        m = RE_JAVA_METHOD.matcher(line);
        if (m.find()) {
            String name = m.group(1);
            if (isKeyword(name)) return null;
            return new Symbol(name, "method", "", 0, m.group(0).trim());
        }
        // field: visibility? static? type name [= ;]
        m = RE_JAVA_FIELD.matcher(line);
        if (m.find()) {
            String name = m.group(1);
            if (isKeyword(name)) return null;
            return new Symbol(name, "field", "", 0, m.group(0).trim());
        }
        return null;
    }

    // Match Java class/interface/enum/record/annotation
    private static final Pattern RE_JAVA_TYPE = Pattern.compile(
        "^\\s*(?:public|protected|private|abstract|final|static|sealed|non-sealed|\\s)*"
        + "(class|interface|enum|record|@interface)\\s+(\\w+)");

    // Match Java method: modifiers retType name(
    private static final Pattern RE_JAVA_METHOD = Pattern.compile(
        "^\\s*(?:public|protected|private|static|final|abstract|synchronized|native|@\\w+(?:\\([^)]*\\))?\\s*|\\s)+"
        + "[\\w<>\\[\\],\\s?.]+\\s+(\\w+)\\s*\\([^;]*\\)\\s*(?:throws\\s+[\\w, ]+)?\\s*(?:\\{|;$)");

    // Match Java field: modifiers type name [=...];
    private static final Pattern RE_JAVA_FIELD = Pattern.compile(
        "^\\s*(?:public|protected|private|static|final|volatile|transient|\\s)+"
        + "[\\w<>\\[\\],\\s?.]+\\s+(\\w+)\\s*(?:=|;)");

    // ——— Python ———
    private Symbol matchPython(String line) {
        // class
        Matcher m = RE_PY_CLASS.matcher(line);
        if (m.find()) return new Symbol(m.group(1), "class", "", 0, line.trim());
        // async? def
        m = RE_PY_FUNC.matcher(line);
        if (m.find()) return new Symbol(m.group(1), "func", "", 0, line.trim());
        return null;
    }

    private static final Pattern RE_PY_CLASS = Pattern.compile("^class\\s+(\\w+)");
    private static final Pattern RE_PY_FUNC = Pattern.compile("^(?:async\\s+)?def\\s+(\\w+)\\s*\\(");

    // ——— JavaScript / TypeScript ———
    private Symbol matchJavaScript(String line) {
        // export? default? abstract? class
        Matcher m = RE_JS_CLASS.matcher(line);
        if (m.find()) return new Symbol(m.group(1), "class", "", 0, line.trim());
        // export? default? async? function
        m = RE_JS_FUNC.matcher(line);
        if (m.find()) return new Symbol(m.group(1), "func", "", 0, line.trim());
        // export? interface
        m = RE_JS_INTERFACE.matcher(line);
        if (m.find()) return new Symbol(m.group(1), "interface", "", 0, line.trim());
        // export? type
        m = RE_JS_TYPE.matcher(line);
        if (m.find()) return new Symbol(m.group(1), "type", "", 0, line.trim());
        // export? enum
        m = RE_JS_ENUM.matcher(line);
        if (m.find()) return new Symbol(m.group(1), "enum", "", 0, line.trim());
        // const/let/var name = (...) => or = async (...) =>
        m = RE_JS_ARROW.matcher(line);
        if (m.find()) return new Symbol(m.group(1), "func", "", 0, line.trim());
        return null;
    }

    private static final Pattern RE_JS_CLASS = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
    private static final Pattern RE_JS_FUNC = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+(\\w+)\\s*\\(");
    private static final Pattern RE_JS_INTERFACE = Pattern.compile(
        "^\\s*(?:export\\s+)?interface\\s+(\\w+)");
    private static final Pattern RE_JS_TYPE = Pattern.compile(
        "^\\s*(?:export\\s+)?type\\s+(\\w+)");
    private static final Pattern RE_JS_ENUM = Pattern.compile(
        "^\\s*(?:export\\s+)?enum\\s+(\\w+)");
    private static final Pattern RE_JS_ARROW = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s*)?\\([^)]*\\)\\s*=>");

    // ——— Rust ———
    private Symbol matchRust(String line) {
        Matcher m = RE_RUST.matcher(line);
        if (m.find()) return new Symbol(m.group(2), m.group(1).toLowerCase(), "", 0, line.trim());
        return null;
    }

    private static final Pattern RE_RUST = Pattern.compile(
        "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(fn|struct|enum|trait)\\s+(\\w+)");

    // ——— C / C++ ———
    private Symbol matchCpp(String line) {
        Matcher m = RE_CPP.matcher(line);
        if (m.find()) return new Symbol(m.group(1), "func", "", 0, line.trim());
        return null;
    }

    private static final Pattern RE_CPP = Pattern.compile(
        "^\\s*(?:[\\w:*&<>\\[\\],]+\\s+)+(\\w+)\\s*\\([^;]*\\)\\s*(?:const\\s*)?(?:override\\s*)?(?:final\\s*)?\\{?");

    // ——— Helpers ———

    private static boolean supportedFile(Path path) {
        return switch (extension(path).toLowerCase()) {
            case ".go", ".js", ".jsx", ".ts", ".tsx", ".py", ".java", ".kt", ".kts",
                 ".cs", ".rs", ".c", ".cc", ".cpp", ".h", ".hpp" -> true;
            default -> false;
        };
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static boolean isKeyword(String name) {
        return switch (name) {
            case "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
                 "return", "throw", "try", "catch", "finally", "new", "this", "super",
                 "true", "false", "null", "import", "package", "goto", "const", "volatile",
                 "transient", "synchronized", "native", "strictfp", "assert", "default" -> true;
            default -> false;
        };
    }

    private String relPath(Path path) {
        try {
            Path base = Path.of("").toAbsolutePath();
            return base.relativize(path.toAbsolutePath()).toString().replace("\\", "/");
        } catch (Exception e) {
            return path.toString().replace("\\", "/");
        }
    }

    // ——— Filtering ———

    private ArrayList<Symbol> filter(ArrayList<Symbol> in, String query, String kind) {
        String q = query.toLowerCase().trim();
        String k = kind.toLowerCase().trim();
        var out = new ArrayList<Symbol>();
        for (Symbol s : in) {
            if (!k.isEmpty() && !s.kind.equals(k)) continue;
            if (!q.isEmpty()) {
                String haystack = (s.name + " " + s.signature).toLowerCase();
                if (!haystack.contains(q)) continue;
            }
            out.add(s);
        }
        return out;
    }

    // ——— Formatting ———

    private String format(List<Symbol> symbols, boolean truncated) {
        if (symbols.isEmpty()) return "(no symbols)";
        var sb = new StringBuilder();
        for (Symbol s : symbols) {
            sb.append(String.format("%s:%d: %s %s",
                s.file, s.line, s.kind, s.name));
            if (s.signature != null && !s.signature.isEmpty()
                && !s.signature.equals(s.name) && !s.signature.equals(s.kind + " " + s.name)) {
                sb.append(" — ").append(s.signature);
            }
            sb.append("\n");
        }
        if (truncated) sb.append("... (truncated; narrow path/query/kind or raise limit)\n");
        return sb.toString().trim();
    }

    // ——— Symbol record ———

    static class Symbol {
        String name;
        String kind;
        String file;
        int line;
        String signature;

        Symbol(String name, String kind, String file, int line, String signature) {
            this.name = name;
            this.kind = kind;
            this.file = file;
            this.line = line;
            this.signature = signature;
        }
    }
}
