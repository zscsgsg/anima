package com.anima;

import com.anima.llm.DeepSeekProvider;
import com.anima.web.ChatEndpoint;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

/**
 * Anima — DeepSeek-native terminal AI coding agent.
 * Step 1: streaming chat with HTML demo.
 */
public class AnimaApp {

    public static void main(String[] args) {
        System.out.println("Anima v0.1.0 starting...");

        DeepSeekProvider provider = new DeepSeekProvider();
        System.out.println("DeepSeek provider initialized.");

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.directory = "/web";
                staticFiles.location = Location.CLASSPATH;
            });
            config.http.asyncTimeout = 120_000L;
        });

        // Register SSE endpoint
        new ChatEndpoint(provider).register(app);

        // Health check
        app.get("/api/health", ctx -> ctx.result("OK"));

        app.start(8080);
        System.out.println("Anima running at http://localhost:8080");
    }
}
