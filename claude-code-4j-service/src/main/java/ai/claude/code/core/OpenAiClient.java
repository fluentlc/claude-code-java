package ai.claude.code.core;

import com.google.gson.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 原生 JDK 1.8 实现的 OpenAI Chat Completions API 客户端。
 * 兼容所有遵循 OpenAI 协议的服务（OpenAI、Azure OpenAI、本地 Ollama、One-API 等）。
 *
 * ===== 与 AnthropicClient 的核心区别 =====
 *
 * 1. 认证方式不同：
 *    - Anthropic: 请求头 x-api-key
 *    - OpenAI:    请求头 Authorization: Bearer <key>
 *
 * 2. 系统提示词位置不同：
 *    - Anthropic: createMessage(systemPrompt, messages, ...) → 独立的 "system" 字段
 *    - OpenAI:    系统提示词作为 {"role":"system"} 消息放在 messages 数组的第一条
 *
 * 3. 工具结果的角色不同：
 *    - Anthropic: tool_result 放在 user 消息的 content 数组中
 *    - OpenAI:    工具结果用独立的 role="tool" 消息，带 tool_call_id 字段
 *
 * 4. 工具定义结构不同：
 *    - Anthropic: {"name":..., "description":..., "input_schema":{...}}
 *    - OpenAI:    {"type":"function", "function":{"name":..., "description":..., "parameters":{...}}}
 *
 * 5. 工具调用在响应中的位置不同：
 *    - Anthropic: response.content[] 中的 {type:"tool_use"} block
 *    - OpenAI:    response.choices[0].message.tool_calls[] 数组
 *
 * 6. 停止原因字段名不同：
 *    - Anthropic: response.stop_reason = "tool_use" / "end_turn"
 *    - OpenAI:    response.choices[0].finish_reason = "tool_calls" / "stop"
 *
 * ===== 配置文件 =====
 * 读取 claude.properties，使用以下配置项：
 *   OPENAI_API_KEY     — API 密钥（必填）
 *   OPENAI_BASE_URL    — API 地址，默认 https://api.openai.com（可替换为兼容服务）
 *   OPENAI_MODEL_ID    — 模型 ID，如 gpt-4o（必填）
 *   DEBUG_PRINT_PAYLOAD — true/false，是否打印请求/响应报文
 */
@SuppressWarnings("JavadocBlankLines")
public class OpenAiClient {

    private static final String CONFIG_FILE = "claude.properties";
    private static final Properties CONFIG = loadConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final boolean debugPrintPayload;
    /** 额外的请求头，如 x-platform: dashscope */
    private final Map<String, String> extraHeaders;
    /** API 路径，默认 /v1/chat/completions，可替换为兼容服务的其他路径 */
    private final String apiPath;

    private static Properties loadConfig() {
        Properties props = new Properties();
        InputStream is = OpenAiClient.class.getClassLoader().getResourceAsStream(CONFIG_FILE);
        if (is != null) {
            try {
                props.load(is);
                is.close();
            } catch (IOException e) {
                System.err.println("[WARN] Failed to load " + CONFIG_FILE + ": " + e.getMessage());
            }
        }
        return props;
    }

    private static String getConfig(String key, String defaultValue) {
        // 优先级：环境变量 > claude.properties > 默认值
        String envVal = System.getenv(key);
        if (envVal != null && !envVal.trim().isEmpty()) return envVal.trim();
        String propVal = CONFIG.getProperty(key);
        if (propVal != null && !propVal.trim().isEmpty()) return propVal.trim();
        return defaultValue;
    }

    /** 从 claude.properties 读取配置的默认构造器。优先使用 ClientFactory 创建实例。 */
    public OpenAiClient() {
        this.baseUrl = getConfig("OPENAI_BASE_URL", "https://api.openai.com")
                .replaceAll("/+$", "");
        this.apiKey = getConfig("OPENAI_API_KEY", getConfig("OPENAI_AUTH_TOKEN", ""));
        this.model = getConfig("OPENAI_MODEL_ID", "gpt-4o");
        this.debugPrintPayload = Boolean.parseBoolean(getConfig("DEBUG_PRINT_PAYLOAD", "false"));
        this.extraHeaders = Collections.<String, String>emptyMap();
        this.apiPath = "/v1/chat/completions";

        System.out.println("[OpenAiClient] base_url = " + baseUrl);
        System.out.println("[OpenAiClient] model    = " + model);
        System.out.println("[OpenAiClient] api_key  = " +
                (apiKey.isEmpty() ? "(NOT SET!)" : apiKey.substring(0, Math.min(8, apiKey.length())) + "..."));
        System.out.println("[OpenAiClient] debug    = " + debugPrintPayload);
    }

    /**
     * 显式传参构造器 —— 用于需要自定义 Header 或 API 路径的场景（如 DashScope、Azure 等）。
     *
     * @param apiKey       API 密钥
     * @param baseUrl      服务基础地址（不含路径），如 https://api.openai.com
     * @param model        模型 ID，如 qwen3-plus
     * @param extraHeaders 额外请求头，如 {"x-platform": "dashscope"}，可传 null
     * @param apiPath      API 路径，如 /v1/completion；传 null 则使用默认 /v1/chat/completions
     */
    public OpenAiClient(String apiKey, String baseUrl, String model,
                        Map<String, String> extraHeaders, String apiPath) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.debugPrintPayload = Boolean.parseBoolean(getConfig("DEBUG_PRINT_PAYLOAD", "false"));
        this.extraHeaders = extraHeaders != null ? extraHeaders : Collections.<String, String>emptyMap();
        this.apiPath = apiPath != null ? apiPath : "/v1/chat/completions";

        System.out.println("[OpenAiClient] base_url = " + this.baseUrl);
        System.out.println("[OpenAiClient] model    = " + this.model);
        System.out.println("[OpenAiClient] api_key  = " +
                (apiKey.isEmpty() ? "(NOT SET!)" : apiKey.substring(0, Math.min(8, apiKey.length())) + "..."));
        System.out.println("[OpenAiClient] extra_headers = " + this.extraHeaders.keySet());
        System.out.println("[OpenAiClient] api_path = " + this.apiPath);
    }

    public String getModel() {
        return model;
    }

    /**
     * 调用 OpenAI Chat Completions API。
     *
     * 接口签名与 AnthropicClient.createMessage 保持一致，方便对比学习。
     *
     * ===== OpenAI 请求结构 =====
     * {
     *   "model": "gpt-4o",
     *   "messages": [
     *     {"role": "system", "content": "You are a coding agent..."},  ← 系统提示词作为第一条消息
     *     {"role": "user",   "content": "帮我列文件"},
     *     {"role": "assistant", "content": null,
     *      "tool_calls": [{"id":"tc_1","type":"function","function":{"name":"bash","arguments":"{...}"}}]},
     *     {"role": "tool", "tool_call_id": "tc_1", "content": "file1.txt\nfile2.java"}
     *   ],
     *   "tools": [{"type":"function","function":{"name":"bash","description":"...","parameters":{...}}}],
     *   "max_tokens": 8000
     * }
     *
     * @param systemPrompt 系统提示词 — 会被包装成 role="system" 的消息插入 messages 头部
     * @param messages     对话消息列表（OpenAI 格式，用本类的静态方法构建）
     * @param tools        工具定义列表（OpenAI 格式，用 toolDef() 构建），可为 null
     * @param maxTokens    最大输出 token 数
     * @return API 原始响应 JsonObject，含 choices[0].message 和 choices[0].finish_reason
     */
    public JsonObject createMessage(String systemPrompt, JsonArray messages,
                                    JsonArray tools, int maxTokens) {
        // 将 systemPrompt 插入 messages 头部作为 system 角色消息
        // 注意：每次调用都重建带 system 的数组，不修改传入的 messages（避免副作用）
        JsonArray fullMessages = new JsonArray();
        fullMessages.add(systemMessage(systemPrompt));
        for (JsonElement el : messages) {
            fullMessages.add(stripPrivateFields(el));
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", fullMessages);
        if (tools != null && tools.size() > 0) {
            body.add("tools", tools);
        }
        body.addProperty("max_tokens", maxTokens);

        String responseStr = doPost(apiPath, GSON.toJson(body));
        return JsonParser.parseString(responseStr).getAsJsonObject();
    }

    /**
     * Streaming variant of createMessage(). Fires listener events for each chunk.
     * Returns the assembled JsonObject in the same shape as createMessage()
     * so AgentLoop can process tool_calls and finish_reason identically.
     *
     * @param listener event callbacks; must not be null
     * @return assembled response JsonObject (choices[0].message + choices[0].finish_reason)
     */
    public JsonObject createMessageStream(String systemPrompt, JsonArray messages,
                                          JsonArray tools, int maxTokens,
                                          AgentEventListener listener) {
        JsonArray fullMessages = new JsonArray();
        fullMessages.add(systemMessage(systemPrompt));
        for (JsonElement el : messages) fullMessages.add(stripPrivateFields(el));

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", fullMessages);
        if (tools != null && tools.size() > 0) body.add("tools", tools);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("stream", true);

        return doPostStream(apiPath, GSON.toJson(body), listener);
    }

    private JsonObject doPostStream(String path, String jsonBody, AgentEventListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(300_000);  // 5-minute read timeout for long tool chains
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }

            if (debugPrintPayload) System.out.println("=====流式请求报文=====\n" + jsonBody);

            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream errIs = conn.getErrorStream();
                StringBuilder errSb = new StringBuilder();
                if (errIs != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(errIs, StandardCharsets.UTF_8))) {
                        String l; while ((l = br.readLine()) != null) errSb.append(l);
                    }
                }
                throw new RuntimeException("API Error (HTTP " + status + "): " + errSb);
            }

            // Accumulators for assembling the complete response
            StringBuilder textAccum      = new StringBuilder();
            StringBuilder reasoningAccum = new StringBuilder();
            long          reasoningMs    = 0;
            // tool_calls[index] -> partial JsonObject being assembled
            java.util.Map<Integer, JsonObject> toolCallMap = new java.util.LinkedHashMap<>();
            // tool_calls[index] -> arguments string builder
            java.util.Map<Integer, StringBuilder> argsMap  = new java.util.LinkedHashMap<>();
            String finishReason = "";
            JsonObject usageObj = null;

            long thinkingStart = 0;
            boolean inThinking = false;

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;

                    JsonObject chunk;
                    try { chunk = JsonParser.parseString(data).getAsJsonObject(); }
                    catch (Exception ex) { continue; }

                    if (debugPrintPayload) System.out.println("[stream] " + data);

                    // Extract usage if present
                    if (chunk.has("usage") && !chunk.get("usage").isJsonNull()) {
                        usageObj = chunk.getAsJsonObject("usage");
                    }

                    JsonArray choices = chunk.has("choices") ? chunk.getAsJsonArray("choices") : new JsonArray();
                    if (choices.size() == 0) continue;
                    JsonObject choice = choices.get(0).getAsJsonObject();

                    if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                        finishReason = choice.get("finish_reason").getAsString();
                    }

                    JsonObject delta = choice.has("delta") ? choice.getAsJsonObject("delta") : new JsonObject();

                    // --- thinking / reasoning field (Claude / o1 / Qwen3 style) ---
                    // Claude uses "reasoning", Qwen3/o1 uses "reasoning_content"
                    String reasoningField = delta.has("reasoning_content") ? "reasoning_content"
                            : delta.has("reasoning") ? "reasoning" : null;
                    if (reasoningField != null && !delta.get(reasoningField).isJsonNull()) {
                        String thinkText = delta.get(reasoningField).getAsString();
                        if (!inThinking) {
                            inThinking = true;
                            thinkingStart = System.currentTimeMillis();
                            listener.onThinkingStart();
                        }
                        reasoningAccum.append(thinkText);
                        listener.onThinkingText(thinkText);
                    }

                    // --- text content ---
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String text = delta.get("content").getAsString();
                        if (!text.isEmpty()) {
                            if (inThinking) {
                                // Transition: thinking ended, text started
                                inThinking = false;
                                reasoningMs = System.currentTimeMillis() - thinkingStart;
                                listener.onThinkingEnd(reasoningMs);
                            }
                            textAccum.append(text);
                            listener.onTextDelta(text);
                        }
                    }

                    // --- tool_calls deltas ---
                    if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                        for (JsonElement tcEl : delta.getAsJsonArray("tool_calls")) {
                            JsonObject tcDelta = tcEl.getAsJsonObject();
                            int idx = tcDelta.has("index") ? tcDelta.get("index").getAsInt() : 0;

                            if (!toolCallMap.containsKey(idx)) {
                                toolCallMap.put(idx, new JsonObject());
                                argsMap.put(idx, new StringBuilder());
                            }
                            JsonObject tc = toolCallMap.get(idx);

                            if (tcDelta.has("id") && !tcDelta.get("id").isJsonNull()) {
                                String tcId = tcDelta.get("id").getAsString();
                                // Some models (e.g. Qwen) return empty-string ids — fall back to a UUID
                                if (tcId.isEmpty()) tcId = "tc-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "-" + idx;
                                tc.addProperty("id", tcId);
                                tc.addProperty("type", "function");
                            }
                            if (tcDelta.has("function")) {
                                JsonObject fnDelta = tcDelta.getAsJsonObject("function");
                                if (fnDelta.has("name") && !fnDelta.get("name").isJsonNull()) {
                                    if (!tc.has("function")) tc.add("function", new JsonObject());
                                    tc.getAsJsonObject("function").addProperty("name", fnDelta.get("name").getAsString());
                                }
                                if (fnDelta.has("arguments") && !fnDelta.get("arguments").isJsonNull()) {
                                    argsMap.get(idx).append(fnDelta.get("arguments").getAsString());
                                }
                            }
                        }
                    }
                }
            }

            if (inThinking) {
                reasoningMs = System.currentTimeMillis() - thinkingStart;
                listener.onThinkingEnd(reasoningMs);
            }

            // Finalize tool_calls: set assembled arguments
            JsonArray toolCallsArray = new JsonArray();
            for (Map.Entry<Integer, JsonObject> e : toolCallMap.entrySet()) {
                JsonObject tc = e.getValue();
                String argsStr = argsMap.get(e.getKey()).toString();
                if (!tc.has("function")) tc.add("function", new JsonObject());
                tc.getAsJsonObject("function").addProperty("arguments", argsStr);
                // Guarantee every tool call has a non-empty id (some models omit it entirely)
                if (!tc.has("id") || tc.get("id").getAsString().isEmpty()) {
                    tc.addProperty("id", "tc-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "-" + e.getKey());
                }
                toolCallsArray.add(tc);
            }

            // Assemble response in the same shape createMessage() returns.
            // When tool_calls are present, set content to null per OpenAI spec
            // (finish_reason=tool_calls implies content is null). Keeping text
            // alongside tool_calls confuses some models in subsequent rounds.
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            if (toolCallsArray.size() > 0) {
                message.add("content", com.google.gson.JsonNull.INSTANCE);
                message.add("tool_calls", toolCallsArray);
                // Preserve interleaved text (model streamed text before tool_calls) for display only
                if (textAccum.length() > 0) {
                    message.addProperty("_content", textAccum.toString());
                }
            } else if (textAccum.length() > 0) {
                message.addProperty("content", textAccum.toString());
            } else {
                message.add("content", com.google.gson.JsonNull.INSTANCE);
            }
            // Store reasoning/thinking content for session persistence (display only, stripped before API calls)
            if (reasoningAccum.length() > 0) {
                message.addProperty("_reasoning", reasoningAccum.toString());
                if (reasoningMs > 0) message.addProperty("_reasoning_ms", reasoningMs);
            }

            JsonObject choiceObj = new JsonObject();
            choiceObj.add("message", message);
            choiceObj.addProperty("finish_reason", finishReason);

            JsonArray choicesArr = new JsonArray();
            choicesArr.add(choiceObj);

            JsonObject response = new JsonObject();
            response.add("choices", choicesArr);
            if (usageObj != null) response.add("usage", usageObj);

            return response;

        } catch (IOException e) {
            throw new RuntimeException("Stream request failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String doPost(String path, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            conn.setRequestProperty("Content-Type", "application/json");
            // OpenAI 使用标准 Bearer Token 认证，而非 Anthropic 的自定义 x-api-key 头
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            // 注入额外请求头（如 x-platform: dashscope）
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }

            if (debugPrintPayload) {
                System.out.println("=====请求报文=====\n" + jsonBody);
            }
            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }

            if (status < 200 || status >= 300) {
                throw new RuntimeException("API Error (HTTP " + status + "): " + sb.toString());
            }
            String responseStr = sb.toString();
            if (debugPrintPayload) {
                try {
                    JsonObject json = JsonParser.parseString(responseStr).getAsJsonObject();
                    System.out.println("=====响应报文=====\n" + GSON.toJson(json));
                } catch (Exception e) {
                    System.out.println("=====响应报文=====\n" + responseStr);
                }
            }
            return responseStr;

        } catch (IOException e) {
            throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================================================================================
    // 静态工具方法：构建 OpenAI 格式的消息和工具定义
    //
    // ===== OpenAI 消息结构 vs Anthropic 消息结构 =====
    //
    // Anthropic:
    //   user:      {"role":"user", "content": "文本" 或 [{type:"tool_result",...}]}
    //   assistant: {"role":"assistant", "content": [{type:"text",...}, {type:"tool_use",...}]}
    //
    // OpenAI:
    //   system:    {"role":"system", "content": "文本"}           ← Anthropic 没有此角色
    //   user:      {"role":"user", "content": "文本"}
    //   assistant: {"role":"assistant", "content":null/文本, "tool_calls":[...]}
    //   tool:      {"role":"tool", "tool_call_id":"...", "content":"结果文本"}  ← Anthropic 没有此角色
    // ==================================================================================

    /** 构建 system 角色消息（Anthropic 中用独立的 system 字段，OpenAI 中是一条消息）*/
    public static JsonObject systemMessage(String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "system");
        msg.addProperty("content", text);
        return msg;
    }

    /** 构建 user 角色消息（纯文本）*/
    public static JsonObject userMessage(String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", text);
        return msg;
    }

    /**
     * 构建 assistant 角色消息（纯文本，无工具调用）。
     * 用于手动构造对话历史。
     */
    public static JsonObject assistantMessage(String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("content", text);
        return msg;
    }

    /**
     * 构建含工具调用的 assistant 消息，直接取自 API 响应中的 message 对象。
     *
     * ===== 为什么要原样保留 API 返回的 message？ =====
     * OpenAI API 要求：如果 assistant 发起了工具调用（tool_calls 字段），
     * 下一轮请求必须把这条 assistant message 原样放回 messages 数组，
     * 否则 API 会因找不到 tool_call_id 对应的调用记录而报错。
     *
     * 用法：messages.add(OpenAiClient.assistantMessage(getAssistantMessage(response)));
     */
    public static JsonObject assistantMessage(JsonObject apiResponseMessage) {
        // 直接返回 API 响应中的 message 对象（role、content、tool_calls 字段都已包含）
        return apiResponseMessage;
    }

    /**
     * 构建 tool 角色消息 —— 工具执行结果的载体。
     *
     * ===== 与 Anthropic 的关键区别 =====
     * Anthropic: tool_result 放在 user 消息的 content 数组中
     *   {"role":"user", "content":[{"type":"tool_result","tool_use_id":"tu_1","content":"结果"}]}
     *
     * OpenAI: 工具结果是独立的 role="tool" 消息
     *   {"role":"tool", "tool_call_id":"tc_1", "content":"结果"}
     *
     * 字段名也不同：Anthropic 用 tool_use_id，OpenAI 用 tool_call_id。
     *
     * @param toolCallId 对应 assistant message 中 tool_calls[i].id 的值
     * @param content    工具执行结果文本
     */
    public static JsonObject toolResultMessage(String toolCallId, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "tool");
        msg.addProperty("tool_call_id", toolCallId);
        msg.addProperty("content", content);
        return msg;
    }

    // ==================================================================================
    // 响应解析工具方法
    // ==================================================================================

    /**
     * 从响应中提取 finish_reason。
     *
     * OpenAI finish_reason 的可能值：
     * - "tool_calls" → LLM 要调用工具（对应 Anthropic 的 "tool_use"）
     * - "stop"       → 正常结束（对应 Anthropic 的 "end_turn"）
     * - "length"     → 达到 max_tokens 限制（对应 Anthropic 的 "max_tokens"）
     * - "content_filter" → 内容被过滤
     */
    public static String getStopReason(JsonObject response) {
        try {
            return response.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .get("finish_reason").getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从响应中提取 assistant 的 message 对象。
     *
     * OpenAI 响应结构：
     * {
     *   "choices": [{
     *     "message": {
     *       "role": "assistant",
     *       "content": "文本（无工具调用时）或 null（有工具调用时）",
     *       "tool_calls": [{"id":"tc_1","type":"function","function":{"name":"bash","arguments":"{...}"}}]
     *     },
     *     "finish_reason": "tool_calls"
     *   }]
     * }
     */
    public static JsonObject getAssistantMessage(JsonObject response) {
        try {
            return response.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message");
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    /**
     * 从 assistant message 中提取 tool_calls 数组。
     * 若没有工具调用（finish_reason="stop"），返回空数组。
     */
    public static JsonArray getToolCalls(JsonObject assistantMessage) {
        if (assistantMessage.has("tool_calls")
                && !assistantMessage.get("tool_calls").isJsonNull()) {
            return assistantMessage.getAsJsonArray("tool_calls");
        }
        return new JsonArray();
    }

    /**
     * 从 assistant message 中提取纯文本内容。
     * 若 content 为 null（发生了工具调用），返回空字符串。
     */
    public static String extractText(JsonObject assistantMessage) {
        if (assistantMessage.has("content")
                && !assistantMessage.get("content").isJsonNull()) {
            return assistantMessage.get("content").getAsString();
        }
        return "";
    }

    // ==================================================================================
    // 工具定义构建方法
    //
    // ===== Anthropic vs OpenAI 工具定义结构对比 =====
    //
    // Anthropic:
    // {
    //   "name": "bash",
    //   "description": "Run a shell command.",
    //   "input_schema": {"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}
    // }
    //
    // OpenAI（多了一层 "type":"function" 和 "function" 包装，参数字段名也从 input_schema 改为 parameters）:
    // {
    //   "type": "function",
    //   "function": {
    //     "name": "bash",
    //     "description": "Run a shell command.",
    //     "parameters": {"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}
    //   }
    // }
    // ==================================================================================

    /**
     * Strip private/display-only fields (prefixed with "_") from a message element
     * before sending to the API. Works on JsonObject messages; passes through other types unchanged.
     * Creates a shallow copy so the original session message is not modified.
     */
    private static JsonElement stripPrivateFields(JsonElement el) {
        if (!el.isJsonObject()) return el;
        JsonObject src = el.getAsJsonObject();
        boolean hasPrivate = false;
        for (String key : src.keySet()) {
            if (key.startsWith("_")) { hasPrivate = true; break; }
        }
        if (!hasPrivate) return el;
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : src.entrySet()) {
            if (!entry.getKey().startsWith("_")) copy.add(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    /**
     * 构建 OpenAI 格式的工具定义。
     * 注意：参数字段名是 "parameters" 而非 Anthropic 的 "input_schema"。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param parameters  JSON Schema（可直接复用 AnthropicClient.schema() 生成的对象）
     */
    public static JsonObject toolDef(String name, String description, JsonObject parameters) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);  // 注意：OpenAI 用 "parameters"，Anthropic 用 "input_schema"

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");    // OpenAI 比 Anthropic 多一层 type:"function" 包装
        tool.add("function", function);
        return tool;
    }

    /**
     * 快速构建 JSON Schema —— 与 AnthropicClient.schema() 签名完全相同，可复用。
     * 参数以三元组传入：(参数名, 类型, 是否必需)，如 schema("command","string","true")
     */
    public static JsonObject schema(String... nameTypePairs) {
        return AnthropicClient.schema(nameTypePairs);
    }
}
