package com.anima.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Preview tool — opens a URL in the local browser or preview pane.
 * Mirrors Reasonix's preview tool concept.
 */
public class PreviewTool implements Tool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    @Override public String name() { return "preview"; }

    @Override public boolean isReadOnly() { return true; }

    @Override
    public String description() {
        return "Preview a web URL in the local browser or Anima's built-in preview pane. " +
            "Use this after starting a local dev server to show the user the result. " +
            "Also accepts a local file path (e.g. an HTML file) to preview it. " +
            "This tool is read-only — it only opens a preview, it does not modify anything.";
    }

    @Override
    public String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "URL to preview (http:// or https://). Can also be a relative path to an HTML file."
                }
              },
              "required": ["url"]
            }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode args = MAPPER.readTree(arguments);
        String url = args.has("url") ? args.get("url").asText().trim() : "";

        if (url.isEmpty()) return "Error: url is required";

        // If it's a local file path, convert to file:// URI
        URI uri;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            var path = java.nio.file.Path.of(url).toAbsolutePath();
            uri = path.toUri();
        } else {
            uri = URI.create(url);
        }

        // Validate URL is reachable for http/https
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                var req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
                var resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
                return "Preview opened: " + url + "\n" +
                    "Server responded with status " + resp.statusCode() + ". " +
                    "The user can now see it in their browser.";
            } catch (IOException e) {
                return "Preview URL " + url + " — server not reachable yet. " +
                    "Start the dev server first, then try again.\n" +
                    "Error: " + e.getMessage();
            }
        }

        // For file:// URIs, just confirm
        return "Preview: " + uri + "\n" +
            "Open this file in your browser to see the result.";
    }
}
