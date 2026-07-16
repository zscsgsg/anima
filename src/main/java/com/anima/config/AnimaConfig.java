package com.anima.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Configuration loader — merges built-in defaults with an optional
 * .anima/anima.properties file in the project root.
 *
 * <p>Priority: .anima/anima.properties > built-in defaults.
 *
 * <h3>Multi-model configuration</h3>
 * Define additional providers via numbered keys:
 * <pre>
 *   model.name=deepseek-v4-flash
 *   model.base_url=https://api.deepseek.com
 *   model.provider.1.label=pro
 *   model.provider.1.name=deepseek-v4-pro
 *   model.provider.1.url=https://api.deepseek.com
 * </pre>
 * The primary model (model.name) becomes "flash", any model.provider.N
 * entries are registered alongside it.
 */
public class AnimaConfig {

    private final Properties props;

    private AnimaConfig(Properties props) {
        this.props = props;
    }

    /** Path to global config: ~/.anima/config.properties */
    private static Path globalConfigPath() {
        return Path.of(System.getProperty("user.home"), ".anima", "config.properties");
    }

    /** Load config from the given working directory. Never throws — falls back to defaults. */
    public static AnimaConfig load(Path cwd) {
        Properties props = new Properties(defaults());

        // 1. Global config (~/.anima/config.properties) — lowest priority
        Path global = globalConfigPath();
        if (Files.exists(global)) {
            try (InputStream in = Files.newInputStream(global)) {
                props.load(in);
            } catch (IOException ignored) {}
        }

        // 2. Project config (.anima/anima.properties) — overrides global
        Path file = cwd.resolve(".anima").resolve("anima.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException ignored) {}
        }
        return new AnimaConfig(props);
    }

    /**
     * Resolve API key with fallback chain:
     * 1. DEEPSEEK_API_KEY env var
     * 2. anima.api.key system property (-D flag)
     * 3. deepseek.api_key in ~/.anima/config.properties
     * Returns null if not found anywhere.
     */
    public static String resolveApiKey() {
        // 1. Environment variable (highest priority)
        String envKey = System.getenv("DEEPSEEK_API_KEY");
        if (envKey != null && !envKey.isBlank()) return envKey;

        // 2. System property (-Danima.api.key=...)
        String propKey = System.getProperty("anima.api.key");
        if (propKey != null && !propKey.isBlank()) return propKey;

        // 3. Global config file
        Path global = globalConfigPath();
        if (Files.exists(global)) {
            try (InputStream in = Files.newInputStream(global)) {
                Properties gp = new Properties();
                gp.load(in);
                String key = gp.getProperty("deepseek.api_key");
                if (key != null && !key.isBlank()) return key;
            } catch (IOException ignored) {}
        }

        return null;
    }

    /**
     * Persist API key to ~/.anima/config.properties.
     * Creates parent directories as needed.
     */
    public static void saveApiKey(String apiKey) throws IOException {
        Path global = globalConfigPath();
        Files.createDirectories(global.getParent());

        // Merge with existing keys (don't overwrite other config)
        Properties gp = new Properties();
        if (Files.exists(global)) {
            try (InputStream in = Files.newInputStream(global)) {
                gp.load(in);
            } catch (IOException ignored) {}
        }
        gp.setProperty("deepseek.api_key", apiKey);

        try (OutputStream out = Files.newOutputStream(global)) {
            gp.store(out, "Anima global configuration");
        }
    }

    /** Default values. */
    private static Properties defaults() {
        Properties p = new Properties();
        p.setProperty("model.name", "deepseek-v4-flash");
        p.setProperty("model.base_url", "https://api.deepseek.com");
        // Additional provider: pro model (enables dual-model mode by default)
        p.setProperty("model.provider.1.label", "pro");
        p.setProperty("model.provider.1.name", "deepseek-v4-pro");
        p.setProperty("model.provider.1.url", "https://api.deepseek.com");
        p.setProperty("agent.max_steps", "0");
        p.setProperty("agent.context_window", "1000000");
        p.setProperty("agent.planner_model", "pro");   // pro as planner when dual-model is active
        p.setProperty("agent.planner_max_steps", "10");
        p.setProperty("permission.mode", "ask");
        p.setProperty("session.auto_save", "true");
        p.setProperty("session.max_sessions", "20");
        return p;
    }

    public String modelName() { return props.getProperty("model.name"); }
    public String modelBaseUrl() { return props.getProperty("model.base_url"); }
    public int agentMaxSteps() { return parseInt("agent.max_steps", 10); }
    public int contextWindow() { return parseInt("agent.context_window", 1_000_000); }
    public String plannerModel() { return props.getProperty("agent.planner_model", ""); }
    public int plannerMaxSteps() { return parseInt("agent.planner_max_steps", 0); }
    public String permissionMode() { return props.getProperty("permission.mode"); }
    public boolean sessionAutoSave() { return Boolean.parseBoolean(props.getProperty("session.auto_save")); }
    public int sessionMaxSessions() { return parseInt("session.max_sessions", 20); }

    /** Get the default provider label for the primary model. */
    public String defaultModelLabel() {
        return props.getProperty("model.label", "flash");
    }

    /** Discover all configured providers, ordered: primary first, then numbered extras. */
    public List<ProviderSpec> providers() {
        List<ProviderSpec> list = new ArrayList<>();

        // Primary model
        String label = defaultModelLabel();
        list.add(new ProviderSpec(label, modelName(), modelBaseUrl()));

        // Numbered extras: model.provider.N.label/name/url
        for (int i = 1; ; i++) {
            String prefix = "model.provider." + i;
            String pl = props.getProperty(prefix + ".label");
            if (pl == null || pl.isBlank()) break;
            String pn = props.getProperty(prefix + ".name", "");
            String pu = props.getProperty(prefix + ".url", modelBaseUrl());
            if (pn.isBlank()) break;
            list.add(new ProviderSpec(pl, pn, pu));
        }

        return list;
    }

    /** Lightweight provider spec: label, modelName, baseUrl. */
    public record ProviderSpec(String label, String modelName, String baseUrl) {}

    private int parseInt(String key, int fallback) {
        try { return Integer.parseInt(props.getProperty(key)); }
        catch (NumberFormatException e) { return fallback; }
    }
}
