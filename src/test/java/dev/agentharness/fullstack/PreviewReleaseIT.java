package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreviewReleaseIT {
    @Test
    void deterministicGateCoversSuccessBlockRollbackAndFirstFailure() throws Exception {
        Path workspace = Files.createDirectories(root("gate-workspace"));
        Path candidate = Files.writeString(workspace.resolve("candidate.jar"), "candidate");
        String candidateHash = sha(candidate);
        CapabilityGrantRegistry registry = new CapabilityGrantRegistry();
        FakeAdapter adapter = new FakeAdapter();
        ReleaseGate gate = new ReleaseGate(registry, adapter);

        GrantBundle valid = grant(registry, workspace, candidateHash, "grant-a");
        ReleaseDecision success =
                gate.release(request(valid.use(), candidate, candidateHash, candidateHash, true, false, null));
        assertEquals(ReleaseDecision.Status.DEPLOYED, success.status());
        assertTrue(success.healthVerified());
        assertTrue(success.businessVerified());

        ReleaseDecision missing =
                gate.release(
                        request(
                                unknownUse(workspace, candidateHash),
                                candidate,
                                candidateHash,
                                candidateHash,
                                true,
                                false,
                                null));
        assertEquals(ReleaseDecision.Status.DEPLOY_BLOCKED, missing.status());
        assertEquals(0, missing.sideEffectCount());

        GrantBundle mismatchGrant = grant(registry, workspace, candidateHash, "grant-b");
        ReleaseDecision mismatch =
                gate.release(
                        request(
                                mismatchGrant.use(),
                                candidate,
                                candidateHash,
                                "different-test-hash",
                                true,
                                false,
                                null));
        assertEquals(ReleaseDecision.Status.DEPLOY_BLOCKED, mismatch.status());
        assertEquals(0, mismatch.sideEffectCount());

        ReleaseGate.DeploymentAnchor anchor = adapter.anchor(success.deployment());
        GrantBundle replacement = grant(registry, workspace, candidateHash, "grant-c");
        ReleaseDecision rollback =
                gate.release(
                        request(
                                replacement.use(),
                                candidate,
                                candidateHash,
                                candidateHash,
                                true,
                                true,
                                anchor));
        assertEquals(ReleaseDecision.Status.ROLLED_BACK, rollback.status());
        assertTrue(rollback.recovered());
        assertEquals(candidateHash, rollback.activeCandidateSha256());

        GrantBundle first = grant(registry, workspace, candidateHash, "grant-d");
        ReleaseDecision firstFailure =
                gate.release(
                        request(
                                first.use(),
                                candidate,
                                candidateHash,
                                candidateHash,
                                true,
                                true,
                                null));
        assertEquals(ReleaseDecision.Status.DEPLOY_FAILED, firstFailure.status());
        assertFalse(firstFailure.recovered());
        assertEquals("", firstFailure.activeCandidateSha256());
    }

    @Test
    void activationExceptionRestoresOldDeploymentOrReportsRollbackFailure() throws Exception {
        Path workspace = Files.createDirectories(root("gate-switch-transaction"));
        Path candidate = Files.writeString(workspace.resolve("candidate.jar"), "candidate");
        String candidateHash = sha(candidate);
        CapabilityGrantRegistry registry = new CapabilityGrantRegistry();
        FakeAdapter adapter = new FakeAdapter();
        ReleaseGate gate = new ReleaseGate(registry, adapter);
        ReleaseDecision initial =
                gate.release(
                        request(
                                grant(registry, workspace, candidateHash, "initial").use(),
                                candidate,
                                candidateHash,
                                candidateHash,
                                true,
                                false,
                                null));
        ReleaseGate.DeploymentAnchor anchor = adapter.anchor(initial.deployment());

        adapter.failActivate = true;
        ReleaseDecision recovered =
                gate.release(
                        request(
                                grant(registry, workspace, candidateHash, "recoverable").use(),
                                candidate,
                                candidateHash,
                                candidateHash,
                                true,
                                false,
                                anchor));
        assertEquals(ReleaseDecision.Status.ROLLED_BACK, recovered.status());
        assertTrue(recovered.recovered());
        assertEquals(anchor.deployment(), recovered.deployment());

        adapter.failActivate = true;
        adapter.failRestore = true;
        ReleaseDecision failed =
                gate.release(
                        request(
                                grant(registry, workspace, candidateHash, "unrecoverable").use(),
                                candidate,
                                candidateHash,
                                candidateHash,
                                true,
                                false,
                                anchor));
        assertEquals(ReleaseDecision.Status.ROLLBACK_FAILED, failed.status());
        assertFalse(failed.recovered());
    }

    @Test
    void replacementCleanupResidualsCanNeverReportRolledBack() throws Exception {
        Path workspace = Files.createDirectories(root("gate-cleanup-residual"));
        Path candidate = Files.writeString(workspace.resolve("candidate.jar"), "candidate");
        String candidateHash = sha(candidate);
        CapabilityGrantRegistry registry = new CapabilityGrantRegistry();
        FakeAdapter adapter = new FakeAdapter();
        ReleaseGate gate = new ReleaseGate(registry, adapter);
        ReleaseDecision initial =
                gate.release(
                        request(
                                grant(registry, workspace, candidateHash, "cleanup-initial").use(),
                                candidate,
                                candidateHash,
                                candidateHash,
                                true,
                                false,
                                null));
        ReleaseGate.DeploymentAnchor anchor = adapter.anchor(initial.deployment());

        for (String residual :
                java.util.List.of(
                        "container:gateway-id",
                        "container:candidate-id",
                        "network:internal-id",
                        "network:publish-id")) {
            adapter.stopResidual = residual;
            ReleaseDecision decision =
                    gate.release(
                            request(
                                    grant(
                                                    registry,
                                                    workspace,
                                                    candidateHash,
                                                    "cleanup-" + residual.hashCode())
                                            .use(),
                                    candidate,
                                    candidateHash,
                                    candidateHash,
                                    true,
                                    true,
                                    anchor));
            assertEquals(ReleaseDecision.Status.ROLLBACK_FAILED, decision.status());
            assertFalse(decision.cleanupComplete());
            assertEquals(java.util.List.of(residual), decision.residualResources());
        }
    }

    @Test
    void healthyReplacementWithOnlyOldCleanupResidualReportsCleanupFailed() throws Exception {
        Path workspace = Files.createDirectories(root("gate-old-cleanup-residual"));
        Path candidate = Files.writeString(workspace.resolve("candidate.jar"), "candidate");
        String candidateHash = sha(candidate);
        CapabilityGrantRegistry registry = new CapabilityGrantRegistry();
        FakeAdapter adapter = new FakeAdapter();
        ReleaseGate gate = new ReleaseGate(registry, adapter);
        ReleaseDecision initial =
                gate.release(
                        request(
                                grant(registry, workspace, candidateHash, "old-cleanup-initial")
                                        .use(),
                                candidate,
                                candidateHash,
                                candidateHash,
                                true,
                                false,
                                null));
        ReleaseGate.DeploymentAnchor anchor = adapter.anchor(initial.deployment());

        adapter.stopResidual = "container:old-candidate-id";
        ReleaseDecision decision =
                gate.release(
                        request(
                                grant(registry, workspace, candidateHash, "old-cleanup-replacement")
                                        .use(),
                                candidate,
                                candidateHash,
                                candidateHash,
                                true,
                                false,
                                anchor));

        assertEquals(ReleaseDecision.Status.CLEANUP_FAILED, decision.status());
        assertTrue(decision.healthVerified());
        assertTrue(decision.businessVerified());
        assertFalse(decision.recovered());
        assertFalse(decision.cleanupComplete());
        assertEquals(
                java.util.List.of("container:old-candidate-id"),
                decision.residualResources());
    }

    @Test
    void previewAuthKeepsSmokeInMemoryAndWritesOnlyHandoffPlaintext() throws Exception {
        Path control = Files.createDirectories(root("preview-auth"));

        BootstrapTokenFile.PreviewAuthMaterial material =
                BootstrapTokenFile.createPreview(control);

        String smoke = new String(material.smokeToken());
        String handoff = Files.readString(material.handoffFile());
        String config = Files.readString(material.authConfigFile());
        assertFalse(config.contains(smoke));
        assertFalse(config.contains(handoff));
        assertTrue(config.contains("\"tokenId\":\"smoke\""));
        assertTrue(config.contains("\"tokenId\":\"browser-handoff\""));
        assertEquals(null, material.testSmokeFile());
        assertEquals(
                "rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(material.handoffFile())));
        assertNotEquals(smoke, handoff);
    }

    @Test
    void missingPinnedImageFailsBeforeDockerOrRuntimeSideEffects() throws Exception {
        Path root = Files.createDirectories(root("missing-image"));
        Path base = Files.writeString(root.resolve("base.jar"), "base");
        Path candidate = Files.writeString(root.resolve("candidate.jar"), "candidate");
        Path runtime = Files.createDirectory(root.resolve("runtime"));
        java.util.List<java.util.List<String>> commands = new java.util.ArrayList<>();
        PreviewReleaseAdapter.CommandRunner missing =
                argv -> {
                    commands.add(java.util.List.copyOf(argv));
                    return new PreviewReleaseAdapter.CommandResult(
                            false, new byte[0], new byte[0]);
                };
        try (PreviewReleaseAdapter adapter =
                new PreviewReleaseAdapter(base, runtime, missing)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> adapter.start(candidate, sha(candidate), 18182, false, false));
            assertEquals(0, adapter.sideEffectCount());
            assertEquals(1, commands.size());
            assertEquals(java.util.List.of("docker", "image", "inspect"), commands.get(0).subList(0, 3));
            assertEquals(0, Files.list(runtime).count());
        }
    }

    @Test
    void realAdapterDeploysRollsBackAndKeepsTokensOutOfContainerConfig() throws Exception {
        Path stateRoot = Files.createDirectories(root("real-adapter-state"));
        Path requirement =
                Files.writeString(
                        stateRoot.resolve("requirement.txt"),
                        "Build the local homepage recommendation preview.");
        FullStackReport report =
                new FullStackService(Path.of("target", "agent-harness-java.jar"))
                        .run(
                                new FullStackRequest(
                                        ModelMode.REPLAY,
                                        requirement,
                                        stateRoot,
                                        "preview-release",
                                        18080));
        Path candidate =
                stateRoot
                        .resolve(report.runDirectory())
                        .resolve("repaired")
                        .resolve("candidate.jar");
        String candidateHash = sha(candidate);
        Path runtimeRoot = Files.createDirectory(stateRoot.resolve("preview-runtime"));
        CapabilityGrantRegistry registry = new CapabilityGrantRegistry();
        PreviewReleaseAdapter.FailurePlan failurePlan =
                PreviewReleaseAdapter.FailurePlan.none();
        try (PreviewReleaseAdapter adapter =
                new PreviewReleaseAdapter(
                        Path.of("target", "agent-harness-java.jar"),
                        runtimeRoot,
                        new PreviewReleaseAdapter.CommandExecutor(
                                java.time.Duration.ofSeconds(120), 1_048_576),
                        failurePlan)) {
            ReleaseGate gate = new ReleaseGate(registry, adapter);
            int port = freePort();
            // 无 grant 与候选 hash 错配都必须在 Docker 副作用前阻断。
            ReleaseDecision noGrant =
                    gate.release(
                            request(
                                    unknownUse(candidate.getParent(), candidateHash),
                                    candidate,
                                    candidateHash,
                                    candidateHash,
                                    true,
                                    false,
                                    null,
                                    port));
            assertEquals(ReleaseDecision.Status.DEPLOY_BLOCKED, noGrant.status());
            assertEquals(0, noGrant.sideEffectCount());
            GrantBundle valid =
                    grant(registry, candidate.getParent(), candidateHash, "real-success");
            ReleaseDecision wrongHash =
                    gate.release(
                            request(
                                    valid.use(),
                                    candidate,
                                    "0".repeat(64),
                                    "0".repeat(64),
                                    true,
                                    false,
                                    null,
                                    port));
            assertEquals(ReleaseDecision.Status.DEPLOY_BLOCKED, wrongHash.status());
            assertEquals(0, wrongHash.sideEffectCount());

            ReleaseDecision success =
                    gate.release(
                            request(
                                    valid.use(),
                                    candidate,
                                    candidateHash,
                                    candidateHash,
                                    true,
                                    false,
                                    null,
                                    port));

            assertEquals(
                    ReleaseDecision.Status.DEPLOYED,
                    success.status(),
                    success.toString());
            assertTrue(success.healthVerified());
            assertTrue(success.businessVerified());
            Path handoff = Path.of(success.deployment().handoffTokenFile());
            assertTrue(Files.isRegularFile(handoff));
            String handoffToken = Files.readString(handoff);
            PreviewReleaseAdapter.RuntimeNames names =
                    adapter.runtimeNames(success.deployment());
            String candidateInspect = inspect(names.candidateContainer());
            String gatewayInspect = inspect(names.gatewayContainer());
            com.fasterxml.jackson.databind.JsonNode candidateNode =
                    com.fasterxml.jackson.databind.json.JsonMapper.builder()
                            .build()
                            .readTree(candidateInspect)
                            .path(0);
            com.fasterxml.jackson.databind.JsonNode gatewayNode =
                    com.fasterxml.jackson.databind.json.JsonMapper.builder()
                            .build()
                            .readTree(gatewayInspect)
                            .path(0);
            assertFalse(candidateInspect.contains(handoffToken));
            assertFalse(gatewayInspect.contains(handoffToken));
            assertFalse(candidateInspect.contains("ANTHROPIC"));
            assertFalse(gatewayInspect.contains("ANTHROPIC"));
            com.fasterxml.jackson.databind.JsonNode binding =
                    gatewayNode.path("NetworkSettings").path("Ports").path("8080/tcp").path(0);
            assertEquals("127.0.0.1", binding.path("HostIp").asText());
            assertEquals(Integer.toString(port), binding.path("HostPort").asText());
            com.fasterxml.jackson.databind.JsonNode candidateBindings =
                    candidateNode.path("HostConfig").path("PortBindings");
            assertTrue(candidateBindings.isNull() || candidateBindings.size() == 0);
            java.util.Set<String> candidateNetworks =
                    fieldNames(candidateNode.path("NetworkSettings").path("Networks"));
            java.util.Set<String> gatewayNetworks =
                    fieldNames(gatewayNode.path("NetworkSettings").path("Networks"));
            assertEquals(java.util.Set.of(names.internalNetwork()), candidateNetworks);
            assertEquals(
                    java.util.Set.of(names.internalNetwork(), names.publishNetwork()),
                    gatewayNetworks);
            // gateway 构建证据只列固定 Dockerfile/base.jar 和固定离线构建参数。
            String buildEvidence =
                    Files.readString(
                            runtimeRoot
                                    .resolve("gateway-image-evidence")
                                    .resolve("build-context-manifest.json"));
            assertTrue(buildEvidence.contains(DockerRunner.IMAGE));
            assertTrue(buildEvidence.contains("\"network\" : \"none\""));
            assertTrue(buildEvidence.contains("\"pull\" : false"));
            assertTrue(buildEvidence.contains("baseJarSha256"));
            assertFalse(buildEvidence.contains(handoffToken));

            // Host 不能选择上游；absolute-form、CONNECT、重复 CL、TE 和超限请求必须被拒绝。
            assertTrue(raw(port, "GET /health HTTP/1.1\r\nHost: attacker.invalid\r\nConnection: close\r\n\r\n").contains("UP"));
            for (String rejected :
                    java.util.List.of(
                            "GET http://attacker.invalid/health HTTP/1.1\r\nHost: attacker.invalid\r\n\r\n",
                            "CONNECT attacker.invalid:443 HTTP/1.1\r\nHost: attacker.invalid\r\n\r\n",
                            "POST /login HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx",
                            "POST /login HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                            "POST /login HTTP/1.1\r\nHost: x\r\nContent-Length: 65537\r\n\r\n")) {
                assertTrue(raw(port, rejected).isBlank());
            }
            assertSlowGatewayDeadline(port);

            ReleaseGate.DeploymentAnchor anchor = adapter.anchor(success.deployment());
            String oldCandidateId = containerId(names.candidateContainer());
            String oldGatewayId = containerId(names.gatewayContainer());
            // create/connect/start 三个稳定端口切换故障都必须清新并恢复旧端口。
            for (PreviewReleaseAdapter.FailPoint point :
                    PreviewReleaseAdapter.FailPoint.values()) {
                failurePlan.schedule(point, 2);
                GrantBundle injected =
                        grant(
                                registry,
                                candidate.getParent(),
                                candidateHash,
                                "switch-" + point.name().toLowerCase());
                ReleaseDecision recovered =
                        gate.release(
                                request(
                                        injected.use(),
                                        candidate,
                                        candidateHash,
                                        candidateHash,
                                        true,
                                        false,
                                        anchor,
                                        port));
                assertEquals(ReleaseDecision.Status.ROLLED_BACK, recovered.status(), point.name());
                assertTrue(recovered.recovered(), point.name());
                assertTrue(canConnect(port), point.name());
                PreviewReleaseAdapter.RuntimeNames recoveredNames =
                        adapter.runtimeNames(recovered.deployment());
                assertEquals(oldCandidateId, containerId(recoveredNames.candidateContainer()));
                assertNotEquals(oldGatewayId, containerId(recoveredNames.gatewayContainer()));
                oldGatewayId = containerId(recoveredNames.gatewayContainer());
                anchor = adapter.anchor(recovered.deployment());
            }
            GrantBundle replacement =
                    grant(registry, candidate.getParent(), candidateHash, "real-replacement");
            ReleaseDecision rollback =
                    gate.release(
                            request(
                                    replacement.use(),
                                    candidate,
                                    candidateHash,
                                    candidateHash,
                                    true,
                                    true,
                                    anchor,
                                    port));
            assertEquals(ReleaseDecision.Status.ROLLED_BACK, rollback.status());
            assertTrue(rollback.recovered());
            assertTrue(rollback.healthVerified());
            assertTrue(rollback.businessVerified());
            PreviewReleaseAdapter.RuntimeNames restoredNames =
                    adapter.runtimeNames(rollback.deployment());
            assertEquals(oldCandidateId, containerId(restoredNames.candidateContainer()));
            assertNotEquals(oldGatewayId, containerId(restoredNames.gatewayContainer()));
            assertTrue(
                    adapter.sideEffectLedger().stream()
                                    .filter(
                                            entry ->
                                                    entry.startsWith("START_GATEWAY:")
                                                            && entry.endsWith(":" + port))
                                    .count()
                            >= 3);

            int failedPort = freePort();
            GrantBundle first =
                    grant(registry, candidate.getParent(), candidateHash, "real-first-fail");
            ReleaseDecision firstFailure =
                    gate.release(
                            request(
                                    first.use(),
                                    candidate,
                                    candidateHash,
                                    candidateHash,
                                    true,
                                    true,
                                    null,
                                    failedPort));
            assertEquals(ReleaseDecision.Status.DEPLOY_FAILED, firstFailure.status());
            assertFalse(firstFailure.recovered());
            // rollback 后显式停止 gateway，稳定端口必须立即不可达。
            adapter.stop(rollback.deployment());
            assertFalse(canConnect(port));
            // first-fail 与最终停止都不得残留容器或网络。
            for (PreviewReleaseAdapter.RuntimeNames runtime : adapter.allRuntimeNames()) {
                assertFalse(dockerExists("inspect", runtime.candidateContainer()));
                assertFalse(dockerExists("inspect", runtime.gatewayContainer()));
                assertFalse(dockerExists("network", "inspect", runtime.internalNetwork()));
                assertFalse(dockerExists("network", "inspect", runtime.publishNetwork()));
            }
        }
    }

    @Test
    void realCleanupFailureForEachOwnedResourceIsExplicitAndLeavesUnrelatedContainer() throws Exception {
        Path stateRoot = Files.createDirectories(root("real-cleanup-failures"));
        Path requirement =
                Files.writeString(
                        stateRoot.resolve("requirement.txt"),
                        "Build the local homepage recommendation preview.");
        FullStackReport report =
                new FullStackService(Path.of("target", "agent-harness-java.jar"))
                        .run(
                                new FullStackRequest(
                                        ModelMode.REPLAY,
                                        requirement,
                                        stateRoot,
                                        "preview-cleanup-failures",
                                        freePort()));
        Path candidate =
                stateRoot
                        .resolve(report.runDirectory())
                        .resolve("repaired")
                        .resolve("candidate.jar");
        String candidateHash = sha(candidate);
        String unrelated =
                "agent-harness-unrelated-" + UUID.randomUUID().toString().replace("-", "");
        String unrelatedId = startUnrelated(unrelated);
        try {
            for (DeleteKind kind : DeleteKind.values()) {
                Path runtime = Files.createDirectory(stateRoot.resolve("runtime-" + kind.name()));
                CapabilityGrantRegistry registry = new CapabilityGrantRegistry();
                FailDeleteRunner commands = new FailDeleteRunner(kind);
                java.util.List<PreviewReleaseAdapter.RuntimeNames> owned;
                try (PreviewReleaseAdapter adapter =
                        new PreviewReleaseAdapter(
                                Path.of("target", "agent-harness-java.jar"),
                                runtime,
                                commands)) {
                    ReleaseGate gate = new ReleaseGate(registry, adapter);
                    ReleaseDecision decision =
                            gate.release(
                                    request(
                                            grant(
                                                            registry,
                                                            candidate.getParent(),
                                                            candidateHash,
                                                            "delete-" + kind.name())
                                                    .use(),
                                            candidate,
                                            candidateHash,
                                            candidateHash,
                                            true,
                                            true,
                                            null,
                                            freePort()));
                    assertEquals(ReleaseDecision.Status.CLEANUP_FAILED, decision.status());
                    assertFalse(decision.cleanupComplete());
                    assertTrue(
                            decision.residualResources().stream()
                                    .anyMatch(kind::matchesResidual));
                    owned = adapter.allRuntimeNames();
                    assertUnrelated(unrelated, unrelatedId);
                }
                // close 在一次性故障消费后重试精确资源，最终不得留本轮容器或网络。
                for (PreviewReleaseAdapter.RuntimeNames names : owned) {
                    assertFalse(dockerExists("container", "inspect", names.candidateContainer()));
                    assertFalse(dockerExists("container", "inspect", names.gatewayContainer()));
                    assertFalse(dockerExists("network", "inspect", names.internalNetwork()));
                    assertFalse(dockerExists("network", "inspect", names.publishNetwork()));
                }
                assertUnrelated(unrelated, unrelatedId);
            }
        } finally {
            docker("rm", "-f", unrelated);
        }
    }

    private static ReleaseGate.ReleaseRequest request(
            CapabilityGrantRegistry.GrantUse use,
            Path candidate,
            String candidateHash,
            String testHash,
            boolean testPassed,
            boolean healthFault,
            ReleaseGate.DeploymentAnchor anchor) {
        return request(
                use,
                candidate,
                candidateHash,
                testHash,
                testPassed,
                healthFault,
                anchor,
                18080);
    }

    private static ReleaseGate.ReleaseRequest request(
            CapabilityGrantRegistry.GrantUse use,
            Path candidate,
            String candidateHash,
            String testHash,
            boolean testPassed,
            boolean healthFault,
            ReleaseGate.DeploymentAnchor anchor,
            int port) {
        return new ReleaseGate.ReleaseRequest(
                use,
                candidate,
                candidateHash,
                new ReleaseGate.TestEvidence(testHash, testPassed),
                "preview",
                port,
                healthFault,
                anchor);
    }

    private static GrantBundle grant(
            CapabilityGrantRegistry registry,
            Path workspace,
            String candidateHash,
            String grantName) {
        Map<String, String> arguments = Map.of("candidateSha256", candidateHash);
        CapabilityGrant grant =
                registry.issue(
                        new CapabilityGrantRegistry.GrantSpec(
                                "release-service",
                                "demo",
                                workspace,
                                "fixed-scaffold-v1",
                                FullStackRole.RELEASE,
                                Set.of("deploy-preview"),
                                Set.of("candidate.jar"),
                                "preview",
                                candidateHash,
                                arguments,
                                Instant.now().plusSeconds(300)));
        CapabilityGrantRegistry.GrantUse use =
                new CapabilityGrantRegistry.GrantUse(
                        grant.reference(),
                        "release-service",
                        "demo",
                        workspace,
                        "fixed-scaffold-v1",
                        FullStackRole.RELEASE,
                        "deploy-preview",
                        "candidate.jar",
                        "preview",
                        candidateHash,
                        arguments,
                        Instant.now());
        return new GrantBundle(grantName, use);
    }

    private static CapabilityGrantRegistry.GrantUse unknownUse(
            Path workspace, String candidateHash) {
        return new CapabilityGrantRegistry.GrantUse(
                "grant-unknown",
                "release-service",
                "demo",
                workspace,
                "fixed-scaffold-v1",
                FullStackRole.RELEASE,
                "deploy-preview",
                "candidate.jar",
                "preview",
                candidateHash,
                Map.of("candidateSha256", candidateHash),
                Instant.now());
    }

    private static Path root(String name) throws Exception {
        return Path.of("target", "preview-release-it", UUID.randomUUID() + "-" + name);
    }

    private static String sha(Path file) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(file)));
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static String inspect(String container) throws Exception {
        Process process = new ProcessBuilder("docker", "inspect", container).start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String containerId(String container) throws Exception {
        Process process =
                new ProcessBuilder("docker", "inspect", "--format", "{{.Id}}", container)
                        .start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .strip();
    }

    private static java.util.Set<String> fieldNames(
            com.fasterxml.jackson.databind.JsonNode object) {
        java.util.Set<String> names = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return java.util.Set.copyOf(names);
    }

    private static String raw(int port, String request) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2_000);
            socket.getOutputStream()
                    .write(request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            try {
                return new String(
                        socket.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
            } catch (java.net.SocketTimeoutException rejectedWithoutResponse) {
                return "";
            }
        }
    }

    private static void assertSlowGatewayDeadline(int port) throws Exception {
        long started = System.nanoTime();
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(12_000);
            socket.getOutputStream().write('G');
            socket.getOutputStream().flush();
            assertEquals(-1, socket.getInputStream().read());
        }
        long elapsed = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertTrue(elapsed >= 9_000 && elapsed <= 11_500, "gateway deadline=" + elapsed);
    }

    private static boolean canConnect(int port) {
        try (java.net.Socket ignored = new java.net.Socket("127.0.0.1", port)) {
            return true;
        } catch (java.io.IOException expected) {
            return false;
        }
    }

    private static boolean dockerExists(String... arguments) throws Exception {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add("docker");
        argv.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(argv).start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        return process.exitValue() == 0;
    }

    private static String startUnrelated(String name) throws Exception {
        return docker(
                        "run",
                        "-d",
                        "--name",
                        name,
                        "--pull=never",
                        "--platform",
                        "linux/arm64",
                        "--network",
                        "none",
                        "--read-only",
                        "--cap-drop",
                        "ALL",
                        "--security-opt",
                        "no-new-privileges",
                        "--entrypoint",
                        "/bin/sleep",
                        DockerRunner.IMAGE,
                        "180")
                .strip();
    }

    private static void assertUnrelated(String name, String id) throws Exception {
        assertEquals(id, docker("inspect", "--format", "{{.Id}}", name).strip());
        assertEquals("true", docker("inspect", "--format", "{{.State.Running}}", name).strip());
    }

    private static String docker(String... arguments) throws Exception {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add("docker");
        argv.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(argv).start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(stderr, java.nio.charset.StandardCharsets.UTF_8));
        return new String(stdout, java.nio.charset.StandardCharsets.UTF_8);
    }

    private enum DeleteKind {
        GATEWAY,
        CANDIDATE,
        INTERNAL,
        PUBLISH;

        private boolean matchesDelete(java.util.List<String> argv) {
            String joined = String.join(" ", argv);
            return switch (this) {
                case GATEWAY -> joined.startsWith("docker rm -f agent-harness-gateway-");
                case CANDIDATE -> joined.startsWith("docker rm -f agent-harness-candidate-");
                case INTERNAL -> joined.startsWith("docker network rm agent-harness-internal-");
                case PUBLISH -> joined.startsWith("docker network rm agent-harness-publish-");
            };
        }

        private boolean matchesResidual(String residual) {
            return switch (this) {
                case GATEWAY -> residual.startsWith("container:agent-harness-gateway-");
                case CANDIDATE -> residual.startsWith("container:agent-harness-candidate-");
                case INTERNAL -> residual.startsWith("network:agent-harness-internal-");
                case PUBLISH -> residual.startsWith("network:agent-harness-publish-");
            };
        }
    }

    private static final class FailDeleteRunner implements PreviewReleaseAdapter.CommandRunner {
        private final DeleteKind kind;
        private final PreviewReleaseAdapter.CommandRunner delegate =
                new PreviewReleaseAdapter.CommandExecutor(
                        java.time.Duration.ofSeconds(120), 1_048_576);
        private boolean pending = true;

        private FailDeleteRunner(DeleteKind kind) {
            this.kind = kind;
        }

        @Override
        public PreviewReleaseAdapter.CommandResult run(java.util.List<String> argv) {
            if (pending && kind.matchesDelete(argv)) {
                pending = false;
                return new PreviewReleaseAdapter.CommandResult(
                        false,
                        new byte[0],
                        "injected delete failure".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return delegate.run(argv);
        }
    }

    private record GrantBundle(
            String name, CapabilityGrantRegistry.GrantUse use) {}

    private static final class FakeAdapter implements ReleaseGate.Adapter {
        private int sequence;
        private int effects;
        private final Map<String, ReleaseGate.DeploymentHandle> known = new java.util.HashMap<>();
        private boolean failActivate;
        private boolean failRestore;
        private String stopResidual;

        @Override
        public ReleaseGate.DeploymentHandle start(
                Path candidateJar,
                String candidateSha256,
                int previewPort,
                boolean healthFault,
                boolean shadow) {
            effects++;
            ReleaseGate.DeploymentHandle handle =
                    new ReleaseGate.DeploymentHandle(
                            "deployment-" + ++sequence,
                            candidateSha256,
                            "http://127.0.0.1:" + previewPort,
                            "browser-handoff.token",
                            healthFault);
            known.put(handle.deploymentId(), handle);
            return handle;
        }

        @Override
        public ReleaseGate.DeploymentHandle activate(
                ReleaseGate.DeploymentHandle candidate,
                ReleaseGate.DeploymentHandle previous) {
            effects++;
            if (failActivate) {
                failActivate = false;
                throw new IllegalStateException("injected activation failure");
            }
            return candidate;
        }

        @Override
        public void stop(ReleaseGate.DeploymentHandle deployment) {
            effects++;
            if (stopResidual != null) {
                String residual = stopResidual;
                stopResidual = null;
                throw new PreviewReleaseAdapter.CleanupException(
                        "injected cleanup residual", java.util.List.of(residual));
            }
        }

        @Override
        public boolean health(ReleaseGate.DeploymentHandle deployment) {
            return !deployment.healthFault();
        }

        @Override
        public boolean business(ReleaseGate.DeploymentHandle deployment) {
            return !deployment.healthFault();
        }

        @Override
        public ReleaseGate.DeploymentAnchor anchor(ReleaseGate.DeploymentHandle deployment) {
            return new ReleaseGate.DeploymentAnchor(deployment, deployment.candidateSha256());
        }

        @Override
        public ReleaseGate.DeploymentHandle restore(ReleaseGate.DeploymentAnchor anchor) {
            effects++;
            if (failRestore) {
                failRestore = false;
                throw new IllegalStateException("injected restore failure");
            }
            return known.get(anchor.deployment().deploymentId());
        }

        @Override
        public int sideEffectCount() {
            return effects;
        }
    }
}
