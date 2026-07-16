package com.anima.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts JSON Schema strings (from Tool.schema()) to LangChain4j JsonObjectSchema.
 * This is critical — without parameters, the LLM has no idea what arguments each tool expects.
 */
public final class SchemaConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaConverter() {}

    /**
     * Parse a JSON Schema string and return a LangChain4j JsonObjectSchema.
     */
    public static JsonObjectSchema convert(String schemaJson) {
        try {
            JsonNode root = MAPPER.readTree(schemaJson);
            var builder = JsonObjectSchema.builder();

            // Parse properties
            JsonNode props = root.get("properties");
            if (props != null && props.isObject()) {
                Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
                var fields = props.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    String name = entry.getKey();
                    JsonNode def = entry.getValue();
                    properties.put(name, buildElement(def));
                }
                builder.addProperties(properties);
            }

            // Parse required
            JsonNode required = root.get("required");
            if (required != null && required.isArray()) {
                List<String> req = new ArrayList<>();
                for (JsonNode r : required) {
                    req.add(r.asText());
                }
                builder.required(req);
            }

            return builder.build();
        } catch (Exception e) {
            // Fallback: return empty schema rather than crash
            return JsonObjectSchema.builder().build();
        }
    }

    private static JsonSchemaElement buildElement(JsonNode def) {
        String type = def.has("type") ? def.get("type").asText() : "string";
        String desc = def.has("description") ? def.get("description").asText() : null;

        return switch (type) {
            case "string" -> {
                var b = JsonStringSchema.builder();
                if (desc != null) b.description(desc);
                yield b.build();
            }
            case "integer" -> {
                var b = JsonIntegerSchema.builder();
                if (desc != null) b.description(desc);
                yield b.build();
            }
            case "number" -> {
                var b = JsonNumberSchema.builder();
                if (desc != null) b.description(desc);
                yield b.build();
            }
            case "boolean" -> {
                var b = JsonBooleanSchema.builder();
                if (desc != null) b.description(desc);
                yield b.build();
            }
            case "array" -> {
                var b = JsonArraySchema.builder();
                if (desc != null) b.description(desc);
                yield b.build();
            }
            default -> {
                var b = JsonStringSchema.builder();
                if (desc != null) b.description(desc);
                yield b.build();
            }
        };
    }
}
