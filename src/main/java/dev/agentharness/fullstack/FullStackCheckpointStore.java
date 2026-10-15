package dev.agentharness.fullstack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** 每个角色接受后保存不含 prompt、密钥、host 与本机绝对路径的原子 checkpoint。 */
final class FullStackCheckpointStore {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final Path runDirectory;

    FullStackCheckpointStore(Path runDirectory) {
        try {
            // checkpoint 只允许写入已经验证的真实 run 目录。
            Path normalized = runDirectory.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                    || !normalized.toRealPath().equals(normalized)) {
                throw new IllegalArgumentException("checkpoint run directory invalid");
            }
            this.runDirectory = normalized;
        } catch (Exception exception) {
            throw new IllegalArgumentException("checkpoint run directory cannot be verified", exception);
        }
    }

    void save(
            String runId,
            String session,
            String requirementSha256,
            ModelMode mode,
            String baseRevision,
            List<Provenance> provenances,
            List<FullStackArtifact> artifacts) {
        Path target = runDirectory.resolve("checkpoint.json");
        Path temporary = null;
        Checkpoint existingCheckpoint = null;
        try {
            // 预置链接或非普通 leaf 不能被原子替换掩盖。
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(target)
                            || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalArgumentException("checkpoint target invalid");
            }
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                Checkpoint existing = JSON.readValue(target.toFile(), Checkpoint.class);
                // 恢复只能推进同一 session/requirement/mode/baseRevision，禁止跨运行覆盖。
                if (!runId.equals(existing.runId())
                        || !session.equals(existing.session())
                        || !requirementSha256.equals(existing.requirementSha256())
                        || mode != existing.modelMode()
                        || !baseRevision.equals(existing.baseRevision())
                        || existing.nextInvocation() > artifacts.size() + 1) {
                    throw new IllegalStateException("checkpoint binding mismatch");
                }
                existingCheckpoint = existing;
            }
            List<ArtifactDigest> digests =
                    artifacts.stream()
                            .map(
                                    artifact ->
                                            new ArtifactDigest(
                                                    artifact.artifactId(),
                                                    artifact.producerRole(),
                                                    artifact.invocation(),
                                                    artifact.inputPacketId(),
                                                    artifact.predecessorArtifactIds(),
                                                    artifact.sha256()))
                            .toList();
            if (existingCheckpoint != null
                    && (existingCheckpoint.artifacts().size() > digests.size()
                            || !digests.subList(0, existingCheckpoint.artifacts().size())
                                    .equals(existingCheckpoint.artifacts()))) {
                throw new IllegalStateException("checkpoint artifact lineage mismatch");
            }
            Checkpoint checkpoint =
                    new Checkpoint(
                            runId,
                            session,
                            requirementSha256,
                            mode,
                            baseRevision,
                            artifacts.size() + 1,
                            List.copyOf(provenances),
                            digests);
            // 随机同目录临时文件写完后原子替换，失败不暴露部分 JSON。
            temporary = Files.createTempFile(runDirectory, ".checkpoint-", ".tmp");
            JSON.writeValue(temporary.toFile(), checkpoint);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            throw new IllegalStateException("fullstack checkpoint cannot be saved", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // 临时文件清理失败不覆盖原始 checkpoint 错误。
                }
            }
        }
    }

    record Checkpoint(
            String runId,
            String session,
            String requirementSha256,
            ModelMode modelMode,
            String baseRevision,
            int nextInvocation,
            List<Provenance> provenances,
            List<ArtifactDigest> artifacts) {}

    record ArtifactDigest(
            String artifactId,
            FullStackRole producerRole,
            int invocation,
            String inputPacketId,
            List<String> predecessorArtifactIds,
            String payloadSha256) {}
}
