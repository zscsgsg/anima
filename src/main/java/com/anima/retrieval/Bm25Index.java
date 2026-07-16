package com.anima.retrieval;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Lightweight BM25 full-text search — no external dependencies.
 * Used by the history tool to search past session transcripts.
 *
 * <p>BM25 formula: score(D,Q) = sum(IDF(qi) * TF(qi,D) * (k1+1) / (TF(qi,D) + k1 * (1-b + b*|D|/avgDL)))
 *
 * <p>Defaults: k1=1.2, b=0.75 (standard Okapi BM25).
 */
public class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<Document> docs = new ArrayList<>();
    private final Map<String, Double> idfCache = new HashMap<>();
    private double avgDocLength = 0;
    private int totalDocs = 0;

    // ── Document ──

    public record Document(String id, String path, String text, String kind,
                           String sessionId, int messageIndex, String role,
                           String toolName) {}

    // ── Indexing ──

    /** Add a document to the index. */
    public void add(Document doc) {
        docs.add(doc);
        totalDocs++;
        avgDocLength = ((avgDocLength * (totalDocs - 1)) + tokenize(doc.text).size()) / totalDocs;
        idfCache.clear(); // invalidate IDF cache
    }

    /** Add multiple documents at once. */
    public void addAll(Collection<Document> documents) {
        for (var d : documents) add(d);
    }

    /** Number of indexed documents. */
    public int size() { return docs.size(); }

    // ── Searching ──

    /**
     * Search with BM25 scoring. Returns top-k results sorted by score desc.
     * @param query search query
     * @param limit max results (default 8, max 20)
     */
    public List<Hit> search(String query, int limit) {
        if (query == null || query.isBlank() || docs.isEmpty()) return List.of();
        limit = Math.max(1, Math.min(limit <= 0 ? 8 : limit, 20));

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return List.of();

        // BM25 score for each doc
        record ScoredDoc(Document doc, double score) {}
        List<ScoredDoc> scored = new ArrayList<>(docs.size());

        for (Document doc : docs) {
            double score = bm25Score(doc.text, queryTerms);
            if (score > 0) {
                scored.add(new ScoredDoc(doc, score));
            }
        }

        // Sort by score descending, keep top-k
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        // Relative score floor: trim trailing noise
        List<Hit> hits = new ArrayList<>();
        double bestScore = scored.isEmpty() ? 0 : scored.get(0).score;
        double floor = bestScore * 0.1; // keep hits within 10% of best

        for (int i = 0; i < Math.min(scored.size(), limit); i++) {
            ScoredDoc sd = scored.get(i);
            if (sd.score < floor && hits.size() > 0) break;
            hits.add(Hit.from(sd.doc, sd.score));
        }
        return hits;
    }

    // ── BM25 scoring ──

    private double bm25Score(String docText, List<String> queryTerms) {
        List<String> docTerms = tokenize(docText);
        if (docTerms.isEmpty()) return 0;

        Map<String, Integer> tf = termFrequencies(docTerms);
        double docLen = docTerms.size();
        double score = 0;

        for (String term : new HashSet<>(queryTerms)) {
            double idf = idf(term);
            if (idf == 0) continue;
            int f = tf.getOrDefault(term, 0);
            if (f == 0) continue;
            double numerator = f * (K1 + 1);
            double denominator = f + K1 * (1 - B + B * docLen / Math.max(avgDocLength, 1));
            score += idf * numerator / denominator;
        }
        return score;
    }

    private double idf(String term) {
        return idfCache.computeIfAbsent(term, t -> {
            int df = 0;
            for (Document d : docs) {
                if (tokenize(d.text).contains(t)) df++;
            }
            if (df == 0 || totalDocs == 0) return 0.0;
            return Math.log(1 + (totalDocs - df + 0.5) / (df + 0.5));
        });
    }

    // ── Tokenization ──

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        // Split on non-alphanumeric, keep CJK characters as individual tokens
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/') {
                current.append(Character.toLowerCase(c));
            } else if (isCJK(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                tokens.add(String.valueOf(c));
            } else {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) tokens.add(current.toString());

        // Filter noise: drop single-char non-CJK and very long tokens
        return tokens.stream()
            .filter(t -> t.length() >= 2 || (t.length() == 1 && isCJK(t.charAt(0))))
            .filter(t -> t.length() <= 50)
            .collect(Collectors.toList());
    }

    private static boolean isCJK(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) ||  // CJK Unified
               (c >= 0x3400 && c <= 0x4DBF) ||  // CJK Extension A
               (c >= 0xF900 && c <= 0xFAFF) ||  // CJK Compatibility
               (c >= 0x3040 && c <= 0x309F) ||  // Hiragana
               (c >= 0x30A0 && c <= 0x30FF) ||  // Katakana
               (c >= 0xAC00 && c <= 0xD7AF);     // Hangul
    }

    private static Map<String, Integer> termFrequencies(List<String> terms) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : terms) tf.merge(t, 1, Integer::sum);
        return tf;
    }

    // ── Hit ──

    public record Hit(String sessionId, String sessionPath, int messageIndex,
                      String kind, String role, String toolName,
                      double score, String snippet) {
        static Hit from(Document doc, double score) {
            String snippet = doc.text.length() > 200
                ? doc.text.substring(0, 197) + "..." : doc.text;
            return new Hit(doc.sessionId, doc.path, doc.messageIndex,
                doc.kind, doc.role, doc.toolName, score, snippet);
        }
    }
}
