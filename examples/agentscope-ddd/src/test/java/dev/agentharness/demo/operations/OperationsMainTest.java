package dev.agentharness.demo.operations;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationsMainTest {
    @TempDir Path temp;

    @Test
    void requestIdAllowsOnlyBoundedAscii() {
        assertTrue(OperationsMain.validRequestId("r-0123456789abcdef"));
        assertFalse(OperationsMain.validRequestId("r-0123456789abcde"));
        assertFalse(OperationsMain.validRequestId("r-0123456789abcdef\nSECRET"));
        assertFalse(OperationsMain.validRequestId(null));
    }

    @Test
    void privateDirectoryRejectsSymlinkAncestors() throws Exception {
        // macOS 的 /var 本身可能是系统符号链接，先取临时目录真实路径。
        Path actualTemp = temp.toRealPath();
        Path real = Files.createDirectory(actualTemp.resolve("real"));
        Path link = Files.createSymbolicLink(actualTemp.resolve("link"), real);
        assertTrue(OperationsMain.privatePathIsSafe(real));
        assertFalse(OperationsMain.privatePathIsSafe(link.resolve("private")));
    }

    @Test
    void fakeRuleStaysOfflineAndKeepsValidatedInput() {
        // 离线函数记录实际收到的日期，不调用付费模型。
        AtomicReference<String> seen = new AtomicReference<>();
        boolean result = OperationsMain.processDate("r-0123456789abcdef", "2024-02-29", date -> {
            seen.set(date);
            return true;
        });
        assertTrue(result);
        assertEquals("2024-02-29", seen.get());
        assertFalse(OperationsMain.processDate("r-0123456789abcdef", "2025-02-29", date -> false));
        assertThrows(IllegalArgumentException.class,
                () -> OperationsMain.processDate("bad\nlog", "2024-02-29", date -> true));
        assertThrows(IllegalArgumentException.class,
                () -> OperationsMain.processDate("r-0123456789abcdef", "2024-2-29", date -> true));
    }

    @Test
    void toolResultMustMatchRequestedDate() {
        // 即使工具被调用，错误日期也不能冒充这次请求的完成结果。
        OperationsDateTool tool = new OperationsDateTool("r-0123456789abcdef");
        tool.check("2024-02-29");
        assertEquals(Boolean.TRUE, tool.singleResultFor("2024-02-29"));
        assertEquals(null, tool.singleResultFor("2024-02-28"));
        tool.check("2024-02-29");
        assertEquals(null, tool.singleResultFor("2024-02-29"));
    }

    @Test
    void authenticationAndBusyPermitRejectBeforePaidWork() {
        // 错误或不完整的 Bearer 均不能通过模型入口。
        String token = "a".repeat(64);
        assertTrue(OperationsMain.constantTimeTokenEquals(token, "Bearer " + token));
        assertFalse(OperationsMain.constantTimeTokenEquals(token, "Bearer " + "b".repeat(64)));
        assertFalse(OperationsMain.constantTimeTokenEquals(token, null));
        // 已有请求持有唯一许可时，第二项请求立即得到忙碌判据。
        assertTrue(OperationsMain.tryReserveModel());
        try {
            assertFalse(OperationsMain.tryReserveModel());
        } finally {
            OperationsMain.releaseModel();
        }
    }

    @Test
    void httpRejectsUnauthorizedAndConcurrentCallsBeforeFakeRule() throws Exception {
        // 受控函数代替模型，真实 HTTP 验证 401/429 不触发额外执行。
        String token = "a".repeat(64);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        var workers = Executors.newFixedThreadPool(2);
        server.setExecutor(workers);
        server.createContext("/", exchange -> OperationsMain.handle(exchange, (id, date) -> {
            calls.incrementAndGet();
            int running = active.incrementAndGet();
            maxActive.accumulateAndGet(running, Math::max);
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fake timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } finally {
                active.decrementAndGet();
            }
            return true;
        }, token));
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/validate");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String id = "r-0123456789abcdef";
            HttpRequest noAuth = HttpRequest.newBuilder(uri).header("X-Request-Id", id)
                    .POST(HttpRequest.BodyPublishers.ofString("2024-02-29")).build();
            assertEquals(401, client.send(noAuth, HttpResponse.BodyHandlers.discarding()).statusCode());
            HttpRequest duplicate = HttpRequest.newBuilder(uri).header("X-Request-Id", id)
                    .header("Authorization", "Bearer " + token).header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("2024-02-29")).build();
            assertEquals(401, client.send(duplicate, HttpResponse.BodyHandlers.discarding()).statusCode());
            HttpRequest longAuth = HttpRequest.newBuilder(uri).header("X-Request-Id", id)
                    .header("Authorization", "Bearer " + token + "x")
                    .POST(HttpRequest.BodyPublishers.ofString("2024-02-29")).build();
            assertEquals(401, client.send(longAuth, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(0, calls.get());
            HttpRequest valid = HttpRequest.newBuilder(uri).header("X-Request-Id", id)
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("2024-02-29")).build();
            var first = client.sendAsync(valid, HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(429, client.send(valid, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(1, calls.get());
            assertEquals(1, maxActive.get());
            release.countDown();
            assertEquals(200, first.get(3, TimeUnit.SECONDS).statusCode());
        } finally {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
