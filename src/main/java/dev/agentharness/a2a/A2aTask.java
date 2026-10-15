package dev.agentharness.a2a;

import java.util.List;

/** 服务端任务 ID、上下文 ID 与业务任务 ID 的独立映射。 */
public record A2aTask(
        String id,
        String contextId,
        String businessTaskId,
        String status,
        List<A2aArtifact> artifacts) {}
