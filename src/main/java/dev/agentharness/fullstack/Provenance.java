package dev.agentharness.fullstack;

import java.util.List;

/** 不含完整 prompt、密钥或 endpoint 的角色调用来源记录。 */
public record Provenance(
        FullStackRole role,
        int invocation,
        String roleSessionId,
        String promptSha256,
        String modelName,
        String artifactSha256,
        ModelMode modelMode,
        int realModelRequests,
        List<String> toolSchemas,
        boolean toolResultMatched,
        boolean agentClosed,
        String stateSha256,
        boolean recovered) {
    public Provenance {
        // 工具 schema 保存不可变快照，防止证据随后漂移。
        toolSchemas = List.copyOf(toolSchemas);
    }
}
