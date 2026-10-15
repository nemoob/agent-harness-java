package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FullStackReplayIT {
    @Test
    void replayRunsEightAgentScopeInvocationsAndRealDockerDefectLoop() throws Exception {
        Path stateRoot = Path.of("target", "fullstack-replay-it", UUID.randomUUID().toString());
        Files.createDirectories(stateRoot);
        Path requirement = stateRoot.resolve("requirement.txt");
        Files.writeString(requirement, "Build a safe local feature preview with numeric version comparison.");
        FullStackRequest request =
                new FullStackRequest(
                        ModelMode.REPLAY,
                        requirement,
                        stateRoot,
                        "replay-session",
                        18080);
        FullStackService service =
                new FullStackService(Path.of("target", "agent-harness-java.jar"));

        FullStackReport report = service.run(request);

        assertEquals(ModelMode.REPLAY, report.modelMode());
        assertEquals(0, report.realModelRequests());
        assertEquals(
                List.of(
                        FullStackRole.ARCHITECT,
                        FullStackRole.FRONTEND,
                        FullStackRole.BACKEND,
                        FullStackRole.TEST,
                        FullStackRole.TROUBLESHOOT,
                        FullStackRole.BACKEND,
                        FullStackRole.TEST,
                        FullStackRole.RELEASE),
                report.route());
        assertEquals(8, report.invocations().size());
        assertEquals(6, report.invocations().stream().map(Provenance::role).distinct().count());
        assertEquals(8, report.invocations().stream().map(Provenance::roleSessionId).distinct().count());
        assertEquals("FAIL", report.initialTestStatus());
        assertTrue(report.troubleshootCause().contains("Lexicographic"));
        assertEquals("PASS", report.finalTestStatus());
        assertNotEquals(report.initialCandidateSha256(), report.repairedCandidateSha256());
        assertTrue(report.staleEvidenceRejected());
        assertEquals("DEPLOYED_CLEANED", report.outcome());
        assertEquals("DEPLOYED", report.releaseStatus());
        assertTrue(report.releaseHealthVerified());
        assertTrue(report.releaseBusinessVerified());
        assertTrue(Files.exists(stateRoot.resolve("fullstack-summary/replay-session.json")));

        // 回放候选必须实际使用受信派生的完整前端，而不是旧占位 Ready 页面。
        Path candidateSource = stateRoot.resolve(report.runDirectory()).resolve("candidate-source");
        String indexHtml = Files.readString(candidateSource.resolve("web/index.html"));
        String styles = Files.readString(candidateSource.resolve("web/styles.css"));
        String app = Files.readString(candidateSource.resolve("web/app.js"));
        assertTrue(indexHtml.contains("确定性 REPLAY 运行结果，非本次真实模型全链成功"));
        assertTrue(indexHtml.contains("<label"));
        assertTrue(styles.contains("@media"));
        for (String contract :
                List.of(
                        "/login",
                        "/api/session",
                        "/api/features",
                        "X-CSRF-Token",
                        "expectedRevision",
                        "REVISION_CONFLICT",
                        "SESSION_REQUIRED",
                        "CSRF_REJECTED",
                        "ORIGIN_REJECTED",
                        "textContent")) {
            assertTrue(app.contains(contract), contract);
        }
        // 派生页面只访问同源固定路径，并继续拒绝危险 DOM 写入。
        assertFalse(indexHtml.contains("http://") || indexHtml.contains("https://"));
        assertFalse(app.contains("http://") || app.contains("https://"));
        assertFalse(indexHtml.contains("innerHTML") || app.contains("innerHTML"));

        // fixture 外层 lineage 记录原 REAL 前端制品与派生文件 hash，但不会进入模型工具 payload。
        JsonNode replayFixtures;
        try (InputStream input =
                FullStackReplayIT.class.getResourceAsStream(
                        "/fullstack/replay/role-outputs.json")) {
            replayFixtures = JsonMapper.builder().build().readTree(input);
        }
        JsonNode frontendFixture =
                java.util.stream.StreamSupport.stream(replayFixtures.spliterator(), false)
                        .filter(value -> "FRONTEND".equals(value.path("role").asText()))
                        .filter(value -> value.path("invocation").asInt() == 2)
                        .findFirst()
                        .orElseThrow();
        JsonNode lineage = frontendFixture.path("lineage");
        assertEquals(
                "0b6a4286c6aad8d36d2ef1a0d01ca59f0c9f6f75dcc7aea68763f7819ac8994f",
                lineage.path("sourceArtifactSha256").asText());
        for (String path : List.of("web/index.html", "web/styles.css", "web/app.js")) {
            // 候选工作区、fixture 声明与公开 REPLAY 快照必须逐字一致。
            assertEquals(
                    sha256(candidateSource.resolve(path)),
                    lineage.path("files").path(path).path("derivedSha256").asText());
            assertEquals(
                    Files.readString(candidateSource.resolve(path)),
                    Files.readString(Path.of("examples/fullstack-generated").resolve(path)));
        }
        assertEquals(
                Files.readString(candidateSource.resolve("GeneratedVersionPolicy.java")),
                Files.readString(
                        Path.of(
                                "examples/fullstack-generated/backend/GeneratedVersionPolicy.java")));
        // 公开快照 manifest 必须明确标为 REPLAY，并绑定本次修复候选而非 REAL 成功。
        JsonNode snapshotManifest =
                JsonMapper.builder()
                        .build()
                        .readTree(
                                Path.of("examples/fullstack-generated/manifest.json").toFile());
        assertEquals("REPLAY", snapshotManifest.path("modelMode").asText());
        assertEquals(
                report.repairedManifestSha256(),
                snapshotManifest.path("candidateManifestSha256").asText());
        assertTrue(snapshotManifest.path("candidateSha256").asText().matches("[0-9a-f]{64}"));
        for (String path :
                List.of(
                        "web/index.html",
                        "web/styles.css",
                        "web/app.js",
                        "backend/GeneratedVersionPolicy.java")) {
            assertEquals(
                    sha256(Path.of("examples/fullstack-generated").resolve(path)),
                    snapshotManifest.path("files").path(path).path("sha256").asText());
        }

        // 实际 AgentScope USER 消息必须携带完整前端与后端固定契约，而非只传制品 ID。
        String capturedPrompts;
        try (var paths = Files.walk(stateRoot.resolve("agentscope"))) {
            capturedPrompts =
                    paths.filter(path -> path.getFileName().toString().equals("agent_state.json"))
                            .map(
                                    path -> {
                                        try {
                                            return Files.readString(path);
                                        } catch (java.io.IOException exception) {
                                            throw new java.io.UncheckedIOException(exception);
                                        }
                                    })
                            .reduce("", String::concat);
        }
        for (String required :
                List.of(
                        "GET/POST /login",
                        "GET /api/session",
                        "csrfToken",
                        "GET /api/features",
                        "expectedRevision",
                        "effective",
                        "reasonCode",
                        "REVISION_CONFLICT",
                        "SESSION_REQUIRED",
                        "CSRF_REJECTED",
                        "ORIGIN_REJECTED",
                        "package fullstack.generated",
                        "public final class GeneratedVersionPolicy implements VersionPolicy",
                        "boolean isAtLeast(String clientVersion, String minVersion)")) {
            assertTrue(capturedPrompts.contains(required), required);
        }

        List<List<Integer>> expectedPredecessors =
                List.of(
                        List.of(),
                        List.of(1),
                        List.of(1),
                        List.of(3),
                        List.of(4),
                        List.of(5),
                        List.of(6),
                        List.of(7));
        for (int index = 0; index < report.artifacts().size(); index++) {
            FullStackArtifact artifact = report.artifacts().get(index);
            assertEquals(index + 1, artifact.invocation());
            assertEquals(
                    expectedPredecessors.get(index).stream()
                            .map(value -> report.artifacts().get(value - 1).artifactId())
                            .toList(),
                    artifact.predecessorArtifactIds());
        }
    }

    private static String sha256(Path path) throws Exception {
        // 标准库复算候选文件 hash，避免只相信 fixture 自报值。
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
