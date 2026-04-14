package ai.claude.code.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 根据 claude.properties 中的 CLIENT_TYPE 返回对应客户端。
 * CLIENT_TYPE=openai（默认）→ OpenAiClient（DashScope）
 * CLIENT_TYPE=anthropic    → AnthropicClient
 */
public class ClientFactory {

    private static final Properties CONFIG = loadConfig();

    private static Properties loadConfig() {
        Properties props = new Properties();
        InputStream is = ClientFactory.class.getClassLoader()
                .getResourceAsStream("application.properties");
        if (is != null) {
            try { props.load(is); is.close(); } catch (IOException ignore) { }
        }
        return props;
    }

    private static String get(String key, String def) {
        // 优先级：环境变量 > claude.properties > 默认值
        String envVal = System.getenv(key);
        if (envVal != null && !envVal.trim().isEmpty()) return envVal.trim();
        String v = CONFIG.getProperty(key);
        return (v != null && !v.trim().isEmpty()) ? v.trim() : def;
    }

    public static boolean isOpenAi() {
        return !"anthropic".equalsIgnoreCase(get("CLIENT_TYPE", "openai"));
    }

    public static OpenAiClient openAiClient() {
        String baseUrl = get("OPENAI_BASE_URL", "https://api.openai.com");
        String apiKey  = get("OPENAI_API_KEY", get("OPENAI_AUTH_TOKEN", ""));
        String model   = get("OPENAI_MODEL_ID", "gpt-4o");
        // 未配置 API Key 时打印友好提示 / Print friendly prompt if API key is not configured
        if (apiKey.isEmpty() || "your_api_key_here".equals(apiKey)) {
            System.err.println("⚠  OPENAI_API_KEY 未配置！");
            System.err.println("   请编辑 claude-code-4j-service/src/main/resources/claude.properties");
            System.err.println("   将 OPENAI_API_KEY=your_api_key_here 替换为真实的 API Key");
            System.err.println("   或设置环境变量：export OPENAI_API_KEY=<your-key>");
        }
        // 仅当配置了 x-platform 时才加该 header，避免默认注入与 OpenAI 无关的字段
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String xPlatform = get("OPENAI_X_PLATFORM", "");
        if (!xPlatform.isEmpty()) {
            headers.put("x-platform", xPlatform);
        }
        return new OpenAiClient(apiKey, baseUrl, model, headers, "/v1/chat/completions");
    }

    public static AnthropicClient anthropicClient() {
        return new AnthropicClient();
    }
}
