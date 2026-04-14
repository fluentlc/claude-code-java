package ai.claude.code.capability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;

/**
 * 任务轮询器 - 空闲时自动扫描并认领未分配的任务。
 * Task Poller - automatically scan and claim unassigned tasks during idle.
 *
 * 【核心机制 / Core Mechanism】
 * Agent 在空闲状态下调用 pollForTask()，扫描 TaskStore 中 owner 为空的 pending 任务，
 * 找到后自动认领（设置 owner 字段）。
 * During idle state, the agent calls pollForTask() to scan TaskStore for pending tasks
 * with no owner, and automatically claims one by setting the owner field.
 *
 * 【自治循环 / Autonomous Loop】
 *   Agent 工作 -> 完成 -> 进入 idle -> pollForTask() -> 找到任务 -> 认领 -> 继续工作
 *   Agent works -> done -> idle -> pollForTask() -> found task -> claim -> resume work
 *   如果没有找到任务，Agent 继续保持 idle 直到超时关闭。
 *   If no task found, agent stays idle until timeout shutdown.
 */
public class TaskPoller {

    /**
     * 尝试认领一个未分配的 pending 任务。
     * Try to claim one unassigned pending task.
     *
     * 流程 / Flow:
     *   1. 调用 TaskStore.scanUnclaimed() 获取所有未认领任务
     *      Call TaskStore.scanUnclaimed() to get all unclaimed tasks
     *   2. 如果有任务，取第一个并调用 TaskStore.claimTask() 认领
     *      If tasks exist, take the first one and call TaskStore.claimTask() to claim
     *   3. 返回被认领的任务 JsonObject（包含 id, title, description 等）
     *      Return the claimed task JsonObject (with id, title, description, etc.)
     *
     * @param store   任务存储 / Task store
     * @param agentId 认领者的 Agent 名称 / Claiming agent's name
     * @return Optional 包含被认领的任务，如果没有可用任务则为空
     *         Optional containing the claimed task, or empty if none available
     */
    public static Optional<JsonObject> pollForTask(TaskStore store, String agentId) {
        // 扫描未认领任务 / Scan unclaimed tasks
        String unclaimed = store.scanUnclaimed();
        if ("No unclaimed tasks.".equals(unclaimed)) {
            return Optional.empty();
        }

        // 解析 JSON 数组 / Parse JSON array
        JsonArray tasks = JsonParser.parseString(unclaimed).getAsJsonArray();
        if (tasks.size() == 0) {
            return Optional.empty();
        }

        // 取第一个未认领任务并认领 / Take first unclaimed task and claim it
        JsonObject firstTask = tasks.get(0).getAsJsonObject();
        String taskId = firstTask.get("id").getAsString();
        String claimResult = store.claimTask(taskId, agentId);

        // 认领失败（如已被其他 Agent 抢先认领）/ Claim failed (e.g., race condition)
        if (claimResult.startsWith("Error")) {
            return Optional.empty();
        }

        // 更新任务对象的 owner 和 status 以反映认领后状态
        // Update task object to reflect post-claim state
        firstTask.addProperty("owner", agentId);
        firstTask.addProperty("status", "in_progress");
        return Optional.of(firstTask);
    }
}
