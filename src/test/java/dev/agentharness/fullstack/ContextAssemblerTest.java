package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextAssemblerTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void assemblesVerifiedRoleSpecificArtifactsAndUntrustedEvidenceContent() {
        FullStackArtifact architect = artifact("fs-architect", FullStackRole.ARCHITECT, 1, "{\"role\":\"ARCHITECT\",\"outputs\":{\"summary\":\"frozen contract\",\"sentinel\":\"ARCH_ONLY\"}}");
        FullStackArtifact unrelated = artifact("fs-unrelated", FullStackRole.FRONTEND, 2, "{\"role\":\"FRONTEND\",\"outputs\":{\"sentinel\":\"MUST_NOT_LEAK\"}}");
        ContextAssembler assembler = new ContextAssembler(List.of(architect, unrelated));
        ContextAssembler.EvidenceBlock runner =
                assembler.runnerEvidence(
                        "runner-initial",
                        4,
                        "candidate-sha",
                        "source-sha",
                        "version-2.10-vs-2.9",
                        0,
                        true,
                        false,
                        "assertion failed");

        ContextAssembler.AssembledContext context =
                assembler.assemble(
                        FullStackRole.TROUBLESHOOT,
                        5,
                        List.of(architect.artifactId()),
                        Map.of("expected", "numeric segment comparison"),
                        List.of(runner));

        assertTrue(context.json().contains("frozen contract"));
        assertTrue(context.json().contains("candidate-sha"));
        assertTrue(context.json().contains("source-sha"));
        assertTrue(context.json().contains("version-2.10-vs-2.9"));
        assertTrue(context.json().contains("UNTRUSTED_EVIDENCE"));
        assertTrue(context.json().contains("artifactId"));
        assertTrue(context.json().contains("revision"));
        assertTrue(context.json().contains("sha256"));
        assertFalse(context.json().contains("MUST_NOT_LEAK"));
    }

    @Test
    void frontendReceivesCompactArchitectureSummaryAndCompleteFixedUiContract() {
        FullStackArtifact architect =
                artifact(
                        "fs-frontend-architect",
                        FullStackRole.ARCHITECT,
                        1,
                        "{\"outputs\":{\"summary\":\"frozen homepage contract\","
                                + "\"plan\":[\"FAKE_TIMELINE_SENTINEL\",\"phase-2\"],"
                                + "\"implementationNotes\":\"not needed by frontend\"}}}");
        Map<String, Object> contract =
                Map.of(
                        "httpRoutes", List.of("GET /login", "POST /login", "GET /api/session", "GET /api/features", "PUT /api/features/{id}", "POST /api/features/{id}/preview"),
                        "auth", "cookie plus CSRF plus exact Origin",
                        "errors", List.of("REVISION_CONFLICT", "SESSION_REQUIRED", "CSRF_REJECTED", "ORIGIN_REJECTED", "INVALID_VERSION"),
                        "responsive", "narrow and desktop widths",
                        "accessibility", "semantic labels keyboard visible focus status text textContent");

        ContextAssembler.AssembledContext context =
                new ContextAssembler(List.of(architect))
                        .assemble(
                                FullStackRole.FRONTEND,
                                2,
                                List.of(architect.artifactId()),
                                contract,
                                List.of());

        assertTrue(context.json().contains("frozen homepage contract"));
        assertTrue(context.json().contains("GET /api/session"));
        assertTrue(context.json().contains("cookie plus CSRF plus exact Origin"));
        assertTrue(context.json().contains("REVISION_CONFLICT"));
        assertTrue(context.json().contains("responsive"));
        assertTrue(context.json().contains("accessibility"));
        assertFalse(context.json().contains("FAKE_TIMELINE_SENTINEL"));
        assertFalse(context.json().contains("implementationNotes"));
        assertTrue(context.dynamicBytes() < 16_384);
    }

    @Test
    void rejectsTamperedMissingAndOversizedDynamicContextWithoutTruncatingArtifacts() {
        FullStackArtifact valid = artifact("fs-valid", FullStackRole.ARCHITECT, 1, "{\"outputs\":{\"summary\":\"ok\"}}");
        FullStackArtifact tampered =
                new FullStackArtifact(
                        valid.artifactId(),
                        valid.producerRole(),
                        valid.invocation(),
                        valid.inputPacketId(),
                        valid.predecessorArtifactIds(),
                        valid.payloadJson() + " ",
                        valid.sha256());

        assertThrows(
                IllegalStateException.class,
                () ->
                        new ContextAssembler(List.of(tampered))
                                .assemble(
                                        FullStackRole.FRONTEND,
                                        2,
                                        List.of(tampered.artifactId()),
                                        Map.of(),
                                        List.of()));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ContextAssembler(List.of(valid))
                                .assemble(
                                        FullStackRole.FRONTEND,
                                        2,
                                        List.of("fs-missing"),
                                        Map.of(),
                                        List.of()));
        FullStackArtifact oversized =
                artifact(
                        "fs-oversized",
                        FullStackRole.ARCHITECT,
                        1,
                        "{\"outputs\":{\"summary\":\"" + "x".repeat(17_000) + "\"}}");
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ContextAssembler(List.of(oversized))
                                .assemble(
                                        FullStackRole.FRONTEND,
                                        2,
                                        List.of(oversized.artifactId()),
                                        Map.of(),
                                        List.of()));

        ContextAssembler assembler = new ContextAssembler(List.of());
        ContextAssembler.EvidenceBlock first =
                assembler.evidence("evidence-first", 1, Map.of("value", "x".repeat(13_000)));
        ContextAssembler.EvidenceBlock second =
                assembler.evidence("evidence-second", 2, Map.of("value", "y".repeat(13_000)));
        // 两块证据各自低于 16 KiB，但动态总量超过 24 KiB 时仍须整体拒绝。
        assertThrows(
                IllegalStateException.class,
                () ->
                        assembler.assemble(
                                FullStackRole.TROUBLESHOOT,
                                5,
                                List.of(),
                                Map.of(),
                                List.of(first, second)));
    }

    @Test
    void canonicalEvidenceIsStableAcrossMapOrdersAndJvmRestart(@TempDir Path temp)
            throws Exception {
        LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", 2);
        nested.put("a", 1);
        LinkedHashMap<String, Object> arrayObject = new LinkedHashMap<>();
        arrayObject.put("b", "second");
        arrayObject.put("a", "first");
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("zeta", "tail");
        content.put("alpha", nested);
        content.put("list", List.of(arrayObject));

        ContextAssembler assembler = new ContextAssembler(List.of());
        ContextAssembler.EvidenceBlock local =
                assembler.evidence("restart-evidence", 5, content);
        ContextAssembler.EvidenceBlock immutableMap =
                assembler.evidence(
                        "immutable-map",
                        5,
                        Map.of(
                                "alpha",
                                Map.of("a", 1, "z", 2),
                                "list",
                                List.of(Map.of("a", "first", "b", "second")),
                                "zeta",
                                "tail"));
        assertEquals(
                "{\"alpha\":{\"a\":1,\"z\":2},\"list\":[{\"a\":\"first\",\"b\":\"second\"}],\"zeta\":\"tail\"}",
                local.contentJson());
        assertEquals(local.contentJson(), immutableMap.contentJson());
        assertEquals(local.sha256(), immutableMap.sha256());

        Path input = temp.resolve("input.json");
        Path output = temp.resolve("output.json");
        Path error = temp.resolve("error.log");
        // 子进程使用相反字段顺序读取同一语义对象，模拟跨 JVM 重启后的状态读取。
        Files.writeString(
                input,
                "{\"list\":[{\"b\":\"second\",\"a\":\"first\"}],\"zeta\":\"tail\",\"alpha\":{\"z\":2,\"a\":1}}",
                StandardCharsets.UTF_8);
        String classPath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                classPath,
                                RestartEvidenceProcess.class.getName(),
                                input.toString(),
                                output.toString())
                        .redirectError(error.toFile())
                        .start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "restart evidence process timed out");
        assertEquals(
                0,
                process.exitValue(),
                () -> {
                    try {
                        return Files.readString(error);
                    } catch (Exception exception) {
                        return exception.toString();
                    }
                });

        JsonNode restarted = JSON.readTree(output.toFile());
        assertEquals(local.contentJson(), restarted.path("contentJson").asText());
        assertEquals(local.sha256(), restarted.path("evidenceSha256").asText());

        ContextAssembler.AssembledContext localContext =
                assembler.assemble(
                        FullStackRole.TROUBLESHOOT,
                        5,
                        List.of(),
                        fixedContract(),
                        List.of(local));
        assertEquals(localContext.json(), restarted.path("contextJson").asText());
        assertEquals(localContext.sha256(), restarted.path("contextSha256").asText());
        assertEquals(
                sha(instruction(localContext)), restarted.path("promptSha256").asText());
    }

    @Test
    void canonicalEvidenceChangesWhenFieldsValuesOrArrayOrderChange() {
        ContextAssembler assembler = new ContextAssembler(List.of());
        ContextAssembler.EvidenceBlock baseline =
                assembler.evidence(
                        "baseline", 1, Map.of("list", List.of(1, 2), "value", "same"));
        ContextAssembler.EvidenceBlock fieldChanged =
                assembler.evidence(
                        "field-changed", 1, Map.of("list", List.of(1, 2), "other", "same"));
        ContextAssembler.EvidenceBlock valueChanged =
                assembler.evidence(
                        "value-changed", 1, Map.of("list", List.of(1, 2), "value", "different"));
        ContextAssembler.EvidenceBlock arrayChanged =
                assembler.evidence(
                        "array-changed", 1, Map.of("list", List.of(2, 1), "value", "same"));

        assertNotEquals(baseline.sha256(), fieldChanged.sha256());
        assertNotEquals(baseline.sha256(), valueChanged.sha256());
        assertNotEquals(baseline.sha256(), arrayChanged.sha256());
    }

    @Test
    void stderrOverEightKibBecomesBoundedSanitizedSummaryWithOriginalHash() {
        ContextAssembler assembler = new ContextAssembler(List.of());

        ContextAssembler.EvidenceBlock block =
                assembler.runnerEvidence(
                        "runner-long",
                        4,
                        "candidate",
                        "source",
                        "failure-vector",
                        1,
                        true,
                        false,
                        "token=secret /Users/private/path " + "z".repeat(12_000));
        ContextAssembler.AssembledContext context =
                assembler.assemble(
                        FullStackRole.TROUBLESHOOT,
                        5,
                        List.of(),
                        Map.of(),
                        List.of(block));

        assertTrue(context.json().contains("\"truncated\":true"));
        assertTrue(context.json().contains("originalSha256"));
        assertFalse(context.json().contains("secret"));
        assertFalse(context.json().contains("/Users/private/path"));
        assertTrue(context.json().getBytes(StandardCharsets.UTF_8).length < 24_576);
    }

    private static FullStackArtifact artifact(
            String id, FullStackRole role, int invocation, String payload) {
        return new FullStackArtifact(
                id,
                role,
                invocation,
                "packet-" + invocation,
                List.of(),
                payload,
                sha(payload));
    }

    private static Map<String, Object> fixedContract() {
        // 使用生产 TROUBLESHOOT 的三键契约，覆盖 Map.of 的非固定迭代顺序。
        return Map.of(
                "expected", "numeric segment comparison",
                "failureVector", "2.10 versus 2.9",
                "featureId", "homepage_recommendation");
    }

    private static Map<String, Object> reversedFixedContract() {
        LinkedHashMap<String, Object> contract = new LinkedHashMap<>();
        // 子进程按与生产声明相反的顺序重建契约，验证递归规范化消除顺序漂移。
        contract.put("featureId", "homepage_recommendation");
        contract.put("failureVector", "2.10 versus 2.9");
        contract.put("expected", "numeric segment comparison");
        return contract;
    }

    private static String instruction(ContextAssembler.AssembledContext context) {
        // 使用生产提示中的固定上下文标记复算可审计 prompt SHA。
        return "Troubleshoot the verified failure.\nCONTEXT_JSON_DATA:\n" + context.json();
    }

    private static String sha(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    public static final class RestartEvidenceProcess {
        private RestartEvidenceProcess() {}

        public static void main(String[] args) throws Exception {
            // 新 JVM 从磁盘读取保持原字段顺序的普通 Map。
            Map<String, Object> content =
                    JSON.readValue(
                            Files.readString(Path.of(args[0]), StandardCharsets.UTF_8),
                            new TypeReference<LinkedHashMap<String, Object>>() {});
            ContextAssembler assembler = new ContextAssembler(List.of());
            ContextAssembler.EvidenceBlock evidence =
                    assembler.evidence("restart-evidence", 5, content);
            ContextAssembler.AssembledContext context =
                    assembler.assemble(
                            FullStackRole.TROUBLESHOOT,
                            5,
                            List.of(),
                            reversedFixedContract(),
                            List.of(evidence));
            ObjectNode result = JSON.createObjectNode();
            // 子进程只输出脱敏的规范字节与 hash 供父进程逐项比较。
            result.put("contentJson", evidence.contentJson());
            result.put("evidenceSha256", evidence.sha256());
            result.put("contextJson", context.json());
            result.put("contextSha256", context.sha256());
            result.put("promptSha256", sha(instruction(context)));
            Files.writeString(
                    Path.of(args[1]), JSON.writeValueAsString(result), StandardCharsets.UTF_8);
        }
    }
}
