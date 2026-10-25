package dev.agentharness.demo.infra.a2a;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class A2aTransportTest {
    private static byte[] message(String text) {
        return ("{\"message\":{\"messageId\":\"test-1\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"" + text + "\"}]}}").getBytes(StandardCharsets.UTF_8);
    }
    private static int post(int port, String token, String version, byte[] body) throws IOException {
        var c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/message:send").openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(1000); c.setReadTimeout(3000);
        c.setRequestMethod("POST"); c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("A2A-Version", version); c.setRequestProperty("Content-Type", "application/a2a+json");
        c.setDoOutput(true);
        try { try (var out=c.getOutputStream()) { out.write(body); } return c.getResponseCode(); }
        finally { c.disconnect(); }
    }
    @Test public void onlyAcceptsCompleteDateAndNewTextMessage() throws Exception {
        assertEquals("2025-02-29", A2aTransport.dateFrom(message("2025-02-29")));
        assertThrows(IllegalArgumentException.class, () -> A2aTransport.dateFrom(message("03/04")));
        String valid = new String(message("2025-02-29"), StandardCharsets.UTF_8);
        for (String invalid : new String[]{valid.replace("ROLE_USER","user"), valid.replace("\"role\"", "\"contextId\":\"old\",\"role\""), valid.replace("\"text\"", "\"kind\":\"text\",\"text\"")}) {
            assertThrows(IllegalArgumentException.class, () -> A2aTransport.dateFrom(invalid.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(IOException.class, () -> A2aTransport.dateFrom((valid + " {}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> A2aTransport.dateFrom(valid.replace("\"role\"", "\"role\":\"ROLE_USER\",\"role\"").getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void deniesAuthVersionAndOversizeBeforeModel() throws Exception {
        var calls = new AtomicInteger();
        try (var service = new A2aTransport.Service("secret", date -> { calls.incrementAndGet(); return date; })) {
            assertEquals(401, post(service.port(), "wrong", "1.0", message("2025-02-29")));
            assertEquals(400, post(service.port(), "secret", "0.3", message("2025-02-29")));
            assertEquals(400, post(service.port(), "secret", "1.0", new byte[9000]));
            assertEquals(400, post(service.port(), "secret", "1.0", message("03/04")));
            assertEquals(0, calls.get());
        }
    }
    @Test public void versionErrorCarriesA2aReasonBeforeExecution() throws Exception {
        var calls = new AtomicInteger();
        try (var service = new A2aTransport.Service("secret", date -> { calls.incrementAndGet(); return date; })) {
            // 向真实本机 HTTP 适配器发送旧版本，读取完整错误响应，不连接模型。
            var c = (HttpURLConnection) new URL("http://127.0.0.1:" + service.port() + "/message:send").openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(1000); c.setReadTimeout(3000); c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST"); c.setRequestProperty("Authorization", "Bearer secret");
            c.setRequestProperty("A2A-Version", "0.3"); c.setRequestProperty("Content-Type", "application/a2a+json");
            c.setDoOutput(true);
            try {
                try (var out = c.getOutputStream()) { out.write(message("2025-02-29")); }
                assertEquals(400, c.getResponseCode());
                assertEquals("application/a2a+json", c.getHeaderField("Content-Type"));
                byte[] bytes;
                try (var input = c.getErrorStream()) { bytes = A2aTransport.readBounded(input, A2aTransport.RESPONSE_LIMIT); }
                var response = A2aTransport.JSON.readTree(bytes);
                var error = response.path("error");
                assertEquals(400, error.path("code").asInt());
                assertEquals("FAILED_PRECONDITION", error.path("status").asText());
                assertEquals("Requested A2A version is not supported; use 1.0.", error.path("message").asText());
                assertEquals(1, error.path("details").size());
                var detail = error.path("details").get(0);
                assertEquals("type.googleapis.com/google.rpc.ErrorInfo", detail.path("@type").asText());
                assertEquals("VERSION_NOT_SUPPORTED", detail.path("reason").asText());
                assertEquals("a2a-protocol.org", detail.path("domain").asText());
                assertEquals(0, calls.get());
                assertFalse(response.toString().contains("secret"));
                // 留下无认证信息的原始响应及探测结果，供正文引用。
                System.out.println("[版本错误响应] " + response);
                System.out.println("[协议探测] HTTP=400, reason=VERSION_NOT_SUPPORTED, executions=0");
            } finally { c.disconnect(); }
        }
    }

    @Test public void returnsMessageWithoutTaskAndDoesNotDeduplicate() throws Exception {
        var calls = new AtomicInteger();
        try (var service = new A2aTransport.Service("secret", date -> { calls.incrementAndGet(); return "date=" + date + ", valid=false"; })) {
            var card = A2aTransport.exchange(service.port(), "/.well-known/agent-card.json", null, null);
            assertEquals("1.0", card.path("supportedInterfaces").get(0).path("protocolVersion").asText());
            assertFalse(card.toString().contains("secret"));
            var first = A2aTransport.exchange(service.port(), "/message:send", "secret", message("2025-02-29"));
            var second = A2aTransport.exchange(service.port(), "/message:send", "secret", message("2025-02-29"));
            assertFalse(first.has("task"));
            assertEquals("ROLE_AGENT", first.path("message").path("role").asText());
            assertTrue(first.path("message").path("parts").get(0).path("text").asText().contains("valid=false"));
            assertNotEquals(first.path("message").path("contextId"), second.path("message").path("contextId"));
            assertEquals(2, calls.get());
        }
    }
    @Test public void rejectsConcurrentModelWork() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try (var service = new A2aTransport.Service("secret", date -> {
            started.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            return date;
        })) {
            var first = pool.submit(() -> post(service.port(), "secret", "1.0", message("2024-02-29")));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(429, post(service.port(), "secret", "1.0", message("2025-02-29")));
            release.countDown();
            assertEquals(Integer.valueOf(200), first.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test public void failureAndOutputLimitDoNotLeakDetails() throws Exception {
        try (var service = new A2aTransport.Service("secret", date -> { throw new IllegalStateException("private"); })) {
            assertEquals(500, post(service.port(), "secret", "1.0", message("2025-02-29")));
        }
        try (var service = new A2aTransport.Service("secret", date -> "x".repeat(70000))) {
            assertEquals(500, post(service.port(), "secret", "1.0", message("2025-02-29")));
        }
        assertThrows(IOException.class, () -> A2aTransport.readBounded(new ByteArrayInputStream(new byte[11]), 10));
    }
}
