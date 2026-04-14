package ai.claude.code.cli;

import ai.claude.code.agent.AgentLoop;
import ai.claude.code.agent.SlashRouter;
import ai.claude.code.capability.SkillLoader;
import ai.claude.code.capability.TaskStore;
import ai.claude.code.capability.TodoManager;
import ai.claude.code.capability.WorktreeManager;
import ai.claude.code.capability.MessageBus;
import ai.claude.code.core.OpenAiClient;
import com.google.gson.JsonArray;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Scanner;

/**
 * CLI REPL 入口 — 仅在 spring.profiles.active=cli 时激活。
 * CLI REPL entry — activated only when spring.profiles.active=cli.
 *
 * 启动方式 / How to start:
 *   java -jar claude-code-4j-start.jar --spring.profiles.active=cli
 */
@Component
@Profile("cli")
public class CliRunner implements CommandLineRunner {

    @Autowired
    private AgentLoop agentLoop;

    @Autowired
    private TodoManager todoManager;

    @Autowired
    private SkillLoader skillLoader;

    @Autowired
    private TaskStore taskStore;

    @Autowired
    private WorktreeManager worktreeManager;

    @Autowired
    private MessageBus messageBus;

    @Value("${claude.workdir:${user.dir}}")
    private String workDir;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║           claude-code-4j                ║");
        System.out.println("║  输入 /help 查看命令，quit 退出             ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        JsonArray messages = new JsonArray();
        Scanner scanner    = new Scanner(System.in);

        while (true) {
            System.out.print("\nYou> ");
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;
            if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) break;

            SlashRouter.RouteResult route = SlashRouter.route(input);
            if (handleSlash(route, messages)) continue;

            messages.add(OpenAiClient.userMessage(input));
            String reply = agentLoop.run(messages);
            System.out.println("\nAssistant> " + reply);
        }

        scanner.close();
        agentLoop.shutdown();
        System.out.println("Goodbye!");
    }

    private boolean handleSlash(SlashRouter.RouteResult route, JsonArray messages) {
        switch (route.command) {
            case HELP:
                System.out.println(SlashRouter.helpText());
                return true;
            case TASKS:
                System.out.println("=== Todo List ===\n" + todoManager.render());
                System.out.println("\n=== Task Store ===\n" + taskStore.list());
                return true;
            case SKILL:
                System.out.println(route.args.isEmpty()
                        ? skillLoader.getDescriptions()
                        : skillLoader.getContent(route.args));
                return true;
            case COMPACT:
                if (messages.size() <= 2) {
                    System.out.println("[/compact] 消息太少，无需压缩。");
                } else {
                    JsonArray compacted = agentLoop.getCompactor().compact(messages, agentLoop.getSystemPrompt());
                    while (messages.size() > 0) messages.remove(0);
                    for (com.google.gson.JsonElement el : compacted) messages.add(el);
                    System.out.println("[/compact] 压缩完成，当前消息数: " + messages.size());
                }
                return true;
            case TEAM:
                System.out.println("=== Team Status ===");
                System.out.println("[Inbox] lead: " + messageBus.readInbox("lead").size() + " 条消息");
                System.out.println("[Unclaimed Tasks] " + taskStore.scanUnclaimed());
                System.out.println("提示: 用 msg_send/msg_read/task_poll 工具与 worker 通信");
                return true;
            case WORKTREE:
                System.out.println(route.args.isEmpty()
                        ? worktreeManager.list()
                        : worktreeManager.create(route.args, null));
                return true;
            default:
                return false;
        }
    }
}
