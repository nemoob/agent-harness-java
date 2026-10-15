package dev.agentharness.workflow;

import java.util.List;

/** 一个角色通过真实 AgentScope 循环生成并带完整来源链的教学 JSON 制品。 */
public record RoleArtifact(
        String artifactId,
        String producerRole,
        String inputPacketId,
        List<String> predecessorArtifactIds,
        List<String> evidenceIds,
        String kind,
        String status,
        String payload,
        String returnTarget,
        int modelCalls,
        int toolCalls) {}
