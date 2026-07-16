package com.anima.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages multiple LLM providers and supports runtime hot-switching.
 * Providers are registered with labels (e.g. "flash", "pro") that
 * the /model command uses for switching.
 */
public class ProviderManager {
    //名字 → 模型实例   注册多个模型，然后通过名字来切换模型
    private final Map<String, LLMProvider> providers = new LinkedHashMap<>();
    //当前激活的模型名字
    private String activeLabel;

    /** Register a provider under its label. First one registered becomes active. */
    public void register(LLMProvider provider) {
        providers.put(provider.label(), provider);
        if (activeLabel == null) activeLabel = provider.label();
    }

    /** Get the currently active provider. */
    public LLMProvider active() {
        return providers.get(activeLabel);
    }

    /** Get the active label (for display). */
    public String activeLabel() {
        return activeLabel;
    }

    /** Switch to a different provider by label. Returns true on success.
     *  这个方法的目的是在运行时切换模型，而不是在配置文件中指定模型
     * */
    public boolean switchTo(String label) {
        if (providers.containsKey(label)) {
            activeLabel = label;
            return true;
        }
        return false;
    }

    /** All registered providers (for /model listing). */
    public Map<String, LLMProvider> all() {
        return providers;
    }
}
