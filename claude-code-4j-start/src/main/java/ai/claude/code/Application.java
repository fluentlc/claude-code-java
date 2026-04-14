package ai.claude.code;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * claude-code-4j 统一启动入口。
 * Unified entry point for claude-code-4j.
 *
 * 默认模式 — REST API 服务器（端口 8080）：
 * Default mode — REST API server (port 8080):
 *   java -jar claude-code-4j-start.jar
 *
 * CLI 交互模式 — 禁用 Web Server，进入 REPL：
 * CLI interactive mode — disables Web Server, enters REPL:
 *   java -jar claude-code-4j-start.jar --spring.profiles.active=cli
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
