package ai.claude.code.core;

import com.google.gson.JsonObject;

/**
 * Streaming event callbacks for AgentLoop.
 * Implement to receive real-time events during Agent execution.
 * All methods have empty defaults so implementors only override what they need.
 */
public interface AgentEventListener {
    /** Agent begins a thinking phase (model-dependent, may not fire). */
    default void onThinkingStart() {}
    /** Incremental thinking text fragment. */
    default void onThinkingText(String text) {}
    /** Thinking phase ended. @param durationMs elapsed milliseconds */
    default void onThinkingEnd(long durationMs) {}
    /** Tool call is about to execute. @param input complete tool input JSON */
    default void onToolStart(String id, String name, JsonObject input) {}
    /** Tool call completed successfully. */
    default void onToolEnd(String id, boolean success, String result) {}
    /** Incremental text fragment from the model's final reply. */
    default void onTextDelta(String text) {}
    /** All rounds complete — AgentLoop.run() is about to return. */
    default void onDone() {}
    /** Fatal error — AgentLoop.run() will throw after this callback. */
    default void onError(String message) {}
    /** AgentLoop checks this before each tool-execution round. Return true to abort. */
    default boolean isCancelled() { return false; }

    // ── Teammate events (fired from TeammateLoop thread via TeammateRunner) ──

    /** A teammate is about to execute a tool. */
    default void onTeamToolStart(String agentId, String id, String name, JsonObject input) {}
    /** A teammate's tool call completed. */
    default void onTeamToolEnd(String agentId, String id, boolean success, String result) {}
    /** A teammate produced a text response from the LLM. */
    default void onTeamText(String agentId, String text) {}
    /** A teammate's working session ended (entering idle or shutting down). */
    default void onTeamDone(String agentId) {}

    // ── Compact event ──

    /** Context compression completed. @param summary the LLM-generated summary */
    default void onCompactDone(String summary) {}
}
