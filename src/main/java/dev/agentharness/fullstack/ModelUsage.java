package dev.agentharness.fullstack;

/** 供应商返回的真实 token 用量。 */
public record ModelUsage(int inputTokens, int outputTokens) {
    public ModelUsage {
        // 负数用量属于无效供应商响应，不能进入预算统计。
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("model usage must be non-negative");
        }
    }
}
