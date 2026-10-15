package dev.agentharness.fullstack;

import java.util.List;

/** 确定性发布门禁的脱敏结果。 */
public record ReleaseDecision(
        Status status,
        int sideEffectCount,
        boolean healthVerified,
        boolean businessVerified,
        boolean recovered,
        boolean cleanupComplete,
        String failureReason,
        List<String> residualResources,
        String activeCandidateSha256,
        ReleaseGate.DeploymentHandle deployment) {
    public ReleaseDecision {
        // 对外结果只复制受控资源类型/ID，调用方不能改写清理证据。
        residualResources = List.copyOf(residualResources);
    }

    public enum Status {
        DEPLOYED,
        DEPLOY_BLOCKED,
        ROLLED_BACK,
        ROLLBACK_FAILED,
        DEPLOY_FAILED,
        CLEANUP_FAILED
    }
}
