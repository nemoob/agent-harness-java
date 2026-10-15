package dev.agentharness.fullstack;

/** 受控模型传输边界；不暴露 endpoint 或密钥给任务包。 */
public interface ModelTransport {
    Reply complete(RolePrompt prompt);

    String modelName();

    record RolePrompt(
            FullStackRole role,
            int invocation,
            String systemPrompt,
            String userPrompt,
            String priorToolId,
            String priorToolInputJson,
            String toolResult,
            int maxTokens) {
        public RolePrompt(
                FullStackRole role,
                int invocation,
                String systemPrompt,
                String userPrompt,
                String toolResult,
                int maxTokens) {
            // 第一轮没有历史工具调用，使用便利构造保持调用清晰。
            this(
                    role,
                    invocation,
                    systemPrompt,
                    userPrompt,
                    null,
                    null,
                    toolResult,
                    maxTokens);
        }
    }

    record Reply(
            String text,
            String toolId,
            String toolName,
            String toolInputJson,
            ModelUsage usage) {
        public boolean isToolUse() {
            // 工具名存在表示供应商返回工具调用，而非最终文本。
            return toolName != null;
        }
    }
}
