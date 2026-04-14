package ai.claude.code.core;

import com.google.gson.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 原生 JDK 1.8 实现的 Anthropic Messages API 客户端。
 * 不依赖任何第三方 HTTP 库，仅使用 HttpURLConnection。
 *
 * ===== 设计决策 =====
 * 为什么不用 OkHttp、Apache HttpClient 等第三方库？
 * 因为本项目的目标是"最小依赖、最清晰的实现"，让学习者聚焦于 Claude API 的交互协议本身，
 * 而不是被 HTTP 库的 API 分散注意力。JDK 自带的 HttpURLConnection 完全够用。
 *
 * 为什么用 Gson 而不是 Jackson？
 * Gson 的 JsonObject/JsonArray 可以像 Python dict/list 一样灵活操作，
 * 不需要定义 POJO 类，更贴近 Python 版的动态风格。
 *
 * ===== 在 Claude Code 真实实现中的对应 =====
 * Claude Code CLI 使用 TypeScript 的 @anthropic-ai/sdk，
 * 该 SDK 内部封装了 HTTP 调用、重试、流式响应等逻辑。
 * 本类相当于该 SDK 的极简 Java 版本。
 *
 * ===== 配置加载优先级（高 → 低）=====
 *   1. 环境变量（export ANTHROPIC_API_KEY=...） — 适合 CI/CD 和生产环境
 *   2. classpath 下的 claude.properties 配置文件 — 适合本地开发
 *   3. 内置默认值 — 兜底方案
 *
 * 对应 Python 版中的: client = Anthropic() + load_dotenv()
 * Python SDK 会自动从环境变量读取 ANTHROPIC_API_KEY，
 * load_dotenv() 则从 .env 文件加载，和这里的 claude.properties 等价。
 */
@SuppressWarnings("JavadocBlankLines")
public class AnthropicClient {

    /** 配置文件名，放在 src/main/resources 下，classpath 可直接加载 */
    private static final String CONFIG_FILE = "claude.properties";

    /**
     * 静态加载配置 —— 类加载时就执行一次，保证整个 JVM 生命周期内只读取一次配置文件。
     * 这里用 static 初始化是因为配置在运行期间不会变，没必要每次都重新读取。
     */
    private static final Properties CONFIG = loadConfig();

    /** API 密钥，用于请求头 x-api-key 认证 */
    private final String apiKey;

    /**
     * API 基础 URL，默认指向 Anthropic 官方地址。
     * 可以通过环境变量 ANTHROPIC_BASE_URL 指向代理服务器（如 one-api、openrouter 等），
     * 这是国内开发者常用的做法 —— 通过中转服务访问 Anthropic API。
     */
    private final String baseUrl;

    /** 模型 ID，如 "claude-sonnet-4-20250514"，决定了使用哪个版本的 Claude */
    private final String model;

    /** 是否打印请求/响应报文，用于调试，默认 false */
    private final boolean debugPrintPayload;

    /**
     * Gson 实例，开启 PrettyPrinting 是为了调试时日志可读性更好。
     * 设为 static final 是因为 Gson 是线程安全的，全局共享一个实例即可。
     */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 从 classpath 加载 claude.properties 配置文件。
     * 对应 Python 版中的 load_dotenv() —— 从 .env 文件加载环境变量。
     *
     * 为什么不用 .env 文件格式？
     * Java 没有原生的 .env 解析器，而 Properties 是 JDK 内置的，
     * 格式也是 key=value，功能完全等价，无需引入额外依赖。
     *
     * 注意：这里的 getClassLoader().getResourceAsStream() 会在 classpath 中查找，
     * 所以 claude.properties 文件应该放在 src/main/resources/ 目录下。
     */
    private static Properties loadConfig() {
        Properties props = new Properties();
        InputStream is = AnthropicClient.class.getClassLoader().getResourceAsStream(CONFIG_FILE);
        if (is != null) {
            try {
                props.load(is);
                is.close();
            } catch (IOException e) {
                // 配置文件加载失败不应该中断程序，因为还可以通过环境变量获取配置
                System.err.println("[WARN] Failed to load " + CONFIG_FILE + ": " + e.getMessage());
            }
        }
        return props;
    }

    /**
     * 读取配置项：环境变量优先，其次 claude.properties，最后使用默认值。
     *
     * 三级回退策略（Fallback Pattern）的设计理由：
     * - 环境变量最高优先级：适合 Docker/K8s/CI 环境，无需修改代码或配置文件
     * - 配置文件次优先：适合本地开发，把 API Key 写在 claude.properties 里避免每次 export
     * - 默认值兜底：确保程序不会因为缺少配置而 NPE，至少能启动并给出有意义的错误信息
     *
     * 这种模式在实际项目中非常常见，Spring Boot 的配置加载也遵循类似的优先级。
     */
    private static String getConfig(String key, String defaultValue) {
        // 1. 环境变量优先 —— 最灵活，不需要修改任何文件
//        String envVal = System.getenv(key);
//        if (envVal != null && !envVal.trim().isEmpty()) {
//            return envVal.trim();
//        }
        // 2. 配置文件 —— 本地开发时最方便
        String propVal = CONFIG.getProperty(key);
        if (propVal != null && !propVal.trim().isEmpty()) {
            return propVal.trim();
        }
        // 3. 默认值 —— 兜底
        return defaultValue;
    }

    /**
     * 无参构造器 —— 自动从环境变量/配置文件加载所有配置。
     *
     * 为什么 API Key 要支持两个变量名 (ANTHROPIC_API_KEY 和 ANTHROPIC_AUTH_TOKEN)？
     * 因为不同的代理服务和部署环境可能使用不同的变量名，
     * 支持两个变量名可以兼容更多场景，减少用户的配置烦恼。
     *
     * 为什么要用 replaceAll("/+$", "") 去掉 baseUrl 末尾的斜杠？
     * 因为后面拼接路径时会加 "/v1/messages"，如果 baseUrl 末尾已有斜杠，
     * 就会变成 "https://api.anthropic.com//v1/messages"，双斜杠可能导致请求失败。
     */
    public AnthropicClient() {
        // 去掉末尾斜杠，避免拼接 URL 时出现双斜杠
        this.baseUrl = getConfig("ANTHROPIC_BASE_URL", "https://api.anthropic.com")
                .replaceAll("/+$", "");
        // 优先使用 ANTHROPIC_API_KEY，其次尝试 ANTHROPIC_AUTH_TOKEN（某些代理服务使用此变量名）
        this.apiKey = getConfig("ANTHROPIC_API_KEY",
                getConfig("ANTHROPIC_AUTH_TOKEN", ""));
        this.model = getConfig("MODEL_ID", "");
        // 读取调试开关，默认不打印报文
        this.debugPrintPayload = Boolean.parseBoolean(getConfig("DEBUG_PRINT_PAYLOAD", "false"));

        // 启动时打印配置来源，方便调试 —— 生产环境中应该用日志框架替代 System.out
        // 注意：API Key 只打印前 8 位，这是安全最佳实践，防止密钥泄露到日志中
        System.out.println("[AnthropicClient] base_url = " + baseUrl);
        System.out.println("[AnthropicClient] model    = " + model);
        System.out.println("[AnthropicClient] api_key  = " +
                (apiKey.isEmpty() ? "(NOT SET!)" : apiKey.substring(0, Math.min(8, apiKey.length())) + "..."));
        System.out.println("[AnthropicClient] debug    = " + debugPrintPayload);
    }

    public String getModel() {
        return model;
    }

    /**
     * 调用 Anthropic Messages API —— 整个客户端的核心方法。
     *
     * 对应 Python 版中的:
     *   response = client.messages.create(
     *       model=model, system=system_prompt,
     *       messages=messages, tools=tools, max_tokens=max_tokens
     *   )
     *
     * ===== Messages API 的请求结构 =====
     * {
     *   "model": "claude-sonnet-4-20250514",    // 使用的模型
     *   "system": "You are a coding agent...",   // 系统提示词（设定 AI 的角色和行为）
     *   "messages": [...],                       // 对话历史（user/assistant 交替）
     *   "tools": [...],                          // 可用工具定义（可选）
     *   "max_tokens": 8000                       // 最大生成 token 数
     * }
     *
     * ===== 关键设计 =====
     * - tools 为 null 时不传该字段 —— 这样可以在不需要工具时（如简单对话）省略
     * - 返回原始 JsonObject —— 不做任何包装，让调用方自由解析，保持灵活性
     *
     * @param systemPrompt  系统提示词 —— 告诉 Claude 扮演什么角色
     * @param messages      对话消息列表 (JsonArray) —— 完整的对话历史
     * @param tools         工具定义列表 (JsonArray, 可为 null) —— Claude 可以调用的工具
     * @param maxTokens     最大输出 token 数 —— 限制单次回复的长度
     * @return API 响应的 JsonObject，包含 content、stop_reason 等字段
     */
    public JsonObject createMessage(String systemPrompt, JsonArray messages,
                                    JsonArray tools, int maxTokens) {
        // 构建 API 请求体 —— 对应 Messages API 的 JSON 结构
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("system", systemPrompt);
        body.add("messages", messages);
        // tools 为空时不传，让 API 知道这次调用不需要工具能力
        if (tools != null && tools.size() > 0) {
            body.add("tools", tools);
        }
        body.addProperty("max_tokens", maxTokens);

        // 发送 POST 请求并解析响应
        String responseStr = doPost("/v1/messages", GSON.toJson(body));
        return JsonParser.parseString(responseStr).getAsJsonObject();
    }

    /**
     * 底层 HTTP POST 方法 —— 负责与 Anthropic API 服务器通信。
     *
     * ===== 为什么用 HttpURLConnection 而不是更现代的 HttpClient？ =====
     * java.net.http.HttpClient 是 JDK 11 引入的，本项目要求 JDK 1.8 兼容，
     * 所以只能用 HttpURLConnection。虽然 API 比较原始，但功能完全足够。
     *
     * ===== 超时设置的考量 =====
     * - connectTimeout = 30s：建立 TCP 连接的超时，通常几秒内就能完成
     * - readTimeout = 120s：等待响应的超时，LLM 推理可能需要较长时间（特别是复杂任务），
     *   所以设为 2 分钟。Claude Code 真实实现中也有类似的较长超时设置。
     *
     * ===== Anthropic API 认证方式 =====
     * 使用 x-api-key 请求头传递 API Key，这是 Anthropic 的标准认证方式。
     * anthropic-version 头指定 API 版本，确保请求/响应格式的兼容性。
     *
     * @param path     API 路径，如 "/v1/messages"
     * @param jsonBody 请求体的 JSON 字符串
     * @return 响应体的 JSON 字符串
     */
    private String doPost(String path, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);       // 启用输出流，用于发送请求体
            conn.setConnectTimeout(30000); // 连接超时 30 秒
            conn.setReadTimeout(120000);   // 读取超时 120 秒（LLM 推理可能较慢）

            // ===== 设置请求头 =====
            // Content-Type: Anthropic API 只接受 JSON 格式
            conn.setRequestProperty("Content-Type", "application/json");
            // anthropic-version: API 版本号，不同版本的请求/响应格式可能不同
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            // x-api-key: Anthropic 的认证方式（不是 Bearer Token，而是自定义头）
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("x-api-key", apiKey);
            }

            // ===== 发送请求体 =====
            if (debugPrintPayload) {
                System.out.println("=====请求报文=====\n" + jsonBody);
            }
            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            // ===== 读取响应 =====
            int status = conn.getResponseCode();
            // HTTP 2xx 表示成功，从 getInputStream 读取；否则从 getErrorStream 读取错误信息
            InputStream is = (status >= 200 && status < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }

            // 非 2xx 状态码表示 API 调用失败，抛出异常让上层处理
            if (status < 200 || status >= 300) {
                throw new RuntimeException("API Error (HTTP " + status + "): " + sb.toString());
            }
            // 格式化 JSON 响应报文（仅在调试模式下打印）
            String responseStr = sb.toString();
            if (debugPrintPayload) {
                try {
                    JsonObject responseJson = JsonParser.parseString(responseStr).getAsJsonObject();
                    System.out.println("=====响应报文=====\n" + GSON.toJson(responseJson));
                } catch (Exception e) {
                    // JSON 解析失败时直接输出原始字符串
                    System.out.println("=====响应报文=====\n" + responseStr);
                }
            }
            return responseStr;

        } catch (IOException e) {
            throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
        } finally {
            // 确保释放 HTTP 连接资源
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ==================================================================================
    // 静态工具方法：方便构建 API 请求/解析响应
    // ==================================================================================
    //
    // 为什么把这些方法放在 AnthropicClient 里而不是单独的工具类？
    // 因为它们都是围绕 Messages API 的数据结构（message、content、tool_result）服务的，
    // 和 API 客户端天然内聚。这也是 Python 版中直接用 dict 构建的等价物。
    //
    // ===== Messages API 的消息结构 =====
    // Anthropic Messages API 要求消息列表是 user 和 assistant 严格交替的：
    //   [user, assistant, user, assistant, ...]
    //
    // content 字段有两种形式：
    //   1. 字符串："Hello" —— 简单文本消息
    //   2. 数组：[{type:"text", text:"..."}, {type:"tool_use", ...}] —— 包含工具调用的复合消息
    //
    // tool_result 必须放在 user 消息的 content 数组中，这是 API 的设计约束，
    // 因为工具执行的结果在概念上是"用户侧"提供给 LLM 的信息。
    // ==================================================================================

    /**
     * 构建一条 user 消息（纯文本形式）。
     * 对应 Python: {"role": "user", "content": text}
     */
    public static JsonObject userMessage(String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", text);
        return msg;
    }

    /**
     * 构建一条 user 消息（content 是数组形式，用于承载 tool_result）。
     *
     * 为什么 tool_result 要放在 user 消息里？
     * 这是 Anthropic API 的设计：工具执行发生在"用户侧"，
     * 所以结果通过 user 角色的消息传回给 LLM。
     * 这和 OpenAI 的设计不同（OpenAI 有专门的 "tool" 角色）。
     */
    public static JsonObject userMessage(JsonArray content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.add("content", content);
        return msg;
    }

    /**
     * 构建一条 assistant 消息（纯文本形式）。
     * 用于手动构造对话历史或测试。
     */
    public static JsonObject assistantMessage(String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("content", text);
        return msg;
    }

    /**
     * 构建一条 assistant 消息（content 是数组形式，直接来自 API 响应）。
     *
     * 为什么需要这个重载？
     * API 响应中 assistant 的 content 可能包含多个 block（text + tool_use），
     * 我们需要原样保留这些 block 再传回给 API，以维持完整的对话上下文。
     * 如果丢失了 tool_use block，API 会因为找不到对应的 tool_result 而报错。
     */
    public static JsonObject assistantMessage(JsonArray content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.add("content", content);
        return msg;
    }

    /**
     * 构建 tool_result block —— 告诉 LLM 工具执行的结果。
     *
     * tool_use_id 是关键字段：它将 tool_result 和对应的 tool_use 请求关联起来。
     * LLM 在一次回复中可能请求多个工具调用，每个都有唯一 ID，
     * 我们必须为每个调用返回匹配 ID 的结果。
     *
     * 对应 Python: {"type": "tool_result", "tool_use_id": id, "content": result}
     */
    public static JsonObject toolResult(String toolUseId, String content) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "tool_result");
        result.addProperty("tool_use_id", toolUseId);
        result.addProperty("content", content);
        return result;
    }

    /**
     * 从 API 响应中提取 stop_reason —— 这是代理循环的关键判断依据。
     *
     * stop_reason 的可能值：
     * - "end_turn"：LLM 认为任务已完成，主动结束对话
     * - "tool_use"：LLM 想要调用工具，需要我们执行工具并继续循环
     * - "max_tokens"：达到 max_tokens 限制，被强制截断
     * - "stop_sequence"：遇到了预设的停止序列
     *
     * 在代理循环中，我们主要关注 "tool_use" —— 它意味着循环应该继续。
     */
    public static String getStopReason(JsonObject response) {
        return response.has("stop_reason") ? response.get("stop_reason").getAsString() : "";
    }

    /**
     * 从 API 响应中提取 content 数组。
     *
     * content 数组包含 LLM 回复的所有内容块（content blocks），可能有：
     * - {type: "text", text: "..."} —— 文本回复
     * - {type: "tool_use", id: "...", name: "bash", input: {...}} —— 工具调用请求
     * 一次回复中可以同时包含文本和多个工具调用。
     */
    public static JsonArray getContent(JsonObject response) {
        return response.has("content") ? response.getAsJsonArray("content") : new JsonArray();
    }

    /**
     * 从 content 数组中提取所有文本内容，拼接成一个字符串。
     *
     * 为什么要遍历而不是直接取第一个？
     * 因为 content 数组中可能有多个 text block（虽然通常只有一个），
     * 而且中间可能夹杂 tool_use block，我们只需要提取 type="text" 的部分。
     */
    public static String extractText(JsonArray content) {
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : content) {
            JsonObject block = el.getAsJsonObject();
            if ("text".equals(block.get("type").getAsString())) {
                sb.append(block.get("text").getAsString());
            }
        }
        return sb.toString();
    }

    /**
     * 构建工具定义 —— 告诉 LLM 有哪些工具可用。
     *
     * 工具定义的结构遵循 Anthropic Tool Use 规范：
     * {
     *   "name": "bash",                    // 工具名称，LLM 通过名字调用
     *   "description": "Run a command...",  // 描述，帮助 LLM 理解何时使用此工具
     *   "input_schema": { ... }            // JSON Schema，定义工具的输入参数
     * }
     *
     * description 非常重要 —— 它是 LLM 决定使用哪个工具的主要依据。
     * 好的描述能让 LLM 更准确地选择工具，差的描述会导致工具选择错误。
     */
    public static JsonObject toolDef(String name, String description, JsonObject inputSchema) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("input_schema", inputSchema);
        return tool;
    }

    /**
     * 快速构建 JSON Schema —— 用变长参数简化工具参数定义。
     *
     * 参数以三元组的形式传入：(参数名, 类型, 是否必需)
     * 例如：schema("command", "string", "true", "timeout", "integer", "false")
     *
     * 生成的 JSON Schema 格式：
     * {
     *   "type": "object",
     *   "properties": {
     *     "command": { "type": "string" },
     *     "timeout": { "type": "integer" }
     *   },
     *   "required": ["command"]
     * }
     *
     * 为什么用 varargs 而不是 Builder 模式？
     * 因为本项目追求极简，varargs 写起来最快，对于简单 schema 完全够用。
     * 复杂场景（嵌套对象、枚举等）可以手动构建 JsonObject。
     */
    public static JsonObject schema(String... nameTypePairs) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        // 每 3 个元素一组：名称、类型、是否必需
        for (int i = 0; i < nameTypePairs.length; i += 3) {
            String name = nameTypePairs[i];
            String type = nameTypePairs[i + 1];
            String req = nameTypePairs[i + 2]; // "true" 表示必需参数
            JsonObject prop = new JsonObject();
            prop.addProperty("type", type);
            props.add(name, prop);
            if ("true".equals(req)) {
                required.add(name);
            }
        }
        schema.add("properties", props);
        if (required.size() > 0) {
            schema.add("required", required);
        }
        return schema;
    }
}
