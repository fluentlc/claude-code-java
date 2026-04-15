package ai.claude.code.config;

import ai.claude.code.agent.AgentAssembler;
import ai.claude.code.agent.AgentLoop;
import ai.claude.code.capability.BackgroundRunner;
import ai.claude.code.capability.ContextCompactor;
import ai.claude.code.capability.MessageBus;
import ai.claude.code.capability.SessionStore;
import ai.claude.code.capability.SkillLoader;
import ai.claude.code.capability.TaskStore;
import ai.claude.code.capability.TeammateRunner;
import ai.claude.code.capability.TodoManager;
import ai.claude.code.capability.WorktreeManager;
import ai.claude.code.core.BaseTools;
import ai.claude.code.core.ClientFactory;
import ai.claude.code.core.OpenAiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spring Bean 配置 — 将所有 capability 和 AgentLoop 注册为 Spring Bean。
 * Spring Bean configuration — registers all capabilities and AgentLoop as Spring Beans.
 *
 * 所有 capability 先作为 Bean 创建，再传入 AgentAssembler.build()，
 * 确保 CliRunner（slash 命令）和 AgentLoop（工具执行）共享同一套 capability 实例。
 * All capabilities are created as beans first, then passed to AgentAssembler.build(),
 * ensuring CliRunner (slash commands) and AgentLoop (tool execution) share the same instances.
 */
@Configuration
public class AgentBeans {

    @Value("${claude.workdir:${user.dir}}")
    private String workDir;

    @Bean
    public OpenAiClient openAiClient() {
        return ClientFactory.openAiClient();
    }

    @Bean
    public BaseTools baseTools() {
        return new BaseTools(workDir);
    }

    @Bean
    public TodoManager todoManager() {
        return new TodoManager();
    }

    @Bean
    public SkillLoader skillLoader() {
        return new SkillLoader(workDir + "/skills");
    }

    @Bean
    public ContextCompactor contextCompactor(OpenAiClient client) {
        return new ContextCompactor(client, workDir);
    }

    @Bean
    public TaskStore taskStore() {
        return new TaskStore(workDir + "/.tasks");
    }

    @Bean
    public BackgroundRunner backgroundRunner() {
        return new BackgroundRunner();
    }

    @Bean
    public WorktreeManager worktreeManager() {
        return new WorktreeManager(workDir);
    }

    @Bean
    public MessageBus messageBus() {
        return new MessageBus(workDir);
    }

    @Bean
    public SessionStore sessionStore() {
        return new SessionStore(workDir + "/.sessions");
    }

    @Bean
    public ExecutorService streamExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sse-stream");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public TeammateRunner teammateRunner(OpenAiClient client,
                                         MessageBus messageBus,
                                         TaskStore taskStore) {
        return new TeammateRunner(client, workDir, messageBus, taskStore);
    }

    /**
     * AgentLoop 使用所有已创建的 capability bean，确保实例一致性。
     * AgentLoop uses all pre-created capability beans, ensuring instance consistency.
     */
    @Bean
    public AgentLoop agentLoop(OpenAiClient client,
                               BaseTools baseTools,
                               TodoManager todoManager,
                               SkillLoader skillLoader,
                               ContextCompactor compactor,
                               TaskStore taskStore,
                               BackgroundRunner bgRunner,
                               WorktreeManager worktreeManager,
                               MessageBus messageBus,
                               TeammateRunner teammateRunner) {
        return AgentAssembler.build(client, workDir,
                baseTools, todoManager, skillLoader,
                compactor, taskStore, bgRunner,
                worktreeManager, messageBus, teammateRunner);
    }
}
