package com.anima;

import com.anima.config.AnimaConfig;
import com.anima.llm.DeepSeekProvider;
import com.anima.memory.ProjectMemory;
import com.anima.terminal.TerminalUI;
import com.anima.web.ChatEndpoint;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Anima — DeepSeek-native terminal AI coding agent.
 * Default: terminal mode. Use --web for browser mode.
 */
public class AnimaApp {

    public static void main(String[] args) throws Exception {
        //启动时，是否包含参数 -web
        boolean webMode = Arrays.asList(args).contains("--web");

        // ── First-run: resolve API key ──   获取秘钥（）
        String apiKey = AnimaConfig.resolveApiKey();
        //判断秘钥是否为空
        boolean firstRun = (apiKey == null);
        //如果找到了，apiKey就有值，firstRun就是 false（不是第一次）。
        //如果没找到，apiKey就是空的，firstRun就是 true（第一次运行）
        if (firstRun) {
            apiKey = promptForApiKey();  // 弹窗问你要密码
            if (apiKey == null) {
                //没有秘钥进行提供，退出
                System.err.println("No API key provided. Exiting.");
                System.exit(1);
            }
            try {
                //这个把秘钥存起来 下次就不用输了
                AnimaConfig.saveApiKey(apiKey);
                //它就存到 ~/.anima/config.properties文件里
                System.out.println("✓ API key saved to ~/.anima/config.properties");
            } catch (IOException e) {
                //失败
                System.err.println("⚠ Failed to save API key: " + e.getMessage());
                System.err.println("  The key will only be used for this session.");
            }
        }

        // Make key available to DeepSeekProvider via system property (fallback after env var)
        //设置环境变量
        System.setProperty("anima.api.key", apiKey);

        // ── Load project memory ──
        //查看我在哪个目录，同时看看这个目录下有没有ANIMA.md  如果有可以加载到memory（记忆）里
        Path cwd = Path.of("").toAbsolutePath();
        ProjectMemory memory = ProjectMemory.load(cwd);

        // ── Show first-run usage guide ──
        if (firstRun) {
            //如果是第一次打印这个语句
            printWelcomeGuide(!webMode, memory);
        }

        // Default: terminal mode. Use --web for browser mode.
        if (!webMode) {
            //终端启动 加载记忆（ANIMA.md)
            new TerminalUI().start(memory);
            return;
        }
        //如果不是终端，这个就是web
        System.out.println("Anima v0.17.0 starting in web mode at http://localhost:8080");
        if (!memory.isEmpty()) {
            System.out.println("Project memory loaded: " + memory.sources().size() + " file(s)");
        }
        //准备好聊天的AI大脑
        DeepSeekProvider provider = new DeepSeekProvider();
        System.out.println("DeepSeek provider initialized.");
        //建一个微型网站服务器
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.directory = "/web";
                staticFiles.location = Location.CLASSPATH;
            });
            config.http.asyncTimeout = 120_000L;
        });
        //给网站加一个聊天的接口
        new ChatEndpoint(provider, memory).register(app);
        app.get("/api/health", ctx -> ctx.result("OK"));
        app.start(8080);
        System.out.println("Anima running at http://localhost:8080");
    }

    /** Interactive API key input. Returns null if user declines. */
    private static String promptForApiKey() {
        Console console = System.console();
        if (console == null) {
            System.err.println();
            System.err.println("══════════════════════════════════════════");
            System.err.println("  Welcome to Anima!");
            System.err.println("══════════════════════════════════════════");
            System.err.println();
            System.err.println("  No DEEPSEEK_API_KEY found.");
            System.err.println();
            System.err.println("  To configure:");
            System.err.println("    1. Get your key at https://platform.deepseek.com/api_keys");
            System.err.println("    2. Set it via environment variable:");
            System.err.println("         set DEEPSEEK_API_KEY=sk-xxxx");
            System.err.println("    3. Or restart in an interactive terminal for guided setup.");
            System.err.println();
            return null;
        }

        console.printf("%n");
        console.printf("╔══════════════════════════════════════════╗%n");
        console.printf("║        Welcome to Anima!                ║%n");
        console.printf("║  DeepSeek-native terminal AI agent      ║%n");
        console.printf("╚══════════════════════════════════════════╝%n");
        console.printf("%n");
        console.printf("  To get started, you need a DeepSeek API key.%n");
        console.printf("  Get one at: https://platform.deepseek.com/api_keys%n");
        console.printf("%n");

        String key = console.readLine("  Enter your API key (press Enter to skip): ");
        if (key == null || key.isBlank()) {
            console.printf("%n  ⚠ No key entered — you can set DEEPSEEK_API_KEY later.%n%n");
            return null;
        }
        return key.trim();
    }

    /** Print friendly usage guide for first-time users. */
    private static void printWelcomeGuide(boolean terminalMode, ProjectMemory memory) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║           Anima — Quick Start Guide                 ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");

        if (terminalMode) {
            System.out.println("║                                                      ║");
            System.out.println("║  Terminal mode — type anything to start!             ║");
            System.out.println("║                                                      ║");
            System.out.println("║  Essential commands:                                 ║");
            System.out.println("║    /help     — list all available commands           ║");
            System.out.println("║    /model    — switch between AI models              ║");
            System.out.println("║    /memory   — view project memory                   ║");
            System.out.println("║    /init     — analyze project & generate ANIMA.md   ║");
            System.out.println("║    /sessions — manage saved sessions                 ║");
            System.out.println("║    /clear    — clear screen                          ║");
            System.out.println("║    /exit     — quit Anima                            ║");
            System.out.println("║                                                      ║");
            System.out.println("║  Tips:                                               ║");
            System.out.println("║    • Run /init to auto-generate project memory       ║");
            System.out.println("║    • Use ! prefix mid-turn to steer the agent        ║");
            System.out.println("║    • Ctrl-C interrupts gracefully                    ║");
            System.out.println("║    • API key saved to ~/.anima/config.properties     ║");

        } else {
            System.out.println("║                                                      ║");
            System.out.println("║  Web mode — starting at http://localhost:8080        ║");
            System.out.println("║                                                      ║");
            System.out.println("║  For the full terminal experience, just run:         ║");
            System.out.println("║    anima                                              ║");
            System.out.println("║                                                      ║");
        }

        System.out.println("║                                                      ║");
        if (memory != null && !memory.isEmpty()) {
            System.out.println("║  ✓ Project memory loaded: " + memory.sources().size() + " file(s)");
            int pad = 54 - ("║  ✓ Project memory loaded: " + memory.sources().size() + " file(s)").length();
            if (pad < 0) pad = 0;
            System.out.println("║" + " ".repeat(pad) + "║");
        }
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
