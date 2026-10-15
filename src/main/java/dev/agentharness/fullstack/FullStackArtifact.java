package dev.agentharness.fullstack;

import java.util.List;

/** 统一工具校验后产生的角色制品与 lineage。 */
public record FullStackArtifact(
        String artifactId,
        FullStackRole producerRole,
        int invocation,
        String inputPacketId,
        List<String> predecessorArtifactIds,
        String payloadJson,
        String sha256) {
    public FullStackArtifact {
        // lineage 必须在制品构造时冻结。
        predecessorArtifactIds = List.copyOf(predecessorArtifactIds);
    }
}
