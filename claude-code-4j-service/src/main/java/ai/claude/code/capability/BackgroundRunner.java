package ai.claude.code.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 后台任务执行器 - 异步执行耗时任务，完成后产生通知。
 * Background Runner - executes long-running tasks asynchronously with completion notifications.
 *
 * 【核心思想 / Key Insight】
 * "Fire and forget — the agent doesn't block while the task runs."
 * "发射后忘记——智能体不会因为任务执行而阻塞。"
 *
 * 【线程安全模型 / Thread Safety Model】
 * 1. ConcurrentHashMap 存储任务状态 — 后台线程写入，主线程读取
 *    ConcurrentHashMap for task state — background threads write, main thread reads
 * 2. CopyOnWriteArrayList 作为通知队列 — 写少读多场景
 *    CopyOnWriteArrayList as notification queue — few writes, many reads
 * 3. drainNotifications() 使用 synchronized 保证排空操作的原子性
 *    drainNotifications() uses synchronized to ensure atomic drain
 *
 * 【通知注入 / Notification Injection】
 * 每次调用 LLM 之前，主线程排空通知队列，将已完成的任务结果
 * 注入为用户消息，让 LLM 自动获知后台任务的状态。
 * Before each LLM call, the main thread drains the notification queue
 * and injects completed task results as user messages.
 */
public class BackgroundRunner {

    /**
     * 任务状态：taskId -> 状态描述字符串。
     * Task status map: taskId -> status description string.
     *
     * 使用 ConcurrentHashMap 是因为后台线程会并发更新状态，
     * 主线程会通过 getStatus() 并发读取。
     * ConcurrentHashMap is used because background threads update concurrently
     * while the main thread reads via getStatus().
     */
    private final ConcurrentHashMap<String, String> taskStatus =
            new ConcurrentHashMap<String, String>();

    /**
     * 通知队列：后台任务完成后写入通知，主线程在 LLM 调用前排空并消费。
     * Notification queue: background tasks write on completion, main thread drains before LLM calls.
     *
     * CopyOnWriteArrayList 适合"写少读多"场景——
     * 只在任务完成时写一次，但每次 LLM 调用前都要检查。
     * CopyOnWriteArrayList suits the "few writes, many reads" pattern —
     * written only on task completion, checked before every LLM call.
     */
    private final CopyOnWriteArrayList<String> notifications =
            new CopyOnWriteArrayList<String>();

    /**
     * 缓存线程池：按需创建线程，空闲 60 秒后回收。
     * Cached thread pool: creates threads on demand, recycles after 60s idle.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 提交后台任务。立即返回，不阻塞调用线程。
     * Submit a background task. Returns immediately without blocking.
     *
     * 任务完成后会：
     * 1. 更新 taskStatus 中的状态（成功或失败）
     * 2. 向 notifications 队列添加完成通知
     *
     * After task completion:
     * 1. Updates status in taskStatus (success or failure)
     * 2. Adds a completion notification to the notifications queue
     *
     * @param taskId 任务唯一标识符 / unique task identifier
     * @param work   要异步执行的任务 / the work to execute asynchronously
     */
    public void submit(final String taskId, final Callable<String> work) {
        taskStatus.put(taskId, "running");

        executor.submit(new Runnable() {
            @Override
            public void run() {
                long startTime = System.currentTimeMillis();
                try {
                    String result = work.call();
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    taskStatus.put(taskId, "completed: " + result);
                    notifications.add("[Background " + taskId + " completed] "
                            + "Duration: " + elapsed + "s | Result: " + result);
                } catch (Exception e) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    taskStatus.put(taskId, "failed: " + e.getMessage());
                    notifications.add("[Background " + taskId + " failed] "
                            + "Duration: " + elapsed + "s | Error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 获取指定任务的状态描述。
     * Get the status description of a specific task.
     *
     * @param taskId 任务 ID / task ID
     * @return 状态描述字符串，任务不存在时返回 "unknown" / status string, "unknown" if not found
     */
    public String getStatus(String taskId) {
        String status = taskStatus.get(taskId);
        if (status == null) {
            return "unknown";
        }
        return status;
    }

    /**
     * 排空通知队列并返回所有累积的通知。
     * Drain the notification queue and return all accumulated notifications.
     *
     * 使用 synchronized 确保排空操作的原子性——
     * 在复制和清空之间不会有新的通知插入导致丢失。
     * Uses synchronized to ensure atomic drain —
     * no new notifications can be inserted between copy and clear.
     *
     * @return 自上次调用以来的所有通知（可能为空列表） / all notifications since last call (may be empty)
     */
    public synchronized List<String> drainNotifications() {
        List<String> drained = new ArrayList<String>(notifications);
        notifications.clear();
        return drained;
    }

    /**
     * 优雅关闭线程池。
     * Gracefully shut down the thread pool.
     *
     * 两阶段关闭策略：
     * 1. shutdown()：不再接受新任务，等待已提交的任务完成
     * 2. 超时后 shutdownNow()：强制中断正在执行的任务
     *
     * Two-phase shutdown:
     * 1. shutdown(): stop accepting new tasks, wait for submitted tasks
     * 2. After timeout, shutdownNow(): force-interrupt running tasks
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
