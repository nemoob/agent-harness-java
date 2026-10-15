package dev.agentharness.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/** 单次工作流运行内的本地结构化 packet 与 artifact 仓库。 */
final class WorkflowStore {
    private final Map<String, TaskPacket> packets = new LinkedHashMap<>();
    private final Map<String, RoleArtifact> artifacts = new LinkedHashMap<>();

    synchronized void putPacket(TaskPacket packet) {
        // packetId 必须唯一，禁止角色覆盖既有输入。
        if (packets.putIfAbsent(packet.packetId(), packet) != null) {
            throw new IllegalStateException("duplicate packetId");
        }
    }

    synchronized TaskPacket packet(String packetId) {
        // 工具只按显式 packetId 加载本地输入。
        TaskPacket packet = packets.get(packetId);
        if (packet == null) {
            throw new IllegalArgumentException("unknown packetId");
        }
        // 返回不可变记录。
        return packet;
    }

    synchronized RoleArtifact artifact(String artifactId) {
        // 所有前置 artifact 必须真实存在于本地仓库。
        RoleArtifact artifact = artifacts.get(artifactId);
        if (artifact == null) {
            throw new IllegalStateException("unknown predecessor artifactId: " + artifactId);
        }
        // 返回不可变记录。
        return artifact;
    }

    synchronized void putArtifact(RoleArtifact artifact) {
        // artifactId 必须唯一，禁止伪造覆盖来源链。
        if (artifacts.putIfAbsent(artifact.artifactId(), artifact) != null) {
            throw new IllegalStateException("duplicate artifactId");
        }
    }

    synchronized int artifactCount() {
        // artifact 数量是 packet 输入修订号的确定性来源。
        return artifacts.size();
    }
}
