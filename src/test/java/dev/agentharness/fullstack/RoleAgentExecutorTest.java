package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoleAgentExecutorTest {
    @Test
    void runsSixIndependentReplayAgentSessionsWithLineageAndOneTool() throws Exception {
        Path stateRoot = stateRoot();
        RoleAgentExecutor executor = new RoleAgentExecutor(stateRoot, ModelBudget.fullRun(), null);
        List<String> predecessors = List.of();
        List<String> sessions = new ArrayList<>();

        int invocation = 0;
        for (FullStackRole role : FullStackRole.values()) {
            invocation++;
            FullStackTaskPacket packet = packet(role, invocation, predecessors, validOutput(role, invocation));
            RoleAgentExecutor.RoleExecution execution = executor.execute(packet, ModelMode.REPLAY);

            assertEquals(role, execution.artifact().producerRole());
            assertEquals(predecessors, execution.artifact().predecessorArtifactIds());
            assertEquals(ModelMode.REPLAY, execution.provenance().modelMode());
            assertEquals(0, execution.provenance().realModelRequests());
            assertEquals(List.of("submit_role_output"), execution.provenance().toolSchemas());
            assertTrue(execution.provenance().toolResultMatched());
            assertTrue(execution.provenance().agentClosed());
            assertFalse(execution.provenance().promptSha256().isBlank());
            assertEquals(execution.artifact().sha256(), execution.provenance().artifactSha256());

            sessions.add(execution.provenance().roleSessionId());
            predecessors = List.of(execution.artifact().artifactId());
        }

        assertEquals(6, sessions.stream().distinct().count());
        assertEquals(6, Files.list(stateRoot.resolve("agentscope/teaching-user")).count());
        assertNotEquals(sessions.get(0), sessions.get(2));
    }

    @Test
    void rejectsMalformedOrCrossRoleOutputBeforeArtifactIsAccepted() {
        Path stateRoot = stateRoot();
        RoleAgentExecutor executor = new RoleAgentExecutor(stateRoot, ModelBudget.fullRun(), null);
        FullStackTaskPacket wrongRole =
                packet(
                        FullStackRole.ARCHITECT,
                        1,
                        List.of(),
                        "{\"role\":\"BACKEND\",\"outputs\":{\"plan\":\"x\"}}");

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> executor.execute(wrongRole, ModelMode.REPLAY));
        assertFalse(error.getMessage().contains(wrongRole.replayOutputJson()));

        FullStackTaskPacket missingOutput =
                packet(
                        FullStackRole.TEST,
                        2,
                        List.of("artifact-one"),
                        "{}");
        assertThrows(
                IllegalStateException.class,
                () -> executor.execute(missingOutput, ModelMode.REPLAY));
    }

    @Test
    void acceptsOnlyOutputsAndBindsArtifactIdentityToTrustedPacket() throws Exception {
        FullStackTaskPacket architect =
                packet(
                        FullStackRole.ARCHITECT,
                        1,
                        List.of("trusted-predecessor"),
                        "{\"outputs\":{\"summary\":\"accepted\"}}");
        RoleOutputTool tool = new RoleOutputTool(architect);

        tool.submit(architect.replayOutputJson());
        FullStackArtifact artifact = tool.artifact();

        assertEquals(FullStackRole.ARCHITECT, artifact.producerRole());
        assertEquals(1, artifact.invocation());
        assertEquals(architect.packetId(), artifact.inputPacketId());
        assertEquals(architect.predecessorArtifactIds(), artifact.predecessorArtifactIds());
        assertEquals(1, tool.calls());

        FullStackTaskPacket otherTask =
                packet(
                        FullStackRole.TEST,
                        7,
                        List.of("other-predecessor"),
                        architect.replayOutputJson());
        RoleOutputTool otherTool = new RoleOutputTool(otherTask);
        otherTool.submit(otherTask.replayOutputJson());

        assertEquals(FullStackRole.TEST, otherTool.artifact().producerRole());
        assertEquals(7, otherTool.artifact().invocation());
        assertEquals(otherTask.packetId(), otherTool.artifact().inputPacketId());
        assertEquals(otherTask.predecessorArtifactIds(), otherTool.artifact().predecessorArtifactIds());
    }

    @Test
    void rejectsMissingUnknownAndClientSuppliedIdentityFields() {
        FullStackTaskPacket packet =
                packet(
                        FullStackRole.FRONTEND,
                        2,
                        List.of("architect-artifact"),
                        "{\"outputs\":{\"summary\":\"fixture\"}}");

        assertThrows(IllegalStateException.class, () -> new RoleOutputTool(packet).submit("{}"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new RoleOutputTool(packet)
                                .submit(
                                        "{\"outputs\":{\"summary\":\"x\"},\"unknown\":true}"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new RoleOutputTool(packet)
                                .submit(
                                        "{\"role\":\"BACKEND\",\"outputs\":{\"summary\":\"x\"}}"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new RoleOutputTool(packet)
                                .submit(
                                        "{\"invocation\":99,\"outputs\":{\"summary\":\"x\"}}"));
    }

    @Test
    void canonicalizesNestedKeysAcrossWriteReadAndPreventsSecondSubmission() throws Exception {
        FullStackTaskPacket packet =
                packet(
                        FullStackRole.TEST,
                        3,
                        List.of("architect-artifact"),
                        "{\"outputs\":{\"z\":{\"b\":2,\"a\":1},\"a\":[{\"y\":2,\"x\":1}]}}");
        RoleOutputTool first = new RoleOutputTool(packet);
        RoleOutputTool second = new RoleOutputTool(packet);

        first.submit(packet.replayOutputJson());
        Path persisted = stateRoot().resolve("artifact.json");
        Files.createDirectories(persisted.getParent());
        Files.writeString(
                persisted,
                "{\"outputs\":{\"a\":[{\"x\":1,\"y\":2}],\"z\":{\"a\":1,\"b\":2}}}");
        second.submit(Files.readString(persisted));

        assertEquals(first.artifact().payloadJson(), second.artifact().payloadJson());
        assertEquals(first.artifact().sha256(), second.artifact().sha256());
        assertEquals(first.artifact().artifactId(), second.artifact().artifactId());
        assertThrows(
                IllegalStateException.class,
                () -> first.submit("{\"outputs\":{\"summary\":\"second\"}}"));
        assertEquals(2, first.calls());
        assertEquals(second.artifact().sha256(), first.artifact().sha256());
    }

    @Test
    void frontendArtifactAcceptsTwentyKiBBoundaryAndRejectsOneExtraByte() {
        String styles = "body{}";
        String script = "document.body.dataset.ready='1';";
        int indexBytes =
                20_480
                        - styles.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        - script.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String boundaryFile = "x".repeat(indexBytes);
        FullStackTaskPacket packet =
                packet(
                        FullStackRole.FRONTEND,
                        2,
                        List.of("architect-artifact"),
                        "{\"outputs\":{\"files\":{\"web/index.html\":\""
                                + boundaryFile
                                + "\",\"web/styles.css\":\""
                                + styles
                                + "\",\"web/app.js\":\""
                                + script
                                + "\"}}}");
        RoleOutputTool tool = new RoleOutputTool(packet);

        tool.submit(packet.replayOutputJson());
        assertFalse(tool.artifact().sha256().isBlank());
        String oversized =
                packet.replayOutputJson().replace(boundaryFile, boundaryFile + "x");
        assertThrows(
                IllegalStateException.class,
                () -> new RoleOutputTool(packet).submit(oversized));
    }

    @Test
    void frontendArtifactWritesOnlyAllowlistedFilesAndRejectsShapeExpansion() throws Exception {
        String valid =
                "{\"outputs\":{\"files\":{\"web/index.html\":\"<main>safe</main>\","
                        + "\"web/styles.css\":\"body{}\","
                        + "\"web/app.js\":\"document.body.dataset.ready='1';\"}}}";
        FullStackTaskPacket packet =
                packet(FullStackRole.FRONTEND, 2, List.of("architect-artifact"), valid);
        RoleOutputTool tool = new RoleOutputTool(packet);

        tool.submit(valid);
        com.fasterxml.jackson.databind.JsonNode files =
                com.fasterxml.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .readTree(tool.artifact().payloadJson())
                        .path("outputs")
                        .path("files");
        java.util.Map<String, String> patchFiles =
                new java.util.LinkedHashMap<>();
        files.properties()
                .forEach(entry -> patchFiles.put(entry.getKey(), entry.getValue().asText()));
        Path workspaceRoot = stateRoot();
        Files.createDirectories(workspaceRoot);
        WorkspaceManifest manifest =
                new SafeWorkspace(workspaceRoot).apply(new WorkspacePatch(patchFiles));

        assertEquals(
                java.util.Set.of("web/index.html", "web/styles.css", "web/app.js"),
                manifest.fileSha256().keySet());
        assertEquals("<main>safe</main>", Files.readString(workspaceRoot.resolve("web/index.html")));

        List<String> invalid =
                List.of(
                        "{\"outputs\":{\"files\":{\"web/index.html\":\"x\",\"web/styles.css\":\"x\"}}}",
                        "{\"outputs\":{\"files\":{\"web/index.html\":\"x\",\"web/styles.css\":\"x\",\"web/app.js\":\"x\",\"web/extra.js\":\"x\"}}}",
                        "{\"outputs\":{\"files\":{\"../escape\":\"x\",\"web/styles.css\":\"x\",\"web/app.js\":\"x\"}}}",
                        "{\"outputs\":{\"files\":{\"web/index.html\":{},\"web/styles.css\":\"x\",\"web/app.js\":\"x\"}}}",
                        "{\"outputs\":{\"files\":{\"web/index.html\":\"x\",\"web/styles.css\":\"x\",\"web/app.js\":\"x\"}},\"outputs\":{\"summary\":\"duplicate\"}}",
                        "{\"outputs\":{\"files\":{\"web/index.html\":\"x\",\"web/index.html\":\"y\",\"web/styles.css\":\"x\",\"web/app.js\":\"x\"}}}",
                        "{\"outputs\":{\"files\":{\"web/index.html\":\"x\",\"web/styles.css\":\"x\",\"web/app.js\":\"x\"}}} {}",
                        "{\"outputs\":{\"files\":{\"web/index.html\":\"x\",\"web/styles.css\":\"x\",\"web/app.js\":\"x\"}}} true");
        for (String payload : invalid) {
            assertThrows(
                    IllegalStateException.class,
                    () -> new RoleOutputTool(packet).submit(payload));
        }
    }

    @Test
    void backendArtifactRequiresFixedFileObjectAndInvocationSpecificMetadata() throws Exception {
        String defective = validBackendOutput(3, "class GeneratedVersionPolicy { }");
        FullStackTaskPacket defectPacket =
                packet(FullStackRole.BACKEND, 3, List.of("architect-artifact"), defective);
        RoleOutputTool defectTool = new RoleOutputTool(defectPacket);
        defectTool.submit(defective);

        assertEquals(
                "class GeneratedVersionPolicy { }",
                FullStackService.backendFile(defectTool.artifact()));

        String repaired = validBackendOutput(6, "class GeneratedVersionPolicy { int fixed; }");
        FullStackTaskPacket repairPacket =
                packet(FullStackRole.BACKEND, 6, List.of("troubleshoot-artifact"), repaired);
        RoleOutputTool repairTool = new RoleOutputTool(repairPacket);
        repairTool.submit(repaired);
        assertEquals(
                "class GeneratedVersionPolicy { int fixed; }",
                FullStackService.backendFile(repairTool.artifact()));

        List<String> invalid =
                List.of(
                        "{\"outputs\":{\"file\":\"class GeneratedVersionPolicy {}\",\"fault\":{}}}",
                        "{\"outputs\":{\"fault\":{}}}",
                        defective.replace("fullstack/generated/GeneratedVersionPolicy.java", "wrong.java"),
                        defective.replace("\"content\":\"class GeneratedVersionPolicy { }\"", "\"content\":\"\",\"extra\":true"),
                        defective.replace("\"type\":\"string-comparison\"", "\"type\":false"),
                        repaired.replace("\"repair\":\"numeric-segment-comparison\"", "\"repair\":{}"));
        for (String payload : invalid) {
            int invocation = payload.contains("repair") ? 6 : 3;
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            new RoleOutputTool(
                                            packet(
                                                    FullStackRole.BACKEND,
                                                    invocation,
                                                    List.of("prior"),
                                                    payload))
                                    .submit(payload));
        }
        String oversized = validBackendOutput(6, "x".repeat(65_537));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new RoleOutputTool(
                                        packet(
                                                FullStackRole.BACKEND,
                                                6,
                                                List.of("prior"),
                                                oversized))
                                .submit(oversized));
    }

    @Test
    void frontendRolePromptStatesTheSameThreeFileContract() {
        FullStackTaskPacket packet =
                packet(
                        FullStackRole.FRONTEND,
                        2,
                        List.of("architect-artifact"),
                        validOutput(FullStackRole.FRONTEND, 2));
        List<ModelTransport.RolePrompt> prompts = new ArrayList<>();
        ModelTransport transport =
                new ModelTransport() {
                    private int calls;

                    @Override
                    public Reply complete(RolePrompt prompt) {
                        prompts.add(prompt);
                        if (++calls == 1) {
                            String input;
                            try {
                                input =
                                        com.fasterxml.jackson.databind.json.JsonMapper.builder()
                                                .build()
                                                .writeValueAsString(
                                                        java.util.Map.of(
                                                                "payload",
                                                                packet.replayOutputJson()));
                            } catch (Exception exception) {
                                throw new IllegalStateException(exception);
                            }
                            return new Reply(
                                    "preparing",
                                    "tool-frontend-schema",
                                    "submit_role_output",
                                    input,
                                    new ModelUsage(10, 2));
                        }
                        return new Reply(
                                "accepted", null, null, null, new ModelUsage(10, 1));
                    }

                    @Override
                    public String modelName() {
                        return "trusted-model";
                    }
                };

        new RoleAgentExecutor(stateRoot(), ModelBudget.fullRun(), transport)
                .execute(packet, ModelMode.REAL);

        assertEquals(2, prompts.size());
        assertTrue(prompts.get(0).systemPrompt().contains("web/index.html"));
        assertTrue(prompts.get(0).systemPrompt().contains("web/styles.css"));
        assertTrue(prompts.get(0).systemPrompt().contains("web/app.js"));
        assertTrue(prompts.get(0).systemPrompt().contains("20480"));
        assertTrue(prompts.get(0).systemPrompt().contains("without a plan, timeline"));
        assertTrue(prompts.get(0).systemPrompt().contains("do not include role or invocation"));
    }

    @Test
    void realModeRequiresTrustedTransportAndReplayNeverUsesIt() {
        Path stateRoot = stateRoot();
        RoleAgentExecutor executor = new RoleAgentExecutor(stateRoot, ModelBudget.fullRun(), null);
        FullStackTaskPacket packet =
                packet(
                        FullStackRole.ARCHITECT,
                        1,
                        List.of(),
                        validOutput(FullStackRole.ARCHITECT, 1));

        assertThrows(
                IllegalStateException.class,
                () -> executor.execute(packet, ModelMode.REAL));
        RoleAgentExecutor.RoleExecution replay = executor.execute(packet, ModelMode.REPLAY);
        assertEquals(0, replay.provenance().realModelRequests());
    }

    @Test
    void derivesStableBoundedRoleSessionFromMaximumLengthPublicSession() {
        String longSession = "s".repeat(64);
        Path stateRoot = stateRoot();
        RoleAgentExecutor executor = new RoleAgentExecutor(stateRoot, ModelBudget.fullRun(), null);
        FullStackTaskPacket packet =
                new FullStackTaskPacket(
                        "long-session-packet",
                        "fullstack-case",
                        longSession,
                        8,
                        FullStackRole.RELEASE,
                        "grant-long-session",
                        List.of("prior-artifact"),
                        "Produce the fixed release teaching artifact.",
                        validOutput(FullStackRole.RELEASE, 8));

        RoleAgentExecutor.RoleExecution first = executor.execute(packet, ModelMode.REPLAY);

        assertEquals(64, first.provenance().roleSessionId().length());
        assertTrue(first.provenance().roleSessionId().matches("[a-f0-9]{64}"));
    }

    @Test
    void recoversStrictFourMessageArtifactWithoutCallingRealTransport() {
        Path stateRoot = stateRoot();
        FullStackTaskPacket packet =
                packet(
                        FullStackRole.ARCHITECT,
                        1,
                        List.of(),
                        validOutput(FullStackRole.ARCHITECT, 1));
        RoleAgentExecutor replay =
                new RoleAgentExecutor(stateRoot, ModelBudget.fullRun(), null);
        RoleAgentExecutor.RoleExecution original = replay.execute(packet, ModelMode.REPLAY);
        AtomicInteger calls = new AtomicInteger();
        ModelTransport transport =
                new ModelTransport() {
                    @Override
                    public Reply complete(RolePrompt prompt) {
                        calls.incrementAndGet();
                        throw new AssertionError("recovery must not call transport");
                    }

                    @Override
                    public String modelName() {
                        return "trusted-model";
                    }
                };

        RoleAgentExecutor.RoleExecution recovered =
                new RoleAgentExecutor(stateRoot, ModelBudget.fullRunWithPriorRequests(7), transport)
                        .execute(packet, ModelMode.REAL);

        assertEquals(0, calls.get());
        assertEquals(original.artifact(), recovered.artifact());
        assertTrue(recovered.provenance().recovered());
        assertFalse(recovered.provenance().stateSha256().isBlank());
        assertEquals(2, recovered.provenance().realModelRequests());
    }

    @Test
    void incompleteSingleUserStateMovesToFreshAttemptSession() throws Exception {
        Path stateRoot = stateRoot();
        FullStackTaskPacket packet =
                packet(
                        FullStackRole.FRONTEND,
                        2,
                        List.of("architect-artifact"),
                        validOutput(FullStackRole.FRONTEND, 2));
        ModelTransport unknown =
                new ModelTransport() {
                    @Override
                    public Reply complete(RolePrompt prompt) {
                        throw new IllegalStateException("UNKNOWN_RESULT");
                    }

                    @Override
                    public String modelName() {
                        return "trusted-model";
                    }
                };
        RoleAgentExecutor first =
                new RoleAgentExecutor(stateRoot, ModelBudget.fullRun(), unknown);
        assertThrows(IllegalStateException.class, () -> first.execute(packet, ModelMode.REAL));
        String firstSession =
                Files.list(stateRoot.resolve("agentscope/teaching-user"))
                        .findFirst()
                        .orElseThrow()
                        .getFileName()
                        .toString();
        ScriptedTransport succeeding = new ScriptedTransport(packet.replayOutputJson());
        FullStackTaskPacket changedPromptPacket =
                new FullStackTaskPacket(
                        packet.packetId(),
                        packet.project(),
                        packet.sessionId(),
                        packet.invocation(),
                        packet.role(),
                        packet.capabilityGrantRef(),
                        packet.predecessorArtifactIds(),
                        packet.instruction() + " compact-v2",
                        packet.replayOutputJson());

        RoleAgentExecutor.RoleExecution execution =
                new RoleAgentExecutor(stateRoot, ModelBudget.fullRun(), succeeding)
                        .execute(changedPromptPacket, ModelMode.REAL);

        assertEquals(2, succeeding.calls.get());
        assertNotEquals(firstSession, execution.provenance().roleSessionId());
        assertFalse(execution.provenance().recovered());
        assertEquals(2, Files.list(stateRoot.resolve("agentscope/teaching-user")).count());
    }

    private static FullStackTaskPacket packet(
            FullStackRole role, int invocation, List<String> predecessors, String replayOutput) {
        return new FullStackTaskPacket(
                "packet-" + invocation + "-" + role.name().toLowerCase(Locale.ROOT),
                "fullstack-case",
                "session-a",
                invocation,
                role,
                "grant-" + invocation,
                predecessors,
                "Produce the fixed teaching artifact for " + role,
                replayOutput);
    }

    private static String validOutput(FullStackRole role, int invocation) {
        if (role == FullStackRole.FRONTEND) {
            return "{\"outputs\":{\"files\":{\"web/index.html\":\"<main>validated</main>\","
                    + "\"web/styles.css\":\"body{}\","
                    + "\"web/app.js\":\"document.body.dataset.ready='1';\"}}}";
        }
        if (role == FullStackRole.BACKEND) {
            return validBackendOutput(invocation, "class GeneratedVersionPolicy { }");
        }
        return "{\"outputs\":{\"summary\":\"validated "
                + role
                + " output\"}}";
    }

    private static String validBackendOutput(int invocation, String content) {
        try {
            java.util.Map<String, Object> file =
                    java.util.Map.of(
                            "path",
                            "fullstack/generated/GeneratedVersionPolicy.java",
                            "content",
                            content);
            java.util.Map<String, Object> outputs;
            if (invocation == 3) {
                outputs =
                        java.util.Map.of(
                                "file",
                                file,
                                "fault",
                                java.util.Map.of(
                                        "type",
                                        "string-comparison",
                                        "description",
                                        "comparison defect",
                                        "location",
                                        "isAtLeast",
                                        "method",
                                        "compareTo",
                                        "fix",
                                        "numeric segments",
                                        "failingExample",
                                        java.util.Map.of(
                                                "clientVersion",
                                                "2.10",
                                                "minVersion",
                                                "2.9",
                                                "actualResult",
                                                false,
                                                "expectedResult",
                                                true,
                                                "explanation",
                                                "lexicographic order")));
            } else {
                outputs =
                        java.util.Map.of(
                                "file", file,
                                "repair", "numeric-segment-comparison");
            }
            return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .build()
                    .writeValueAsString(java.util.Map.of("outputs", outputs));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static Path stateRoot() {
        return Path.of("target", "fullstack-role-test", UUID.randomUUID().toString());
    }

    private static final class ScriptedTransport implements ModelTransport {
        private final String payload;
        private final AtomicInteger calls = new AtomicInteger();

        private ScriptedTransport(String payload) {
            this.payload = payload;
        }

        @Override
        public Reply complete(RolePrompt prompt) {
            if (calls.incrementAndGet() == 1) {
                String input;
                try {
                    input =
                            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                                    .build()
                                    .writeValueAsString(java.util.Map.of("payload", payload));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return new Reply(
                        "preparing",
                        "tool-resume-1",
                        "submit_role_output",
                        input,
                        new ModelUsage(10, 2));
            }
            return new Reply(
                    "accepted", null, null, null, new ModelUsage(10, 1));
        }

        @Override
        public String modelName() {
            return "trusted-model";
        }
    }
}
