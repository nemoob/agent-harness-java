package dev.agentharness.fullstack;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** 服务端保存的不可变能力授权快照。 */
public record CapabilityGrant(
        String reference,
        String principal,
        String project,
        Path workspaceRoot,
        String baseRevision,
        FullStackRole role,
        Set<String> tools,
        Set<String> paths,
        String target,
        String inputArtifactSha,
        Map<String, String> arguments,
        Instant expiresAt) {
    public CapabilityGrant {
        // 服务端集合全部冻结，调用方不能在签发后扩大能力。
        tools = Set.copyOf(tools);
        paths = Set.copyOf(paths);
        arguments = Map.copyOf(arguments);
    }
}
