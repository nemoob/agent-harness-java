package dev.agentharness.a2a;

/** message/send 的 Message、Task 或错误联合响应。 */
public record A2aResponse(String kind, A2aMessage message, A2aTask task, A2aError error) {
    public static A2aResponse message(A2aMessage message) {
        // 用显式 kind 区分直接消息结果。
        return new A2aResponse("message", message, null, null);
    }

    public static A2aResponse task(A2aTask task) {
        // 用显式 kind 区分远端任务结果。
        return new A2aResponse("task", null, task, null);
    }

    public static A2aResponse error(String code, String message) {
        // 错误响应不携带部分 Message 或 Task。
        return new A2aResponse("error", null, null, new A2aError(code, message));
    }
}
