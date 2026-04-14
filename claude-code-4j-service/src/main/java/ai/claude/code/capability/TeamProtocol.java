package ai.claude.code.capability;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 团队协议工具 - shutdown/approval 协议的消息构造与判断。
 * Team Protocol Utilities - message construction and detection for shutdown/approval protocols.
 *
 * 【协议状态机 / Protocol FSMs】
 *
 *   Shutdown Protocol:
 *   ┌──────┐  shutdown_request   ┌──────────┐  shutdown_response   ┌──────┐
 *   │ Lead │ ─────────────────> │ Teammate │ ─────────────────── > │ Lead │
 *   │      │  (request_id)      │          │  (approve/reject)     │      │
 *   └──────┘                    └──────────┘                       └──────┘
 *
 *   Plan Approval Protocol:
 *   ┌──────────┐  plan (submit)     ┌──────┐  plan_approval_response  ┌──────────┐
 *   │ Teammate │ ────────────────> │ Lead │ ───────────────────────> │ Teammate │
 *   │          │  (request_id)      │      │  (approve/reject)        │          │
 *   └──────────┘                    └──────┘                          └──────────┘
 *
 * 【request_id 关联模式 / request_id Correlation Pattern】
 *   1. 发起方生成唯一 request_id / Initiator generates unique request_id
 *   2. 通过消息发送给对方 / Sends message to counterpart
 *   3. 对方处理后用同一 request_id 回复 / Counterpart replies with same request_id
 *   4. 发起方通过 request_id 查询结果 / Initiator checks result by request_id
 */
public class TeamProtocol {

    /**
     * 构造 shutdown_request 消息。
     * Build a shutdown_request message.
     *
     * 返回的 JsonObject 包含 / Returned JsonObject contains:
     *   { "type": "shutdown_request", "request_id": "shutdown-xxxxxxxx" }
     *
     * @param requestId 唯一请求 ID / Unique request ID
     * @return shutdown 请求消息 / shutdown request message
     */
    public static JsonObject shutdownRequest(String requestId) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "shutdown_request");
        msg.addProperty("request_id", requestId);
        return msg;
    }

    /**
     * 构造 approval_request 消息（计划审批请求）。
     * Build an approval_request message (plan approval request).
     *
     * 返回的 JsonObject 包含 / Returned JsonObject contains:
     *   { "type": "plan_approval_request", "request_id": "plan-xxxxxxxx",
     *     "description": "..." }
     *
     * @param requestId   唯一请求 ID / Unique request ID
     * @param description 计划描述 / Plan description
     * @return 审批请求消息 / Approval request message
     */
    public static JsonObject approvalRequest(String requestId, String description) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "plan_approval_request");
        msg.addProperty("request_id", requestId);
        msg.addProperty("description", description);
        return msg;
    }

    /**
     * 判断消息是否为 shutdown_response。
     * Check if a message is a shutdown_response.
     *
     * @param msg 待检查的消息 / Message to check
     * @return true 如果消息类型为 "shutdown_response" / true if message type is "shutdown_response"
     */
    public static boolean isShutdownResponse(JsonObject msg) {
        if (msg == null || !msg.has("type")) {
            return false;
        }
        return "shutdown_response".equals(msg.get("type").getAsString());
    }

    /**
     * 判断协议响应消息是否为"批准"。
     * Check if a protocol response message is "approved".
     *
     * 适用于 shutdown_response 和 plan_approval_response 两种消息。
     * Works for both shutdown_response and plan_approval_response messages.
     *
     * 判断逻辑 / Logic:
     *   - 优先检查 "approve" 布尔字段 / Check "approve" boolean field first
     *   - 其次检查 "decision" 字符串字段是否为 "approve" / Then check "decision" string field
     *
     * @param msg 协议响应消息 / Protocol response message
     * @return true 如果批准 / true if approved
     */
    public static boolean isApproved(JsonObject msg) {
        if (msg == null) {
            return false;
        }
        // 检查 "approve" 布尔字段 (shutdown_response 格式)
        // Check "approve" boolean field (shutdown_response format)
        if (msg.has("approve")) {
            return msg.get("approve").getAsBoolean();
        }
        // 检查 "decision" 字符串字段 (plan_approval_response 格式)
        // Check "decision" string field (plan_approval_response format)
        if (msg.has("decision")) {
            return "approve".equals(msg.get("decision").getAsString());
        }
        return false;
    }

    /**
     * 生成唯一 request_id。
     * Generate a unique request_id.
     *
     * 使用 UUID 前 8 位作为短标识，加上前缀便于区分协议类型。
     * Uses first 8 chars of UUID as short identifier.
     *
     * @return 唯一的 request_id / Unique request_id (e.g. "req-a1b2c3d4")
     */
    public static String generateRequestId() {
        return "req-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
