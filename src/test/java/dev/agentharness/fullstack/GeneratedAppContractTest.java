package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratedAppContractTest {
    @Test
    void fixedScaffoldDeclaresRoutesBoundsAndBrowserDefenses() throws Exception {
        String server = resource("ConfigHttpServer.java");

        assertTrue(server.contains("/login"));
        assertTrue(server.contains("/api/session"));
        assertTrue(server.contains("/api/features"));
        assertTrue(server.contains("/preview"));
        assertTrue(server.contains("/health"));
        assertTrue(server.contains("MAX_REQUEST_BYTES = 16_384"));
        assertTrue(server.contains("MAX_RESPONSE_BYTES = 65_536"));
        assertTrue(server.contains("Duration.ofSeconds(5)"));
        assertTrue(server.contains("new ArrayBlockingQueue<>(queueCapacity)"));
        assertTrue(server.contains("boundedExecutor(4, 8)"));
        assertTrue(server.contains("read.get(REQUEST_DEADLINE.toMillis()"));
        assertTrue(server.contains("REQUEST_BODY_DEADLINE"));
        assertTrue(server.contains("MAX_LOGIN_FAILURES = 8"));
        assertTrue(server.contains("MAX_SESSIONS = 16"));
        assertTrue(server.contains("HttpOnly"));
        assertTrue(server.contains("SameSite=Strict"));
        assertTrue(server.contains("X-CSRF-Token"));
        assertTrue(server.contains("origin.equals(requestOrigin)"));
        assertTrue(server.contains("sendJson(exchange, 409"));
        assertTrue(server.contains("application/x-www-form-urlencoded"));
        assertTrue(server.contains("MessageDigest.isEqual"));
        assertTrue(server.contains("browser-handoff"));
        assertTrue(server.contains("CREATE_NEW"));
        assertFalse(server.contains("X-Bootstrap-Token"));
        assertTrue(server.contains("X-Content-Type-Options"));
        assertTrue(server.contains("homepage_recommendation") || resource("FeatureStore.java").contains("homepage_recommendation"));
        assertTrue(server.contains("minVersion"));
        assertTrue(server.contains("expectedRevision"));
        assertTrue(server.contains("configRevision"));
        assertTrue(server.contains("clientVersion"));
        assertTrue(server.contains("effective"));
        assertTrue(server.contains("reasonCode"));
        assertTrue(server.contains("VERSION_ALLOWED"));
        assertTrue(server.contains("VERSION_TOO_LOW"));
        assertTrue(server.contains("FEATURE_DISABLED"));
        assertTrue(server.contains("INVALID_VERSION"));
        assertFalse(server.contains("currentVersion"));
        assertFalse(server.contains("eligible"));
        assertFalse(server.contains("compare-version"));
        assertFalse(server.contains("requiredVersion"));
        assertFalse(server.contains("Access-Control-Allow-Origin: *"));
    }

    @Test
    void fixedStoreAndVersionContractRequireRevisionAndStrictSegments() throws Exception {
        String store = resource("FeatureStore.java");
        String policy = resource("VersionPolicy.java");

        assertTrue(store.contains("expectedRevision"));
        assertTrue(store.contains("RevisionConflictException"));
        assertTrue(store.contains("current.configRevision() + 1"));
        assertTrue(store.contains("feature-config.json"));
        assertTrue(store.contains("FileChannel"));
        assertTrue(store.contains("force(true)"));
        assertTrue(store.contains("ATOMIC_MOVE"));
        assertTrue(store.contains("AtomicMoveNotSupportedException"));
        assertTrue(store.contains("Files.isSymbolicLink"));
        assertFalse(store.contains("compare-version"));
        assertTrue(
                policy.contains(
                        "boolean isAtLeast(String clientVersion, String minVersion)"));
        assertTrue(policy.contains("2–4"));
        assertTrue(policy.contains("0..999999"));
        assertTrue(policy.contains("leading zero"));
    }

    @Test
    void containerSelfCheckCoversAuthCsrfOriginConflictSizeAndVersion() throws Exception {
        String checks = resource("GeneratedAppChecks.java");

        for (String marker :
                List.of(
                        "smoke-login",
                        "one-time-smoke",
                        "browser-handoff-login",
                        "handoff-replay",
                        "DUAL_TOKEN_RESTART_PASS",
                        "LOGIN_RATE_LIMIT_PASS",
                        "SLOW_CLIENT_DEADLINE_PASS",
                        "missing-session",
                        "features-session-required",
                        "bad-csrf",
                        "bad-origin",
                        "revision-conflict",
                        "body-too-large",
                        "restart-get",
                        "restart-preview",
                        "INVALID_VERSION",
                        "FEATURE_DISABLED",
                        "STORE_FAILURES_REJECTED",
                        "2.10",
                        "2.9",
                        "static-page")) {
            assertTrue(checks.contains(marker), marker);
        }
    }

    @Test
    void manifestListsOnlyFixedScaffoldSources() throws Exception {
        String manifest = resource("manifest.txt");
        assertEquals(
                List.of(
                        "ConfigHttpServer.java",
                        "FeatureStore.java",
                        "GeneratedAppChecks.java",
                        "VersionPolicy.java"),
                manifest.lines().filter(line -> !line.isBlank()).sorted().toList());
    }

    private static String resource(String name) throws Exception {
        String path = "/fullstack/scaffold/" + name;
        try (InputStream input = GeneratedAppContractTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new AssertionError("missing scaffold resource " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
