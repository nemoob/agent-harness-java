package dev.agentharness.fullstack;

import java.util.List;

/** 不经 shell 解释的固定 Docker argv。 */
public record DockerCommand(List<String> argv, String containerName) {
    public DockerCommand {
        // 参数复制后不能被调用方换成其他容器目标。
        argv = List.copyOf(argv);
        // 清理只接受 Runner 自己生成的固定前缀和 32 位小写十六进制 UUID。
        if (containerName == null
                || !containerName.matches("agent-harness-run-[0-9a-f]{32}")) {
            throw new IllegalArgumentException("runner container name invalid");
        }
    }
}
