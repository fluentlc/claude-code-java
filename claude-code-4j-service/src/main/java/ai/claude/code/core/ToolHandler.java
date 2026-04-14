package ai.claude.code.core;

import com.google.gson.JsonObject;

/**
 * 工具处理器的函数式接口 —— 工具分派模式的核心抽象。
 *
 * ===== 设计理由 =====
 * 在 Python 版中，工具处理器用 lambda 或普通函数表示，
 * 存储在字典（dict）中：TOOL_HANDLERS = {"bash": lambda **kw: run_bash(**kw), ...}
 * Java 没有一等函数（first-class function），但可以通过函数式接口 + 匿名类/Lambda 实现等价效果。
 *
 * 使用 @FunctionalInterface 注解的好处：
 * 1. 编译器会检查接口是否真的只有一个抽象方法（否则编译报错）
 * 2. 支持 Lambda 表达式（JDK 8+）或匿名内部类（JDK 7 兼容写法）
 * 3. 语义清晰 —— 明确告诉开发者"这是一个函数式接口，用于回调"
 *
 * ===== 使用方式 =====
 * ToolHandler 会被放入 Map<String, ToolHandler> 分派表中：
 *   Map<String, ToolHandler> dispatch = new HashMap<>();
 *   dispatch.put("bash", input -> baseTools.runBash(input.get("command").getAsString()));
 *
 * 新增工具只需往 Map 里加一条，不需要修改循环逻辑。
 *
 * ===== 为什么输入是 JsonObject，输出是 String？ =====
 * - 输入 JsonObject：因为 LLM 返回的工具调用参数是 JSON 格式
 * - 输出 String：因为 tool_result 的 content 是字符串，最终要传回给 LLM
 *
 * ===== 在 Claude Code 真实实现中 =====
 * Claude Code 用 TypeScript 实现，工具处理器是异步函数（async function），
 * 返回值更丰富（可以包含错误状态、元数据等）。
 * 本接口是其简化版本，足以演示核心概念。
 *
 * 对应 Python 版中的: TOOL_HANDLERS = { "bash": lambda **kw: ... }
 */
@FunctionalInterface
public interface ToolHandler {
    /**
     * 执行工具调用。
     *
     * @param input LLM 传入的工具参数（JSON 对象），如 {"command": "ls -la"}
     * @return 工具执行结果的文本描述，将作为 tool_result 传回给 LLM
     */
    String execute(JsonObject input);
}
