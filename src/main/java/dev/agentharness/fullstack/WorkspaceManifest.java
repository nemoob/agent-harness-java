package dev.agentharness.fullstack;

import java.util.Map;

/** 工作区普通文件的稳定 SHA-256 manifest。 */
public record WorkspaceManifest(String sha256, Map<String, String> fileSha256) {
    public WorkspaceManifest {
        fileSha256 = Map.copyOf(fileSha256);
    }
}
