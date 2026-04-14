package ai.claude.code.capability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Todo 管理器 - 管理 todo 列表。
 * Todo Manager - manages the todo list.
 *
 * 【状态模型 / State Model】
 * 每个 item 有: id, text, status (pending / in_progress / completed)
 * Each item has: id, text, status (pending / in_progress / completed)
 * 状态流转：pending -> in_progress -> completed
 * State transition: pending -> in_progress -> completed
 *
 * 【约束条件 / Constraints】
 * - 最多 20 个 todo（防止模型创建无限多的任务，浪费 token）
 *   Max 20 todos (prevent the model from creating too many tasks)
 * - 同时只能有 1 个 in_progress（强制模型专注于单一任务）
 *   Only 1 in_progress at a time (force the model to focus on one task)
 * - text 不能为空（保证每个任务都有明确的描述）
 *   Text cannot be empty (ensure each task has a clear description)
 *
 * 【Nag 机制 / Nag Mechanism】
 * roundsSinceUpdate 计数器追踪模型上次更新 todo 后经过了多少轮。
 * roundsSinceUpdate counter tracks how many rounds since the last todo update.
 * 超过 3 轮未更新，agentLoop 会注入提醒。
 * After 3 rounds without update, the agent loop injects a reminder.
 */
public class TodoManager {

    /** 超过此轮数未更新 todo 则触发 nag 提醒 */
    private static final int NAG_THRESHOLD = 3;
    /** 单次最多允许的 todo 条目数 */
    private static final int MAX_TODOS = 20;

    /** 当前的 todo 列表，由模型通过 todo 工具全量更新 */
    private final List<TodoItem> items;

    /**
     * 自上次 todo 更新以来经过的轮数。
     * Rounds since last todo update.
     * 这是 Nag 提醒机制的核心：当此值 >= 3 时触发提醒注入。
     * Core of nag mechanism: triggers reminder injection when >= 3.
     * 每次模型调用 todo 工具时重置为 0，每轮不调用 todo 则 +1。
     * Reset to 0 when model calls todo tool, +1 each round without todo call.
     */
    int roundsSinceUpdate;

    public TodoManager() {
        this.items = new ArrayList<TodoItem>();
        this.roundsSinceUpdate = 0;
    }

    /**
     * 模型调用 todo 工具时，用传入的列表全量替换当前列表。
     * When the model calls the todo tool, replace the current list with the incoming list.
     *
     * 【全量替换 vs 增量更新 / Full replacement vs incremental update】
     * 采用"全量替换"策略：每次调用都传入完整的 items 数组。
     * Uses "full replacement" strategy: each call passes the complete items array.
     *
     * 【验证先于应用 / Validate-before-apply】
     * 先解析验证所有 item，全部通过后才 clear + addAll。
     * Parse and validate all items first, then clear + addAll only if all pass.
     *
     * @return null 表示成功；非 null 为错误信息，原列表不变
     *         null means success; non-null is an error message, original list unchanged
     */
    public String update(JsonArray newItems) {
        // 限制最大 20 条，防止模型创建过多任务浪费 token
        if (newItems.size() > MAX_TODOS) {
            return "Error: Max 20 todos allowed";
        }
        // 验证阶段：先解析所有 item，检查约束条件
        List<TodoItem> parsed = new ArrayList<TodoItem>();
        int inProgressCount = 0;
        for (JsonElement el : newItems) {
            JsonObject obj = el.getAsJsonObject();
            String id = obj.get("id").getAsString();
            String text = obj.get("text").getAsString();
            if (text.isEmpty()) {
                return "Error: Todo item '" + id + "' has empty text";
            }
            // 默认状态为 pending
            String status = obj.has("status") ? obj.get("status").getAsString() : "pending";
            if ("in_progress".equals(status)) {
                inProgressCount++;
            }
            parsed.add(new TodoItem(id, text, status));
        }
        // 同一时间只允许 1 个 in_progress 任务
        if (inProgressCount > 1) {
            return "Error: Only one task can be in_progress at a time";
        }
        // 应用阶段：验证通过后才替换列表
        items.clear();
        items.addAll(parsed);
        roundsSinceUpdate = 0; // 重置 nag 计数器
        return null; // null 表示没有错误
    }

    /**
     * 渲染 todo 列表为人类可读的文本格式。
     * Render the todo list as human-readable text.
     *
     * 返回值会作为 tool_result 发送给模型，让模型能"看到"当前状态。
     * The return value is sent as tool_result to the model.
     */
    public String render() {
        if (items.isEmpty()) {
            return "(empty todo list)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== TODO LIST ===\n");
        for (TodoItem item : items) {
            String icon;
            if ("completed".equals(item.status)) {
                icon = "[x]";
            } else if ("in_progress".equals(item.status)) {
                icon = "[>]";
            } else {
                icon = "[ ]";
            }
            sb.append(icon).append(" ").append(item.id).append(": ").append(item.text).append("\n");
        }
        int done = 0;
        for (TodoItem item : items) {
            if ("completed".equals(item.status)) {
                done++;
            }
        }
        sb.append("\n(").append(done).append("/").append(items.size()).append(" completed)");
        return sb.toString().trim();
    }

    /**
     * 每轮 agent loop 结束时调用，如果该轮模型没有调用 todo 工具，计数 +1。
     * Called at the end of each agent loop round; if the model didn't call todo, increment counter.
     */
    public void tick() {
        roundsSinceUpdate++;
    }

    /**
     * 判断是否需要注入提醒。
     * Check if a reminder injection is needed.
     * 条件：todo 列表非空且已有 3 轮未更新。
     * Condition: todo list is non-empty and 3 rounds without update.
     */
    public boolean needsReminder() {
        return !items.isEmpty() && roundsSinceUpdate >= NAG_THRESHOLD;
    }

    /**
     * Todo 项 - 不可变的值对象。
     * Todo item - immutable value object.
     */
    static class TodoItem {
        /** 任务唯一标识 / Task unique identifier */
        final String id;
        /** 任务描述文本 / Task description text */
        final String text;
        /** 状态：pending / in_progress / completed */
        final String status;

        TodoItem(String id, String text, String status) {
            this.id = id;
            this.text = text;
            this.status = status;
        }
    }
}
