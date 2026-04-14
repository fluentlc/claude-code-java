package ai.claude.code.capability;

import ai.claude.code.core.OpenAiClient;
import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 上下文压缩器 (Context Compactor)
 *
 * 核心洞察 / Key Insight:
 *   "The agent can forget strategically and keep working forever."
 *   "智能体可以策略性地遗忘，从而永远工作下去。"
 *
 * 三层压缩管道 / Three-layer compression pipeline:
 *
 *   Layer 1 - microCompact (每轮 / every turn):
 *     替换旧的 tool result 内容为 "[Previous: used {tool_name}]"
 *     Replace old tool result content with "[Previous: used {tool_name}]"
 *     效果：防止历史工具输出无限膨胀
 *
 *   Layer 2 - auto compact (消息数 > 40 时自动触发):
 *     保存完整记录到 .transcripts/ 目录
 *     让 LLM 生成摘要替换消息列表
 *     Save full transcript to .transcripts/, ask LLM to summarize, replace messages
 *
 *   Layer 3 - compact tool (模型主动调用):
 *     模型觉得上下文太长时可以手动触发压缩
 *     Model can manually trigger compression when it feels context is too long
 *
 * ===== OpenAI 格式适配 =====
 * 与 Anthropic 协议的关键差异：
 * - 工具结果是独立的 role="tool" 消息，而非 user 消息中的 tool_result 块
 * - 工具调用在 assistant 消息的 tool_calls 数组中，而非 content 数组中的 tool_use 块
 * - 工具定义使用 OpenAI 的 {type:"function", function:{...}} 格式
 */
public class ContextCompactor {

    /** 微压缩保留最近几条 tool result 不压缩 */
    private static final int MICRO_COMPACT_KEEP_COUNT = 3;
    /** 消息数超过此阈值时触发自动全量压缩 */
    private static final int AUTO_COMPACT_THRESHOLD = 40;

    /** JSON 序列化工具，启用格式化输出便于调试 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** OpenAI API 客户端，用于调用 LLM 生成摘要（第二层压缩需要） */
    private final OpenAiClient client;

    /** 对话记录归档目录路径，用于在自动压缩时保存完整历史（便于审计和调试） */
    private final String transcriptsDir;

    /**
     * 构造上下文压缩器。
     *
     * @param client  OpenAI API 客户端实例
     * @param workDir 工作目录，.transcripts/ 子目录将在此下创建
     */
    public ContextCompactor(OpenAiClient client, String workDir) {
        this.client = client;
        this.transcriptsDir = workDir + "/.transcripts";
        // 确保 .transcripts/ 归档目录存在，不存在则递归创建
        new File(transcriptsDir).mkdirs();
    }

    // ==================================================================================
    // 第一层压缩：微压缩 (micro compact)
    // ==================================================================================

    /**
     * 第一层压缩：微压缩（micro compact）— 每一轮 Agent 循环都会调用。
     *
     * 核心策略：保留最近 3 条 role="tool" 消息的完整内容不变，把更早的 tool 消息
     * 中超过 100 字符的内容替换为简短的占位符文本（例如 "[Previous: used bash]"）。
     *
     * ===== OpenAI 格式差异 =====
     * Anthropic 中 tool_result 嵌套在 user 消息的 content 数组中；
     * OpenAI 中工具结果是独立的 role="tool" 消息，通过 tool_call_id 关联。
     * 因此这里直接扫描 role="tool" 的消息即可。
     *
     * @param messages 当前完整的消息列表
     * @return 经过微压缩的消息列表（deepCopy 被修改的消息）
     */
    public JsonArray microCompact(JsonArray messages) {
        // 消息太少（<= 4 条）时跳过压缩，因为还没有足够的历史值得压缩
        if (messages.size() <= 4) return messages;

        // 第一步：收集所有 role="tool" 消息的索引
        List<Integer> toolMsgIndices = new ArrayList<Integer>();
        for (int i = 0; i < messages.size(); i++) {
            JsonObject msg = messages.get(i).getAsJsonObject();
            if ("tool".equals(msg.get("role").getAsString())) {
                toolMsgIndices.add(i);
            }
        }

        // 保留最后 3 条 tool 消息不压缩
        int keepCount = MICRO_COMPACT_KEEP_COUNT;
        int compactCount = toolMsgIndices.size() - keepCount;
        if (compactCount <= 0) return messages;

        // 构建"需要压缩"的消息索引集合
        Set<Integer> toCompact = new HashSet<Integer>();
        for (int i = 0; i < compactCount; i++) {
            toCompact.add(toolMsgIndices.get(i));
        }

        // 第二步：遍历所有消息，对需要压缩的 tool 消息进行替换
        JsonArray result = new JsonArray();
        for (int i = 0; i < messages.size(); i++) {
            if (toCompact.contains(i)) {
                JsonObject msg = messages.get(i).getAsJsonObject();
                String content = "";
                if (msg.has("content") && !msg.get("content").isJsonNull()) {
                    content = msg.get("content").getAsString();
                }
                // 仅当内容超过 100 字符时才替换（短内容压缩收益太小）
                if (content.length() > 100) {
                    JsonObject placeholder = msg.deepCopy();
                    // 通过 tool_call_id 反向查找对应的工具名称
                    String toolCallId = msg.get("tool_call_id").getAsString();
                    String toolName = findToolName(messages, toolCallId);
                    placeholder.addProperty("content", "[Previous: used " + toolName + "]");
                    result.add(placeholder);
                } else {
                    result.add(msg);
                }
            } else {
                result.add(messages.get(i));
            }
        }

        return result;
    }

    /**
     * 辅助方法：从消息历史中根据 tool_call_id 反查工具名称。
     *
     * ===== OpenAI 格式 =====
     * OpenAI 中，assistant 消息的 tool_calls 数组包含工具调用信息：
     *   tool_calls[i].id — 对应 tool 消息的 tool_call_id
     *   tool_calls[i].function.name — 工具名称
     *
     * @param messages   完整的消息历史
     * @param toolCallId 要查找的 tool_call_id
     * @return 工具名称，找不到时返回 "unknown_tool"
     */
    private String findToolName(JsonArray messages, String toolCallId) {
        for (JsonElement msgEl : messages) {
            JsonObject msg = msgEl.getAsJsonObject();
            if (!"assistant".equals(msg.get("role").getAsString())) continue;

            if (!msg.has("tool_calls") || msg.get("tool_calls").isJsonNull()) continue;
            JsonArray toolCalls = msg.getAsJsonArray("tool_calls");

            for (JsonElement tcEl : toolCalls) {
                JsonObject tc = tcEl.getAsJsonObject();
                if (toolCallId.equals(tc.get("id").getAsString())) {
                    return tc.getAsJsonObject("function").get("name").getAsString();
                }
            }
        }
        return "unknown_tool";
    }

    // ==================================================================================
    // 第二层压缩：自动压缩 (auto compact)
    // ==================================================================================

    /**
     * 判断是否应该触发自动压缩。
     *
     * 当消息数超过 40 条时返回 true，表示上下文可能过长需要压缩。
     * 使用消息数量而非 token 估算，因为在 OpenAI 格式中每条 tool 消息独立计数，
     * 消息数量能更直观地反映对话复杂度。
     *
     * @param messages 当前消息列表
     * @return 是否需要自动压缩
     */
    public boolean shouldAutoCompact(JsonArray messages) {
        return messages.size() > AUTO_COMPACT_THRESHOLD;
    }

    /**
     * 第二层压缩：自动压缩（auto compact）— 当 shouldAutoCompact 返回 true 时触发。
     *
     * 这是一次"大规模压缩"：整个消息历史被 LLM 生成的摘要替换。
     *
     * 执行步骤：
     * 1. 将完整对话记录保存到 .transcripts/ 目录（不可逆操作前先备份）
     * 2. 调用 LLM 生成对话摘要（提取关键信息：用户目标、已完成工作、待办事项、文件路径）
     * 3. 用摘要替换整个消息列表（压缩为仅 2 条消息：摘要 + 确认）
     *
     * @param messages     当前完整的消息列表
     * @param systemPrompt 当前系统提示词，生成摘要时传给 LLM
     * @return 压缩后的消息列表（仅包含摘要和确认两条消息）
     */
    public JsonArray compact(JsonArray messages, String systemPrompt) {
        System.out.println("[Compact] Auto-compact triggered! Saving transcript and summarizing...");

        // 步骤 1：保存完整对话记录到文件（不可逆压缩前的备份）
        saveTranscript(messages);

        // 步骤 2：调用 LLM 对整个对话生成摘要
        String summary = generateSummary(messages, systemPrompt);
        System.out.println("[Compact] Summary: " + summary.substring(0, Math.min(200, summary.length())) + "...");

        // 步骤 3：构造压缩后的消息列表
        // 仅包含 2 条消息：一条 user 消息承载摘要内容，一条 assistant 消息表示确认
        JsonArray compacted = new JsonArray();
        compacted.add(OpenAiClient.userMessage(
                "[Context was compacted. Previous conversation summary:]\n" + summary
                        + "\n\n[Continue helping the user from where we left off.]"));
        compacted.add(OpenAiClient.assistantMessage(
                "Understood. I have the context from the summary. Continuing."));
        return compacted;
    }

    // ==================================================================================
    // 第三层压缩：compact 工具定义
    // ==================================================================================

    /**
     * 返回 compact 工具的定义（OpenAI 格式）。
     *
     * 这是暴露给 LLM 的工具，让模型自己判断何时需要压缩上下文。
     * 参数 focus 允许模型指定摘要时应重点保留的内容。
     *
     * ===== OpenAI 工具定义格式 =====
     * {
     *   "type": "function",
     *   "function": {
     *     "name": "compact",
     *     "description": "...",
     *     "parameters": { ... JSON Schema ... }
     *   }
     * }
     *
     * @return compact 工具的 JSON 定义（OpenAI 格式）
     */
    public static JsonObject getCompactToolDef() {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject focusProp = new JsonObject();
        focusProp.addProperty("type", "string");
        focusProp.addProperty("description", "What to preserve in the summary");
        properties.add("focus", focusProp);
        parameters.add("properties", properties);
        return OpenAiClient.toolDef(
                "compact",
                "Compact the conversation context to free up space. "
                        + "Use this when the conversation is getting too long "
                        + "or you notice repeated context.",
                parameters
        );
    }

    // ==================================================================================
    // 内部辅助方法
    // ==================================================================================

    /**
     * 将完整对话记录持久化到 .transcripts/ 目录。
     *
     * 文件命名格式：transcript_yyyyMMdd_HHmmss.json
     * 这样在自动压缩丢失对话细节后，仍可通过归档文件回溯完整历史。
     *
     * @param messages 要归档的完整消息列表
     */
    private void saveTranscript(JsonArray messages) {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String filename = transcriptsDir + "/transcript_" + timestamp + ".json";
            Files.write(Paths.get(filename),
                    GSON.toJson(messages).getBytes(StandardCharsets.UTF_8));
            System.out.println("[Compact] Transcript saved to: " + filename);
        } catch (IOException e) {
            System.err.println("[Compact] Failed to save transcript: " + e.getMessage());
        }
    }

    /**
     * 调用 LLM 生成对话摘要。
     *
     * 摘要 prompt 要求 LLM 聚焦三个维度：
     * (1) 用户的原始需求是什么
     * (2) 目前已完成了什么
     * (3) 还有哪些待办事项或重要上下文
     * 并且要求保留文件路径和关键决策信息（这些是恢复工作状态的关键）。
     *
     * 如果 LLM 调用失败，会降级为一个简单的手动摘要（包含消息数量）。
     *
     * @param messages     要摘要的完整消息列表
     * @param systemPrompt 系统提示词
     * @return 摘要字符串
     */
    private String generateSummary(JsonArray messages, String systemPrompt) {
        // 摘要 prompt：指导 LLM 提取最关键的信息
        String summaryPrompt = "Summarize this conversation concisely. "
                + "Focus on: (1) what the user asked for, (2) what was accomplished, "
                + "(3) any pending tasks or important context. "
                + "Keep file paths and key decisions.";

        JsonArray summaryMessages = new JsonArray();
        summaryMessages.add(OpenAiClient.userMessage(
                summaryPrompt + "\n\nConversation:\n" + GSON.toJson(messages)));

        try {
            JsonObject response = client.createMessage(
                    "You are a conversation summarizer.", summaryMessages, null, 2048);
            JsonObject assistantMsg = OpenAiClient.getAssistantMessage(response);
            return OpenAiClient.extractText(assistantMsg);
        } catch (Exception e) {
            // 降级策略：LLM 摘要失败时，生成一个包含基本信息的兜底摘要
            return "Previous conversation had " + messages.size() + " messages. "
                    + "(Summary generation failed: " + e.getMessage() + ")";
        }
    }
}
