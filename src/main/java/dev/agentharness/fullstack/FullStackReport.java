package dev.agentharness.fullstack;

import java.util.List;

/** 不含密钥、完整 prompt 或本机绝对路径的 fullstack 运行摘要。 */
public record FullStackReport(
        ModelMode modelMode,
        int realModelRequests,
        int cumulativeRealModelRequests,
        int modelInputTokens,
        int modelOutputTokens,
        long wallMillis,
        List<FullStackRole> route,
        List<Provenance> invocations,
        List<FullStackArtifact> artifacts,
        String initialCandidateSha256,
        String repairedCandidateSha256,
        String repairedManifestSha256,
        String initialTestStatus,
        String troubleshootCause,
        String finalTestStatus,
        boolean staleEvidenceRejected,
        String outcome,
        String releaseStatus,
        int releaseSideEffectCount,
        boolean releaseHealthVerified,
        boolean releaseBusinessVerified,
        String previewUrl,
        String handoffTokenFile,
        String runId,
        String runDirectory) {
    public FullStackReport {
        // 对外报告固定路由与证据列表，防止保存后被调用方改写。
        route = List.copyOf(route);
        invocations = List.copyOf(invocations);
        artifacts = List.copyOf(artifacts);
    }
}
