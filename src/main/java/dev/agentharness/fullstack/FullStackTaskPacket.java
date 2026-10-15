package dev.agentharness.fullstack;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 协调器投递给单个角色的不可变任务包。 */
public record FullStackTaskPacket(
        String packetId,
        String project,
        String sessionId,
        int invocation,
        FullStackRole role,
        String capabilityGrantRef,
        List<String> predecessorArtifactIds,
        String instruction,
        String replayOutputJson) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public FullStackTaskPacket {
        // 所有可形成状态路径的标识都采用有限 ASCII 白名单。
        requireId(packetId, "packetId");
        requireId(project, "project");
        requireId(sessionId, "sessionId");
        requireId(capabilityGrantRef, "capabilityGrantRef");
        // 调用序号从 1 开始并由协调器控制。
        if (invocation <= 0) {
            throw new IllegalArgumentException("invocation must be positive");
        }
        // 角色必须来自固定枚举。
        Objects.requireNonNull(role, "role");
        // lineage 复制为不可变列表，模型不能运行中改写。
        predecessorArtifactIds = List.copyOf(predecessorArtifactIds);
        // 指令和回放制品都必须显式给出。
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction is required");
        }
        if (replayOutputJson == null || replayOutputJson.isBlank()) {
            throw new IllegalArgumentException("replay output is required");
        }
    }

    private static void requireId(String value, String label) {
        // 标识白名单排除路径分隔符、控制字符和空值。
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must match [A-Za-z0-9_-]{1,64}");
        }
    }
}
