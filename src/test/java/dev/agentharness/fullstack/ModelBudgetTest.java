package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ModelBudgetTest {
    @Test
    void enforcesRequestTokenOutputAndDeadlineBeforeCalls() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        ModelBudget tokenBudget =
                new ModelBudget(28, 4_096, 60_000, Duration.ofMinutes(30), now::get);
        assertThrows(
                IllegalArgumentException.class,
                () -> tokenBudget.beforeRequest(FullStackRole.ARCHITECT, 1, false, 1_025));
        assertEquals(0, tokenBudget.requests());

        tokenBudget.beforeRequest(FullStackRole.ARCHITECT, 1, false, 1_024);
        tokenBudget.record(new ModelUsage(11, 24));
        assertEquals(24, tokenBudget.totalReservedOutputTokens());

        now.addAndGet(Duration.ofMinutes(30).toMillis() + 1L);
        assertThrows(
                IllegalStateException.class,
                () ->
                        tokenBudget.beforeRequest(
                                FullStackRole.ARCHITECT, 1, false, 1_024));
    }

    @Test
    void disablesEveryRetryPath() {
        ModelBudget budget = ModelBudget.fullRun();

        assertFalse(budget.mayRetry(true, 1));
        assertFalse(budget.mayRetry(false, 1));
        assertFalse(budget.mayRetry(true, 2));
    }

    @Test
    void fixedPlanUsesPriorFourteenAndWorstCaseEndsAtTwentyEightWithinSixtyThousand() {
        ModelBudget budget = ModelBudget.fullRunWithPriorUsage(14, 38_079);
        FullStackRole[] roles = {
            FullStackRole.FRONTEND,
            FullStackRole.BACKEND,
            FullStackRole.TEST,
            FullStackRole.TROUBLESHOOT,
            FullStackRole.BACKEND,
            FullStackRole.TEST,
            FullStackRole.RELEASE
        };

        for (int index = 0; index < roles.length; index++) {
            int invocation = index + 2;
            int firstCap = ModelBudget.fixedCap(roles[index], invocation, false);
            budget.beforeRequest(roles[index], invocation, false, firstCap);
            budget.record(new ModelUsage(0, firstCap));
            int finalCap = ModelBudget.fixedCap(roles[index], invocation, true);
            budget.beforeRequest(roles[index], invocation, true, finalCap);
            budget.record(new ModelUsage(0, finalCap));
        }

        assertEquals(14, budget.requests());
        assertEquals(28, budget.totalRequests());
        assertEquals(55_231, budget.totalReservedOutputTokens());
        assertThrows(
                IllegalStateException.class,
                () -> budget.beforeRequest(FullStackRole.ARCHITECT, 1, false, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelBudget.fullRunWithPriorUsage(29, 38_079));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelBudget.fullRun()
                                .beforeRequest(FullStackRole.FRONTEND, 1, false, 1_024));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelBudget.fullRun()
                                .beforeRequest(FullStackRole.RELEASE, 8, true, 257));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelBudget.fullRun()
                                .beforeRequest(FullStackRole.FRONTEND, 8, true, 256));
    }

    @Test
    void persistentLedgerKeepsUnknownReservationAcrossRestart() throws Exception {
        Path run =
                Path.of("target", "model-budget-ledger", UUID.randomUUID().toString());
        Files.createDirectories(run);
        ModelBudget first = ModelBudget.fromTrustedEnvironment(run.toRealPath());

        first.beforeRequest(FullStackRole.ARCHITECT, 1, false, 1_024);
        ModelBudget restarted = ModelBudget.fromTrustedEnvironment(run.toRealPath());

        assertEquals(15, restarted.totalRequests());
        assertEquals(39_103, restarted.totalReservedOutputTokens());
        restarted.beforeRequest(FullStackRole.ARCHITECT, 1, true, 256);
        restarted.record(new ModelUsage(7, 10));
        ModelBudget twiceRestarted = ModelBudget.fromTrustedEnvironment(run.toRealPath());
        assertEquals(16, twiceRestarted.totalRequests());
        assertEquals(39_113, twiceRestarted.totalReservedOutputTokens());
    }

    @Test
    void auditedV1MigratesOnceAndV2RestartKeepsAbsoluteDeadline() throws Exception {
        Path run = Files.createDirectories(Path.of("target", "model-budget-v1", UUID.randomUUID().toString()));
        Files.writeString(
                run.resolve("model-budget.json"),
                "{\"schemaVersion\":1,\"maxRequests\":27,\"maxOutputTokens\":60000,"
                        + "\"totalRequests\":14,\"reservedOutputTokens\":38079,"
                        + "\"inputTokens\":5367,\"outputTokens\":1026,\"pendingCap\":0}");
        AtomicLong now = new AtomicLong(1_700_000_000_000L);

        ModelBudget migrated = ModelBudget.fromTrustedEnvironment(run.toRealPath(), now::get);
        long deadline = migrated.deadlineEpochMillis();
        assertEquals(now.get(), migrated.startedAtEpochMillis());
        assertEquals(Duration.ofMinutes(20).toMillis(), deadline - now.get());
        String v2 = Files.readString(run.resolve("model-budget.json"));
        assertTrue(v2.contains("\"schemaVersion\":2"));
        assertTrue(v2.contains("AUDITED_V1_14_38079_20M"));

        now.addAndGet(Duration.ofMinutes(1).toMillis());
        ModelBudget restarted = ModelBudget.fromTrustedEnvironment(run.toRealPath(), now::get);
        assertEquals(deadline, restarted.deadlineEpochMillis());
        now.set(deadline);
        assertThrows(
                IllegalStateException.class,
                () -> ModelBudget.fromTrustedEnvironment(run.toRealPath(), now::get));
    }

    @Test
    void v2RejectsMissingFutureRollbackAndTamperedTiming() throws Exception {
        long now = 1_700_000_000_000L;
        Path run = Files.createDirectories(Path.of("target", "model-budget-invalid", UUID.randomUUID().toString()));
        LinkedHashMap<String, Object> ledger = validLedger(now);
        writeLedger(run, ledger);
        ModelBudget accepted = ModelBudget.fromTrustedEnvironment(run.toRealPath(), () -> now);
        assertEquals(now + Duration.ofMinutes(30).toMillis(), accepted.deadlineEpochMillis());

        ledger.remove("deadlineEpochMillis");
        writeLedger(run, ledger);
        assertThrows(IllegalStateException.class, () -> ModelBudget.fromTrustedEnvironment(run.toRealPath(), () -> now));
        ledger = validLedger(now);
        ledger.put("startedAtEpochMillis", now + 1);
        writeLedger(run, ledger);
        assertThrows(IllegalStateException.class, () -> ModelBudget.fromTrustedEnvironment(run.toRealPath(), () -> now));
        ledger = validLedger(now);
        ledger.put("deadlineEpochMillis", now + Duration.ofMinutes(29).toMillis());
        writeLedger(run, ledger);
        assertThrows(IllegalStateException.class, () -> ModelBudget.fromTrustedEnvironment(run.toRealPath(), () -> now));
        ledger = validLedger(now);
        writeLedger(run, ledger);
        assertThrows(IllegalStateException.class, () -> ModelBudget.fromTrustedEnvironment(run.toRealPath(), () -> now - 1));
    }

    @Test
    void requestWindowClampsNetworkAndProcessTimeoutToPersistentDeadline() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        ModelBudget full = new ModelBudget(28, 4_096, 60_000, Duration.ofSeconds(181), now::get);
        ModelBudget.RequestWindow fullWindow =
                full.beforeRequest(FullStackRole.FRONTEND, 2, false, 3_072);
        assertEquals(Duration.ofSeconds(180), fullWindow.networkTimeout());
        assertEquals(Duration.ofSeconds(181), fullWindow.processTimeout());

        ModelBudget clamped = new ModelBudget(28, 4_096, 60_000, Duration.ofSeconds(100), now::get);
        ModelBudget.RequestWindow clampedWindow =
                clamped.beforeRequest(FullStackRole.FRONTEND, 2, false, 3_072);
        assertEquals(Duration.ofSeconds(100), clampedWindow.networkTimeout());
        assertEquals(Duration.ofSeconds(100), clampedWindow.processTimeout());
    }

    @Test
    void expiredDeadlineStopsTransportBeforeNetwork() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        ModelBudget budget =
                new ModelBudget(28, 4_096, 60_000, Duration.ofSeconds(1), now::get);
        RecordingCurlExecutor executor = new RecordingCurlExecutor();
        now.addAndGet(Duration.ofSeconds(1).toMillis());

        assertThrows(
                IllegalStateException.class,
                () -> transport(executor, budget).complete(prompt()));
        assertEquals(0, executor.calls);
        assertEquals(0, budget.totalRequests());
    }

    @Test
    void deadlineCrossingDuringReservationStopsNetworkAndReleasesKnownUnusedCap() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        ModelBudget budget =
                new ModelBudget(
                        28,
                        4_096,
                        60_000,
                        Duration.ofMillis(1_500),
                        () -> clock.getAndAdd(1_000L));
        RecordingCurlExecutor executor = new RecordingCurlExecutor();

        assertThrows(
                IllegalStateException.class,
                () -> transport(executor, budget).complete(prompt()));
        assertEquals(0, executor.calls);
        assertEquals(1, budget.totalRequests());
        assertEquals(0, budget.totalReservedOutputTokens());
    }

    @Test
    void curlTransportKeepsSecretAndBodyOffArgvAndRejectsUnsafeEndpoints() {
        RecordingCurlExecutor executor = new RecordingCurlExecutor();
        ModelBudget budget = ModelBudget.fullRun();
        AnthropicCurlTransport transport =
                new AnthropicCurlTransport(
                        URI.create("https://api.example.invalid/v1/messages"),
                        "secret-value",
                        "trusted-model",
                        budget,
                        executor);

        ModelTransport.Reply reply =
                transport.complete(
                        new ModelTransport.RolePrompt(
                                FullStackRole.ARCHITECT,
                                1,
                                "system prompt",
                                "request body marker",
                                null,
                                1_024));

        assertEquals(List.of("/usr/bin/curl", "--disable", "--config", "-"), executor.argv);
        assertFalse(String.join(" ", executor.argv).contains("secret-value"));
        assertFalse(String.join(" ", executor.argv).contains("request body marker"));
        assertTrue(executor.stdin.contains("secret-value"));
        assertTrue(executor.stdin.contains("request body marker"));
        assertTrue(executor.stdin.contains("proto = \"=https\""));
        assertTrue(executor.stdin.contains("max-redirs = 0"));
        assertTrue(executor.stdin.contains("connect-timeout = 10"));
        assertTrue(executor.stdin.contains("max-time = 120"));
        assertTrue(executor.stdin.contains("\\\"max_tokens\\\":1024"));
        assertEquals("ok", reply.text());

        RecordingCurlExecutor frontendExecutor = new RecordingCurlExecutor();
        AnthropicCurlTransport frontendTransport =
                new AnthropicCurlTransport(
                        URI.create("https://api.example.invalid/v1/messages"),
                        "secret-value",
                        "trusted-model",
                        ModelBudget.fullRun(),
                        frontendExecutor);
        frontendTransport.complete(
                new ModelTransport.RolePrompt(
                        FullStackRole.FRONTEND,
                        2,
                        "system prompt",
                        "request body marker",
                        null,
                        3_072));
        assertTrue(frontendExecutor.stdin.contains("web/index.html"));
        assertTrue(frontendExecutor.stdin.contains("web/styles.css"));
        assertTrue(frontendExecutor.stdin.contains("web/app.js"));
        assertTrue(frontendExecutor.stdin.contains("role and invocation are server-bound"));
        assertTrue(frontendExecutor.stdin.contains("\\\"max_tokens\\\":3072"));
        assertTrue(frontendExecutor.stdin.contains("max-time = 180"));
        assertEquals(Duration.ofSeconds(185), frontendExecutor.timeout);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AnthropicCurlTransport(
                                URI.create("http://api.example.invalid/v1/messages"),
                                "secret",
                                "model",
                                ModelBudget.fullRun(),
                                executor));
    }

    @Test
    void curlTransportFailsClosedOnRedirectTimeoutAndOversizedResponse() {
        RecordingCurlExecutor redirect = new RecordingCurlExecutor();
        redirect.result = new AnthropicCurlTransport.CurlResult(302, "{}", "");
        AnthropicCurlTransport redirectTransport = transport(redirect);
        assertThrows(IllegalStateException.class, () -> redirectTransport.complete(prompt()));
        assertEquals(1, redirect.calls);

        RecordingCurlExecutor timeout = new RecordingCurlExecutor();
        timeout.failure = AnthropicCurlTransport.CurlFailure.resultUnknown();
        ModelBudget timeoutBudget = ModelBudget.fullRun();
        AnthropicCurlTransport timeoutTransport = transport(timeout, timeoutBudget);
        assertThrows(IllegalStateException.class, () -> timeoutTransport.complete(prompt()));
        assertEquals(1, timeout.calls);
        assertEquals(1_024, timeoutBudget.totalReservedOutputTokens());

        RecordingCurlExecutor preConnect = new RecordingCurlExecutor();
        preConnect.failure = AnthropicCurlTransport.CurlFailure.preConnectFailure();
        ModelBudget preConnectBudget = ModelBudget.fullRun();
        AnthropicCurlTransport preConnectTransport = transport(preConnect, preConnectBudget);
        assertThrows(IllegalStateException.class, () -> preConnectTransport.complete(prompt()));
        assertEquals(1, preConnect.calls);
        assertEquals(1, preConnectBudget.totalRequests());
        assertEquals(0, preConnectBudget.totalReservedOutputTokens());

        RecordingCurlExecutor oversized = new RecordingCurlExecutor();
        oversized.failure = AnthropicCurlTransport.CurlFailure.responseTooLarge();
        ModelBudget oversizedBudget = ModelBudget.fullRun();
        AnthropicCurlTransport oversizedTransport = transport(oversized, oversizedBudget);
        assertThrows(IllegalStateException.class, () -> oversizedTransport.complete(prompt()));
        assertEquals(1, oversized.calls);
        assertEquals(1_024, oversizedBudget.totalReservedOutputTokens());

        RecordingCurlExecutor missingUsage = new RecordingCurlExecutor();
        missingUsage.result =
                new AnthropicCurlTransport.CurlResult(
                        200, "{\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}", "");
        ModelBudget missingUsageBudget = ModelBudget.fullRun();
        assertThrows(
                IllegalStateException.class,
                () -> transport(missingUsage, missingUsageBudget).complete(prompt()));
        assertEquals(1, missingUsage.calls);
        assertEquals(1_024, missingUsageBudget.totalReservedOutputTokens());
    }

    @Test
    void capExhaustionStopsBeforeNetworkAndToolRejectionKeepsSettledUsage() {
        RecordingCurlExecutor neverCalled = new RecordingCurlExecutor();
        ModelBudget insufficient =
                new ModelBudget(28, 4_096, 1_000, Duration.ofMinutes(30), System::currentTimeMillis);
        assertThrows(
                IllegalStateException.class,
                () -> transport(neverCalled, insufficient).complete(prompt()));
        assertEquals(0, neverCalled.calls);
        assertEquals(0, insufficient.totalRequests());

        RecordingCurlExecutor rejected = new RecordingCurlExecutor();
        rejected.result =
                new AnthropicCurlTransport.CurlResult(
                        200,
                        "{\"content\":[{\"type\":\"tool_use\",\"id\":\"tool-rejected\","
                                + "\"name\":\"submit_role_output\",\"input\":{\"payload\":"
                                + "\"{\\\"role\\\":\\\"ARCHITECT\\\",\\\"outputs\\\":{\\\"summary\\\":\\\"x\\\"}}\"}}],"
                                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":2}}",
                        "");
        ModelBudget rejectedBudget = ModelBudget.fullRun();
        AnthropicCurlTransport rejectedTransport = transport(rejected, rejectedBudget);
        FullStackTaskPacket packet =
                new FullStackTaskPacket(
                        "packet-budget-reject",
                        "demo",
                        "budget-reject",
                        1,
                        FullStackRole.ARCHITECT,
                        "grant-budget-reject",
                        List.of(),
                        "Produce the fixed architecture artifact.",
                        "{\"outputs\":{\"summary\":\"fixture\"}}");

        assertThrows(
                IllegalStateException.class,
                () ->
                        new RoleAgentExecutor(
                                        Path.of(
                                                "target",
                                                "model-budget-tool-reject",
                                                UUID.randomUUID().toString()),
                                        rejectedBudget,
                                        rejectedTransport)
                                .execute(packet, ModelMode.REAL));
        assertEquals(2, rejected.calls);
        assertEquals(2, rejectedBudget.totalRequests());
        assertEquals(4, rejectedBudget.outputTokens());
        assertEquals(4, rejectedBudget.totalReservedOutputTokens());
    }

    @Test
    void transportAcceptsTextWithOneToolAndPreservesToolResultProtocolId() {
        RecordingCurlExecutor executor = new RecordingCurlExecutor();
        executor.result =
                new AnthropicCurlTransport.CurlResult(
                        200,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"preparing\"},"
                                + "{\"type\":\"tool_use\",\"id\":\"tool-17\","
                                + "\"name\":\"submit_role_output\","
                                + "\"input\":{\"payload\":\"{}\"}}],"
                                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":2}}",
                        "");
        AnthropicCurlTransport transport = transport(executor);
        ModelTransport.Reply first = transport.complete(prompt());
        assertTrue(first.isToolUse());
        assertEquals("tool-17", first.toolId());

        executor.result =
                new AnthropicCurlTransport.CurlResult(
                        200,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"accepted\"}],"
                                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}",
                        "");
        transport.complete(
                new ModelTransport.RolePrompt(
                        FullStackRole.ARCHITECT,
                                1,
                        "system",
                        "prompt",
                        first.toolId(),
                        first.toolInputJson(),
                        "verified-result",
                                256));
        assertTrue(executor.stdin.contains("tool_result"));
        assertTrue(executor.stdin.contains("tool_use_id"));
        assertTrue(executor.stdin.contains("tool-17"));
        assertTrue(executor.stdin.contains("verified-result"));
        assertTrue(executor.stdin.contains("\\\"max_tokens\\\":256"));
    }

    private static AnthropicCurlTransport transport(RecordingCurlExecutor executor) {
        return transport(executor, ModelBudget.fullRun());
    }

    private static AnthropicCurlTransport transport(
            RecordingCurlExecutor executor, ModelBudget budget) {
        return new AnthropicCurlTransport(
                URI.create("https://api.example.invalid/v1/messages"),
                "secret",
                "model",
                budget,
                executor);
    }

    private static ModelTransport.RolePrompt prompt() {
        return new ModelTransport.RolePrompt(
                FullStackRole.ARCHITECT, 1, "system", "prompt", null, 1_024);
    }

    private static LinkedHashMap<String, Object> validLedger(long now) {
        LinkedHashMap<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("schemaVersion", 2);
        ledger.put("maxRequests", 28);
        ledger.put("maxOutputTokens", 60_000);
        ledger.put("startedAtEpochMillis", now);
        ledger.put("deadlineEpochMillis", now + Duration.ofMinutes(30).toMillis());
        ledger.put("migrationMarker", "NEW_RUN_30M");
        ledger.put("totalRequests", 14);
        ledger.put("reservedOutputTokens", 38_079);
        ledger.put("inputTokens", 5_367);
        ledger.put("outputTokens", 1_026);
        ledger.put("pendingCap", 0);
        return ledger;
    }

    private static void writeLedger(Path run, LinkedHashMap<String, Object> ledger) throws Exception {
        com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build()
                .writeValue(run.resolve("model-budget.json").toFile(), ledger);
    }

    private static final class RecordingCurlExecutor implements AnthropicCurlTransport.CurlExecutor {
        private List<String> argv = List.of();
        private String stdin = "";
        private int calls;
        private Duration timeout;
        private AnthropicCurlTransport.CurlResult result =
                new AnthropicCurlTransport.CurlResult(
                        200,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":1}}",
                        "");
        private AnthropicCurlTransport.CurlFailure failure;

        @Override
        public AnthropicCurlTransport.CurlResult execute(
                List<String> argv, String stdin, Duration timeout, int responseLimitBytes)
                throws AnthropicCurlTransport.CurlFailure {
            this.calls++;
            this.argv = List.copyOf(argv);
            this.stdin = stdin;
            this.timeout = timeout;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
