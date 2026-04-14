package ai.claude.code.capability;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * JSONL 文件消息总线 - 基于文件系统的 Agent 间通信机制。
 * JSONL File Message Bus - filesystem-based inter-agent communication.
 *
 * 【核心设计 / Core Design】
 * 每个 Agent 拥有独立的 .jsonl 收件箱文件。发送消息 = 向目标文件追加一行 JSON；
 * 读取消息 = 读取并清空自己的文件（drain 语义）。
 * Each agent has its own .jsonl inbox file. Sending = append a JSON line to target;
 * Reading = read and clear own file (drain semantics).
 *
 * 【为什么用 JSONL / Why JSONL】
 *   1. 持久化: 进程崩溃后消息不丢失 / Persistence: messages survive crashes
 *   2. 可观察性: 可用 cat/tail 直接查看 / Observable: use cat/tail to inspect
 *   3. 简单性: 不需要消息中间件 / Simple: no message broker needed
 *   4. 跨进程: 可扩展为多进程通信 / Cross-process: extensible to multi-process
 *
 * 【目录结构 / Directory Structure】
 *   .team/
 *   +-- inbox/
 *   |   +-- lead.jsonl       <- Lead 的收件箱 / Lead's inbox
 *   |   +-- alice.jsonl      <- Teammate alice 的收件箱 / alice's inbox
 *
 * 【消息格式 / Message Format】
 *   { "from": "lead", "to": "alice", "content": "...", "type": "message",
 *     "timestamp": "2024-01-01T12:00:00" }
 */
public class MessageBus {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 收件箱目录 (.team/inbox/) / Inbox directory */
    private final Path inboxDir;

    /**
     * 构造消息总线，自动创建目录结构。
     * Constructor - creates inbox directory structure.
     *
     * @param workDir 工作目录 (项目根目录) / Working directory (project root)
     */
    public MessageBus(String workDir) {
        this.inboxDir = Paths.get(workDir, ".team", "inbox");
        try {
            Files.createDirectories(inboxDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create inbox dir: " + e.getMessage(), e);
        }
    }

    /**
     * 发送消息到指定 Agent 的收件箱。
     * Send a message to the specified agent's inbox.
     *
     * 实现细节 / Implementation:
     *   1. 构造 JSON 消息对象 (from, to, content, type, timestamp)
     *   2. 压缩为单行 (JSONL 格式要求每条消息占一行)
     *   3. 追加写入目标的 .jsonl 文件
     *
     * 线程安全: synchronized 保证多个线程同时发消息时不会交错写入。
     * Thread-safe: synchronized prevents interleaved writes from multiple threads.
     *
     * @param from    发送者名称 / Sender name
     * @param to      接收者名称 / Recipient name
     * @param content 消息正文 / Message body
     */
    public synchronized void send(String from, String to, String content) {
        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("from", from);
            msg.addProperty("to", to);
            msg.addProperty("content", content);
            msg.addProperty("type", "message");
            msg.addProperty("timestamp",
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));

            // JSONL 核心: 将 JSON 压缩为单行并追加到收件箱文件
            // JSONL core: compress JSON to single line and append to inbox file
            Path inbox = inboxDir.resolve(to + ".jsonl");
            BufferedWriter w = Files.newBufferedWriter(inbox, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            try {
                w.write(GSON.toJson(msg).replace("\n", " "));
                w.newLine();
            } finally {
                w.close();
            }
        } catch (IOException e) {
            throw new RuntimeException("Error sending message: " + e.getMessage(), e);
        }
    }

    /**
     * 读取并清空指定 Agent 的收件箱（drain 语义）。
     * Read and clear the specified agent's inbox (drain semantics).
     *
     * "读取即消费"模式 / "Read-and-consume" pattern:
     *   1. 读取 .jsonl 文件的所有行 / Read all lines from .jsonl file
     *   2. 立即清空文件内容 / Immediately clear file contents
     *   3. 将每行 JSON 解析后收集到 List / Parse each JSON line into List
     *
     * 为什么是 drain 而非 peek / Why drain, not peek:
     *   - 避免消息重复处理 / Avoid duplicate processing
     *   - 简化状态管理: 不需要"已读"标记 / Simpler: no "read" markers needed
     *
     * @param agentId Agent 名称 / Agent name
     * @return 消息列表 (JsonObject)，无消息时返回空列表 / Message list, empty if no messages
     */
    public synchronized List<JsonObject> readInbox(String agentId) {
        List<JsonObject> result = new ArrayList<JsonObject>();
        Path inbox = inboxDir.resolve(agentId + ".jsonl");
        if (!Files.exists(inbox)) {
            return result;
        }
        try {
            List<String> lines = Files.readAllLines(inbox, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return result;
            }
            // drain: 清空收件箱，确保消息不会被重复消费
            // drain: clear inbox to prevent duplicate consumption
            Files.write(inbox, new byte[0]);
            // 逐行解析 JSONL，每行是一个独立的 JSON 对象
            // Parse each JSONL line as an independent JSON object
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    result.add(JsonParser.parseString(trimmed).getAsJsonObject());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading inbox: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 清空指定 Agent 的收件箱（不读取内容）。
     * Clear the specified agent's inbox without reading.
     *
     * @param agentId Agent 名称 / Agent name
     */
    public synchronized void clearInbox(String agentId) {
        Path inbox = inboxDir.resolve(agentId + ".jsonl");
        if (Files.exists(inbox)) {
            try {
                Files.write(inbox, new byte[0]);
            } catch (IOException e) {
                throw new RuntimeException("Error clearing inbox: " + e.getMessage(), e);
            }
        }
    }
}
