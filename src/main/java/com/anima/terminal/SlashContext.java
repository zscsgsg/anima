package com.anima.terminal;

import com.anima.agent.AgentLoop;
import com.anima.llm.ProviderManager;
import com.anima.session.SessionManager;

/**
 * Execution context passed to slash commands, giving them access to
 * the AgentLoop (for state queries/mutations) and TerminalWriter (for output).
 */
public class SlashContext {
    private final AgentLoop agent;
    private final TerminalWriter out;
    private final SessionManager sessionManager;
    private final ProviderManager providerManager;

    public SlashContext(AgentLoop agent, TerminalWriter out,
                        SessionManager sessionManager, ProviderManager providerManager) {
        this.agent = agent;
        this.out = out;
        this.sessionManager = sessionManager;
        this.providerManager = providerManager;
    }

    public AgentLoop agent() { return agent; }
    public TerminalWriter out() { return out; }
    public SessionManager sessionManager() { return sessionManager; }
    public ProviderManager providerManager() { return providerManager; }
}
