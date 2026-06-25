package com.anima;

import com.anima.llm.DeepSeekProvider;
import com.anima.terminal.TerminalUI;
import com.anima.web.ChatEndpoint;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.util.Arrays;

/**
 * Anima — DeepSeek-native terminal AI coding agent.
 * Dual mode: terminal (--terminal) or web (default).
 */
public class AnimaApp {

    public static void main(String[] args) throws Exception {
        boolean terminalMode = Arrays.asList(args).contains("--terminal");

        if (terminalMode) {
            new TerminalUI().start();
            return;
        }

        System.out.println("Anima v0.5.0 starting... (use --terminal for CLI mode)");

        DeepSeekProvider provider = new DeepSeekProvider();
        System.out.println("DeepSeek provider initialized.");

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.directory = "/web";
                staticFiles.location = Location.CLASSPATH;
            });
            config.http.asyncTimeout = 120_000L;
        });

        new ChatEndpoint(provider).register(app);
        app.get("/api/health", ctx -> ctx.result("OK"));
        app.start(8080);
        System.out.println("Anima running at http://localhost:8080");
    }
}
