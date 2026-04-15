package ai.claude.code.agent;

import ai.claude.code.capability.BackgroundRunner;
import ai.claude.code.capability.ContextCompactor;
import ai.claude.code.capability.MessageBus;
import ai.claude.code.capability.SkillLoader;
import ai.claude.code.capability.TaskStore;
import ai.claude.code.capability.TeammateRunner;
import ai.claude.code.capability.TodoManager;
import ai.claude.code.capability.WorktreeManager;
import ai.claude.code.core.BaseTools;
import ai.claude.code.core.OpenAiClient;
import ai.claude.code.core.ToolHandler;
import ai.claude.code.tool.BackgroundTools;
import ai.claude.code.tool.CompactTool;
import ai.claude.code.tool.FileTools;
import ai.claude.code.tool.SkillTool;
import ai.claude.code.tool.TaskTools;
import ai.claude.code.tool.TeamTools;
import ai.claude.code.tool.TodoTool;
import ai.claude.code.tool.ToolProvider;
import ai.claude.code.tool.WorktreeTools;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 组装工厂 — 初始化所有 capability，返回配置好的 AgentLoop。
 * Agent assembly factory — initializes all capabilities and returns a configured AgentLoop.
 *
 * 扩展新工具：在 buildProviders() 里加一行即可。
 * To add new tools: add one line to buildProviders().
 */
public class AgentAssembler {

    /** lead agent 收件箱 ID，与 AgentLoop 保持一致 */
    private static final String LEAD_ID = "lead";

    /**
     * 根据 OpenAiClient 和工作目录构建完整的 AgentLoop（内部自动创建所有 capability）。
     * Build a fully-configured AgentLoop from an OpenAiClient and working directory.
     * All capabilities are created internally.
     *
     * @param client  已配置的 LLM 客户端 / configured LLM client
     * @param workDir 工作目录路径 / working directory
     * @return 配置好的 AgentLoop 实例 / configured AgentLoop instance
     */
    public static AgentLoop build(OpenAiClient client, String workDir) {
        BaseTools baseTools             = new BaseTools(workDir);
        TodoManager todoManager         = new TodoManager();
        SkillLoader skillLoader         = new SkillLoader(workDir + "/skills");
        ContextCompactor compactor      = new ContextCompactor(client, workDir);
        TaskStore taskStore             = new TaskStore(workDir + "/.tasks");
        BackgroundRunner bgRunner       = new BackgroundRunner();
        WorktreeManager worktreeManager = new WorktreeManager(workDir);
        MessageBus messageBus           = new MessageBus(workDir);
        TeammateRunner teammateRunner   = new TeammateRunner(client, workDir, messageBus, taskStore);

        return build(client, workDir, baseTools, todoManager, skillLoader,
                compactor, taskStore, bgRunner, worktreeManager, messageBus, teammateRunner);
    }

    /**
     * 使用外部预创建的 capability 构建 AgentLoop（供 Spring 依赖注入使用）。
     * Build AgentLoop from externally pre-created capabilities (for Spring DI).
     *
     * 通过此方法，Spring Bean 中创建的 capability 实例与 AgentLoop 内部使用的是同一对象，
     * 避免重复实例化导致状态不一致。
     * Ensures Spring-managed capability beans are the same instances used inside AgentLoop,
     * preventing state inconsistency from duplicate instantiation.
     */
    public static AgentLoop build(OpenAiClient client, String workDir,
                                  BaseTools baseTools, TodoManager todoManager,
                                  SkillLoader skillLoader, ContextCompactor compactor,
                                  TaskStore taskStore, BackgroundRunner bgRunner,
                                  WorktreeManager worktreeManager, MessageBus messageBus,
                                  TeammateRunner teammateRunner) {
        List<ToolProvider> providers = buildProviders(
                baseTools, todoManager, skillLoader, compactor,
                taskStore, bgRunner, messageBus, worktreeManager, teammateRunner);

        Map<String, ToolHandler> dispatch = aggregateHandlers(providers);
        JsonArray toolDefs               = aggregateDefinitions(providers);
        String systemPrompt              = buildSystemPrompt(skillLoader, workDir);

        return new AgentLoop(client, dispatch, toolDefs, systemPrompt,
                todoManager, compactor, bgRunner, messageBus, teammateRunner);
    }

    // ==================== 组装细节 ====================

    private static List<ToolProvider> buildProviders(
            BaseTools baseTools, TodoManager todoManager, SkillLoader skillLoader,
            ContextCompactor compactor, TaskStore taskStore, BackgroundRunner bgRunner,
            MessageBus messageBus, WorktreeManager worktreeManager,
            TeammateRunner teammateRunner) {
        List<ToolProvider> list = new ArrayList<ToolProvider>();
        list.add(new FileTools(baseTools));
        list.add(new TodoTool(todoManager));
        list.add(new SkillTool(skillLoader));
        list.add(new CompactTool());
        list.add(new TaskTools(taskStore));
        list.add(new BackgroundTools(bgRunner));
        list.add(new TeamTools(messageBus, taskStore, LEAD_ID, teammateRunner));
        list.add(new WorktreeTools(worktreeManager));
        return list;
    }

    private static Map<String, ToolHandler> aggregateHandlers(List<ToolProvider> providers) {
        Map<String, ToolHandler> dispatch = new LinkedHashMap<String, ToolHandler>();
        for (ToolProvider p : providers) {
            dispatch.putAll(p.handlers());
        }
        return dispatch;
    }

    private static JsonArray aggregateDefinitions(List<ToolProvider> providers) {
        JsonArray defs = new JsonArray();
        for (ToolProvider p : providers) {
            for (JsonObject def : p.definitions()) {
                defs.add(def);
            }
        }
        return defs;
    }

    private static String buildSystemPrompt(SkillLoader skillLoader, String workDir) {
        return "You are a LEAD coding assistant running at " + workDir + ".\n\n"
                + "## Execution Model — CRITICAL\n"
                + "You are action-oriented. When given a task:\n"
                + "1. Optionally call `todo` to plan your steps.\n"
                + "2. IMMEDIATELY execute the work: call write_file, bash, read_file, etc.\n"
                + "3. Do NOT call msg_read, task_list, or task_create until AFTER you have called spawn_teammate.\n"
                + "   Before spawning a teammate, there are zero messages and zero tasks — checking is wasteful.\n"
                + "4. After spawn_teammate, you may check msg_read ONCE per round to see if the teammate replied.\n\n"
                + "## Two Separate Task Systems\n"
                + "- `todo` — YOUR personal in-memory checklist for your own steps. Use it; then execute.\n"
                + "- `task_create` / `task_list` / `task_update` — Only for work assigned to spawned teammates.\n\n"
                + "## When User Asks You to Use an Agent / Reviewer\n"
                + "1. Do the prep work yourself first (write the file, load the skill).\n"
                + "2. Call `task_create` with subject + description.\n"
                + "3. Call `spawn_teammate` with a clear role system prompt.\n"
                + "4. Check `msg_read` once per round to see replies. When done, summarize.\n\n"
                + "## Forbidden Patterns\n"
                + "- NEVER call task_poll or idle (worker-only tools).\n"
                + "- NEVER call msg_read or task_list before spawning a teammate — they will be empty.\n"
                + "- NEVER clear your todo list until all items are done.\n\n"
                + "## Available Skills\n"
                + skillLoader.getDescriptions()
                + "\nWhen a user request matches a skill, call load_skill first, then follow the skill's instructions.\n";
    }
}
