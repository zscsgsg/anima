package com.anima.terminal;

/**
 * A slash command the user can type in the terminal (e.g. /help, /sessions).
 */
public interface SlashCommand {
    /** Command name without the leading slash (e.g. "help"). */
    String name();

    /** Short description shown in /help output. */
    String description();

    /**
     * Execute the command.
     * @param args  everything after the command name (may be empty)
     * @param ctx   gives access to AgentLoop and TerminalWriter
     * @return result text to display to the user, or null for silent
     */
    String execute(String args, SlashContext ctx);
}
