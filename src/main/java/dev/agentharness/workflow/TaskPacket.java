package dev.agentharness.workflow;

import java.util.List;

/** 角色间传递的结构化任务包。 */
public record TaskPacket(
        String packetId,
        String businessTaskId,
        String project,
        String sessionId,
        long inputRevision,
        String sourceRole,
        String targetRole,
        List<String> predecessorArtifactIds,
        List<String> evidenceIds,
        List<String> unknowns,
        List<String> allowedActions,
        String requirementStatus) {}
