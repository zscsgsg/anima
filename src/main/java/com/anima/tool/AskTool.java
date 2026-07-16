package com.anima.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ask tool — lets the model put structured multiple-choice questions to the user
 * mid-task and get the answer back. Mirrors Reasonix's {@code internal/agent/ask.go AskTool}.
 *
 * <p>When no interactive user is available (headless), returns an explicit
 * model-assumption fallback so an autonomous run never blocks.
 */
public class AskTool implements Tool {

    /** Interface for presenting questions to the user and collecting answers. */
    @FunctionalInterface
    public interface Asker {
        List<AskAnswer> ask(List<AskQuestion> questions) throws Exception;
    }

    public record AskQuestion(String id, String header, String prompt,
                               List<AskOption> options, boolean multi) {}

    public record AskOption(String label, String description) {}

    public record AskAnswer(String questionId, List<String> selected) {}

    private final Asker asker;

    public AskTool(Asker asker) {
        this.asker = asker;
    }

    @Override
    public String name() { return "ask"; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String description() {
        return "Ask the user one or more multiple-choice questions when you hit a decision " +
            "that is genuinely theirs to make — one you can't resolve from the request, the code, " +
            "or sensible defaults. The frontend shows the options for the user to pick; their choices " +
            "are returned to you. Prefer this over asking in prose for any real fork (which approach, " +
            "which library, scope). Don't use it for decisions with an obvious default — pick the " +
            "sensible option and proceed. Tool-approval modes such as YOLO do not answer these " +
            "questions for the user. Each question has a short `header` (a tab label), the `question` " +
            "text, 2-4 `options` (each a `label` and optional `description`; put any recommended option " +
            "first), and `multiSelect` when more than one may apply.";
    }

    @Override
    public String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "questions": {
                  "type": "array",
                  "minItems": 1,
                  "maxItems": 4,
                  "description": "1-4 questions to ask together.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "header": { "type": "string", "description": "Very short label for the question (a tab title), e.g. 'Library'." },
                      "question": { "type": "string", "description": "The full question to ask." },
                      "options": {
                        "type": "array",
                        "minItems": 2,
                        "maxItems": 4,
                        "description": "The choices. Put any recommended option first.",
                        "items": {
                          "type": "object",
                          "properties": {
                            "label": { "type": "string", "description": "The choice text (concise)." },
                            "description": { "type": "string", "description": "Optional one-line explanation of the choice." }
                          },
                          "required": ["label"]
                        }
                      },
                      "multiSelect": { "type": "boolean", "description": "Allow selecting more than one option." }
                    },
                    "required": ["question", "header", "options"]
                  }
                }
              },
              "required": ["questions"]
            }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode args = MAPPER.readTree(arguments);
        JsonNode questions = args.get("questions");
        if (questions == null || !questions.isArray() || questions.size() == 0) {
            return "Error: at least one question is required";
        }

        List<AskQuestion> qs = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            JsonNode q = questions.get(i);
            String question = q.get("question").asText().trim();
            String header = q.has("header") ? q.get("header").asText().trim() : "";
            if (question.isEmpty()) return "Error: question " + (i + 1) + ": a question is required";

            JsonNode opts = q.get("options");
            if (opts == null || !opts.isArray() || opts.size() < 2) {
                return "Error: question " + (i + 1) + ": at least 2 options are required";
            }

            List<AskOption> options = new ArrayList<>();
            for (int j = 0; j < opts.size(); j++) {
                JsonNode o = opts.get(j);
                String label = o.get("label").asText().trim();
                if (label.isEmpty()) {
                    return "Error: question " + (i + 1) + " option " + (j + 1) + ": label is required";
                }
                String desc = o.has("description") ? o.get("description").asText().trim() : "";
                options.add(new AskOption(label, desc));
            }

            boolean multi = q.has("multiSelect") && q.get("multiSelect").asBoolean();
            qs.add(new AskQuestion("q" + (i + 1), header, question, options, multi));
        }

        if (asker == null) {
            return "No interactive user answered. This is a model-assumption fallback, not a user answer. " +
                "Proceed with your best judgment, state the assumption you made, and prefer the safest " +
                "reversible option when choices differ in risk.";
        }

        List<AskAnswer> answers = asker.ask(qs);
        return formatAnswers(qs, answers);
    }

    private static String formatAnswers(List<AskQuestion> qs, List<AskAnswer> answers) {
        Map<String, List<String>> pick = new java.util.LinkedHashMap<>();
        for (var a : answers) pick.put(a.questionId(), a.selected());

        int answered = 0;
        for (var q : qs) {
            if (pick.containsKey(q.id()) && !pick.get(q.id()).isEmpty()) answered++;
        }

        if (answered == 0) {
            return "The user dismissed the question without choosing — read this as \"don't decide for me, " +
                "let's just talk.\" Do not pick an option, run a tool, or take any further action toward this; " +
                "stop and wait for the user's next message.";
        }

        StringBuilder sb = new StringBuilder("The user answered:\n");
        for (var q : qs) {
            var sel = pick.getOrDefault(q.id(), List.of());
            String label = !q.header().isEmpty() ? q.header() : q.prompt();
            if (sel.isEmpty()) {
                sb.append("- ").append(label).append(": (left unanswered — don't assume a choice)\n");
            } else {
                sb.append("- ").append(label).append(": ").append(String.join(", ", sel)).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
