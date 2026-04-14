package ai.claude.code.tool;

import ai.claude.code.capability.BackgroundRunner;
import ai.claude.code.core.OpenAiClient;
import ai.claude.code.core.ShellUtils;
import ai.claude.code.core.ToolHandler;
import ai.claude.code.tool.ToolUtils;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 后台任务工具集 — 提供异步任务提交、状态查询与通知消费能力。
 * Background task tools — async task submission, status query, and notification draining.
 *
 * 包含 3 个工具 / Contains 3 tools:
 *   - bg_submit: 提交后台 shell 命令异步执行 / Submit a shell command as a background task
 *   - bg_status: 查询指定后台任务的状态 / Query status of a specific background task
 *   - bg_drain:  消费并返回所有待读取的完成通知 / Drain and return all pending notifications
 */
public class BackgroundTools implements ToolProvider {

    private final BackgroundRunner bg;

    /**
     * 构造后台任务工具集。
     * Constructor for background task tools.
     *
     * @param bg 后台任务执行器实例 / BackgroundRunner instance
     */
    public BackgroundTools(BackgroundRunner bg) {
        this.bg = bg;
    }

    @Override
    public Map<String, ToolHandler> handlers() {
        Map<String, ToolHandler> map = new LinkedHashMap<String, ToolHandler>();

        // bg_submit: 提交一个 shell 命令作为后台任务异步执行
        // bg_submit: submit a shell command as an async background task
        map.put("bg_submit", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                final String taskId = input.get("task_id").getAsString();
                final String command = input.get("command").getAsString();

                bg.submit(taskId, new Callable<String>() {
                    @Override
                    public String call() {
                        return ShellUtils.run(command, null, 0); // 0 = 无超时，阻塞直到完成
                    }
                });

                return "Background task '" + taskId + "' submitted.";
            }
        });

        // bg_status: 查询指定任务 ID 的当前状态
        // bg_status: query the current status of a specific task ID
        map.put("bg_status", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String taskId = input.get("task_id").getAsString();
                return bg.getStatus(taskId);
            }
        });

        // bg_drain: 排空通知队列并返回所有已完成任务的通知
        // bg_drain: drain the notification queue and return all completion notifications
        map.put("bg_drain", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                List<String> notifications = bg.drainNotifications();
                if (notifications.isEmpty()) {
                    return "No pending notifications.";
                }
                StringBuilder sb = new StringBuilder();
                for (String notification : notifications) {
                    sb.append(notification).append("\n");
                }
                return sb.toString().trim();
            }
        });

        return map;
    }

    @Override
    public List<JsonObject> definitions() {
        List<JsonObject> defs = new ArrayList<JsonObject>();

        // bg_submit: 需要 task_id 和 command 两个必填参数
        // bg_submit: requires both task_id and command (both required)
        defs.add(OpenAiClient.toolDef(
                "bg_submit",
                "Submit a shell command to run in the background asynchronously. " +
                "Returns immediately without waiting for completion.",
                OpenAiClient.schema(
                        "task_id", "string", "true",
                        "command", "string", "true"
                )
        ));

        // bg_status: 需要 task_id 一个必填参数
        // bg_status: requires task_id (required)
        defs.add(OpenAiClient.toolDef(
                "bg_status",
                "Get the status of a background task by its task_id. " +
                "Returns 'running', 'completed: <result>', 'failed: <error>', or 'unknown'.",
                OpenAiClient.schema(
                        "task_id", "string", "true"
                )
        ));

        // bg_drain: 无参数，直接消费通知队列
        // bg_drain: no parameters, drains the notification queue directly
        defs.add(OpenAiClient.toolDef(
                "bg_drain",
                "Drain all pending background task completion notifications. " +
                "Returns all notifications since the last drain, or 'No pending notifications.'",
                ToolUtils.emptySchema()
        ));

        return defs;
    }

}
