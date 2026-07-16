package com.anima.tool;

import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * Fetch a URL and return its text content.
 * Mirrors Reasonix's web_fetch with SSRF protection.
 * HTML pages are stripped to readable text.
 *
 * <p>ReadOnly: true — fetches content, does not modify anything.
 */
public class WebFetchTool implements Tool {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_BYTES = 1 << 20; // 1 MiB

    private final HttpClient httpClient;

    public WebFetchTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override public String name() { return "web_fetch"; }
    @Override public boolean isReadOnly() { return true; }

    @Override public String description() {
        return "Fetch a URL over HTTPS/HTTP and return its text content. " +
               "HTML pages are reduced to readable text (scripts, styles, tags stripped). " +
               "Use to read documentation pages, API responses, or source files.";
    }

    @Override public String schema() {
        return """
        {
          "type": "object",
          "properties": {
            "url": {
              "type": "string",
              "description": "Absolute URL beginning with http:// or https://"
            }
          },
          "required": ["url"]
        }""";
    }

    @Override
    public String execute(String arguments) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        if (!json.has("url")) return "Error: missing 'url' argument";
        String urlStr = json.get("url").asText();

        URI uri;
        try {
            uri = URI.create(urlStr);
        } catch (IllegalArgumentException e) {
            return "Error: invalid URL: " + urlStr;
        }

        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "Error: only http/https URLs are supported";
        }

        // SSRF guard: only block IP-literal URLs pointing at private ranges.
        // Domain names are NOT pre-resolved (avoids false positives from proxy/VPN DNS).
        // This matches Reasonix's approach for proxy connections.
        String host = uri.getHost();
        if (host != null && isIpLiteral(host)) {
            try {
                InetAddress addr = InetAddress.getByName(host);
                if (isBlockedAddress(addr)) {
                    return "Error: refusing to fetch internal address: " + host;
                }
            } catch (Exception ignored) {}
        }

        var request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", "Anima/0.9 (web_fetch)")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return "Error fetching URL: " + e.getMessage();
        }

        int status = response.statusCode();
        if (status < 200 || status >= 400) {
            return "HTTP " + status + " for " + urlStr;
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String body = response.body();

        if (body == null || body.isEmpty()) return "(empty response)";

        if (body.length() > MAX_BYTES) {
            body = body.substring(0, MAX_BYTES) + "\n... (truncated at 1 MiB)";
        }

        // Strip HTML tags for text/HTML responses
        if (contentType.contains("html") || body.stripLeading().startsWith("<!")
            || body.stripLeading().startsWith("<html")) {
            return stripHtml(body);
        }

        return body;
    }

    /** Check whether a host string is a literal IP (v4 or v6), not a domain name. */
    private static boolean isIpLiteral(String host) {
        // Quick check: domain names contain dots but also non-numeric chars
        // An IPv4 literal is 1-3 digits separated by dots
        if (host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return true;
        // IPv6 literal is wrapped in brackets (e.g. [::1])
        if (host.startsWith("[") && host.endsWith("]")) return true;
        // Plain IPv6 without brackets (less common in URLs)
        if (host.contains(":") && !host.contains(".")) return true;
        return false;
    }

    /** Block private, loopback-adjacent, and link-local IPs (SSRF prevention). */
    private static boolean isBlockedAddress(InetAddress addr) {
        byte[] octets = addr.getAddress();
        if (octets == null || octets.length != 4) return false; // IPv6 not blocked by default
        int a = octets[0] & 0xFF, b = octets[1] & 0xFF;

        // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8
        if (a == 10) return true;
        if (a == 172 && b >= 16 && b <= 31) return true;
        if (a == 192 && b == 168) return true;
        if (a == 127) return true;
        // 169.254.0.0/16 (link-local)
        if (a == 169 && b == 254) return true;
        // 0.0.0.0/8
        if (a == 0) return true;

        return false;
    }

    /** Crude HTML → text: strip tags, collapse whitespace. */
    private static String stripHtml(String html) {
        // Remove script and style blocks
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        html = html.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        // Remove all tags
        html = html.replaceAll("<[^>]+>", " ");
        // Decode common entities
        html = html.replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&nbsp;", " ");
        // Collapse whitespace
        html = html.replaceAll("\\s+", " ").trim();
        return html;
    }
}
