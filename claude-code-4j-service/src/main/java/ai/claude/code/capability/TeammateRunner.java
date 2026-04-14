package ai.claude.code.capability;

import ai.claude.code.agent.TeammateLoop;
import ai.claude.code.core.OpenAiClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Teammate 生命周期管理器 — 动态 spawn 和追踪 Teammate Agent 线程。
 * Teammate lifecycle manager — dynamically spawns and tracks Teammate agent threads.
 *
 * 【核心设计 / Core Design】
 * 每次调用 spawn() 会在独立线程中启动一个 TeammateLoop，该 Loop 持有自己的
 * LLM 对话历史，通过 MessageBus 与 Lead 通信，通过 TaskStore 自主认领任务。
 * Each spawn() call starts a TeammateLoop in an independent thread. The loop
 * maintains its own LLM conversation history, communicates with Lead via MessageBus,
 * and autonomously claims tasks via TaskStore.
 *
 * 【状态追踪 / Status Tracking】
 * TeammateLoop 在状态变更时回调 updateStatus()，主线程可通过 list() 查询。
 * TeammateLoop calls back updateStatus() on state changes; main thread queries via list().
 *
 * 【状态流转 / Status Flow】
 *   spawn() → "working" → (end_turn) → "idle" → (new work) → "working" → ... → "done"
 */
public class TeammateRunner {

    private static final Gson GSON = new GsonBuilder().create();

    /** LLM 客户端（所有 Teammate 共享同一实例，HttpURLConnection 是无状态的）*/
    /** Shared LLM client (stateless HttpURLConnection, safe to share) */
    private final OpenAiClient client;

    /** 工作目录，用于 BaseTools 路径安全检查 / Working directory for BaseTools path safety */
    private final String workDir;

    /** 消息总线，Teammate 通过它与 Lead 通信 / MessageBus for Teammate ↔ Lead communication */
    private final MessageBus messageBus;

    /** 任务存储，Teammate 通过它认领任务 / TaskStore for Teammate task claiming */
    private final TaskStore taskStore;

    /**
     * Teammate 状态表 / Teammate status map.
     * key: teammate name, value: status record
     */
    private final ConcurrentHashMap<String, TeammateStatus> teammates =
            new ConcurrentHashMap<String, TeammateStatus>();

    /**
     * 缓存线程池：Teammate 数量通常较少，按需创建线程。
     * Cached thread pool: teammate count is small, threads created on demand.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Teammate 状态记录。
     * Teammate status record.
     */
    public static class TeammateStatus {
        public final String name;
        public final String role;
        public volatile String status; // "working" | "idle" | "done"

        public TeammateStatus(String name, String role, String status) {
            this.name   = name;
            this.role   = role;
            this.status = status;
        }
    }

    /**
     * 构造 TeammateRunner。
     * Constructor for TeammateRunner.
     *
     * @param client     LLM 客户端 / LLM client
     * @param workDir    工作目录 / working directory
     * @param messageBus 消息总线 / message bus
     * @param taskStore  任务存储 / task store
     */
    public TeammateRunner(OpenAiClient client, String workDir,
                          MessageBus messageBus, TaskStore taskStore) {
        this.client     = client;
        this.workDir    = workDir;
        this.messageBus = messageBus;
        this.taskStore  = taskStore;
    }

    /**
     * 在独立线程中 spawn 一个新的 Teammate Agent。
     * Spawn a new Teammate Agent in an independent thread.
     *
     * @param name         Teammate 名称（作为收件箱 ID 使用） / Teammate name (used as inbox ID)
     * @param role         Teammate 角色描述 / role description
     * @param instructions 初始任务指令 / initial task instructions
     * @return 启动成功的提示信息 / success message
     */
    public String spawn(String name, String role, String instructions) {
        if (teammates.containsKey(name) &&
                !"done".equals(teammates.get(name).status)) {
            return "Error: Teammate '" + name + "' is already running (status: "
                    + teammates.get(name).status + "). Use a different name.";
        }

        TeammateStatus status = new TeammateStatus(name, role, "working");
        teammates.put(name, status);

        TeammateLoop loop = new TeammateLoop(
                name, role, instructions, client, workDir,
                messageBus, taskStore, this);

        executor.submit(loop);
        System.out.println("[TeammateRunner] Spawned: " + name + " (role: " + role + ")");
        return "Spawned teammate '" + name + "' with role: " + role;
    }

    /**
     * 列出所有 Teammate 及其当前状态。
     * List all teammates with their current status.
     *
     * @return JSON 数组字符串 / JSON array string
     */
    public String list() {
        if (teammates.isEmpty()) {
            return "No teammates spawned yet.";
        }
        JsonArray arr = new JsonArray();
        for (TeammateStatus ts : teammates.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name",   ts.name);
            obj.addProperty("role",   ts.role);
            obj.addProperty("status", ts.status);
            arr.add(obj);
        }
        return GSON.toJson(arr);
    }

    /**
     * TeammateLoop 回调：更新指定 Teammate 的状态。
     * Callback from TeammateLoop: update the status of a specific teammate.
     *
     * @param name   Teammate 名称 / teammate name
     * @param status 新状态 ("working" | "idle" | "done") / new status
     */
    public void updateStatus(String name, String status) {
        TeammateStatus ts = teammates.get(name);
        if (ts != null) {
            ts.status = status;
            System.out.println("[TeammateRunner] " + name + " → " + status);
        }
    }

    /**
     * 向指定 Teammate 发送 shutdown 信号（通过消息总线）。
     * Send shutdown signal to a specific teammate via message bus.
     *
     * @param name Teammate 名称 / teammate name
     * @return 发送结果 / send result
     */
    public String shutdown(String name) {
        if (!teammates.containsKey(name)) {
            return "Error: Teammate '" + name + "' not found.";
        }
        JsonObject shutdownMsg = new JsonObject();
        shutdownMsg.addProperty("type",       "shutdown_request");
        shutdownMsg.addProperty("request_id", "shutdown-" + System.currentTimeMillis());
        messageBus.send("lead", name, GSON.toJson(shutdownMsg));
        return "Shutdown signal sent to '" + name + "'.";
    }

    /**
     * 优雅关闭所有 Teammate 线程。
     * Gracefully shut down all teammate threads.
     *
     * 发送 shutdown 信号给所有活跃 Teammate，然后终止线程池。
     * Sends shutdown signals to all active teammates, then terminates the thread pool.
     */
    public void shutdownAll() {
        for (TeammateStatus ts : teammates.values()) {
            if (!"done".equals(ts.status)) {
                shutdown(ts.name);
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        System.out.println("[TeammateRunner] All teammates shut down.");
    }
}
