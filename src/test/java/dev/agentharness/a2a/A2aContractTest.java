package dev.agentharness.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Test;

class A2aContractTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void cardSendPollAndIdsFollowSemanticSubset() throws Exception {
        try (A2aServer server = new A2aServer(uniqueRoot("a2a"))) {
            URI base = server.start(0);
            A2aClient client = new A2aClient();

            AgentCard card = client.card(base);
            assertEquals("1.0", card.protocolVersion());
            assertEquals("/message:send", card.interfaces().get("messageSend"));

            A2aMessage message = new A2aMessage("msg-001", "ctx-001", null, "CFG-001", "diagnose");
            A2aResponse sent = client.send(base, message);
            assertEquals("task", sent.kind());
            assertNotNull(sent.task().id());
            assertEquals("ctx-001", sent.task().contextId());
            assertEquals("CFG-001", sent.task().businessTaskId());
            assertTrue(sent.task().artifacts().get(0).artifactId().startsWith("artifact-"));

            A2aTask polled =
                    client.getTask(base, sent.task().id(), "ctx-001", "CFG-001");
            assertEquals(sent.task(), polled);

            A2aMessage followUp =
                    new A2aMessage("msg-002", "ctx-001", sent.task().id(), "CFG-001", "continue");
            assertEquals(sent.task().id(), client.send(base, followUp).task().id());
        }
    }

    @Test
    void rejectsVersionMediaTypeMissingFieldsAndIdMismatch() throws Exception {
        try (A2aServer server = new A2aServer(uniqueRoot("a2a-errors"))) {
            URI base = server.start(0);
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest wrongVersion =
                    HttpRequest.newBuilder(base.resolve("/message:send"))
                            .header("A2A-Version", "2.0")
                            .header("Content-Type", "application/a2a+json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build();
            assertEquals(400, http.send(wrongVersion, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest wrongMediaType =
                    HttpRequest.newBuilder(base.resolve("/message:send"))
                            .header("A2A-Version", "1.0")
                            .header("Content-Type", "text/plain")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build();
            assertEquals(415, http.send(wrongMediaType, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest mediaTypeSuffix =
                    HttpRequest.newBuilder(base.resolve("/message:send"))
                            .header("A2A-Version", "1.0")
                            .header("Content-Type", "application/a2a+json-evil")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build();
            assertEquals(415, http.send(mediaTypeSuffix, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest missingFields =
                    HttpRequest.newBuilder(base.resolve("/message:send"))
                            .header("A2A-Version", "1.0")
                            .header("Content-Type", "application/a2a+json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build();
            assertEquals(400, http.send(missingFields, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest oversized =
                    HttpRequest.newBuilder(base.resolve("/message:send"))
                            .header("A2A-Version", "1.0")
                            .header("Content-Type", "application/a2a+json")
                            .POST(HttpRequest.BodyPublishers.ofString("x".repeat(65_537)))
                            .build();
            assertEquals(413, http.send(oversized, HttpResponse.BodyHandlers.ofString()).statusCode());

            String duplicateIdJson =
                    "{\"messageId\":\"first\",\"messageId\":\"second\","
                            + "\"contextId\":\"ctx\",\"businessTaskId\":\"CFG-001\","
                            + "\"text\":\"diagnose\"}";
            HttpRequest duplicateField =
                    HttpRequest.newBuilder(base.resolve("/message:send"))
                            .header("A2A-Version", "1.0")
                            .header("Content-Type", "Application/A2A+JSON; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(duplicateIdJson))
                            .build();
            assertEquals(400, http.send(duplicateField, HttpResponse.BodyHandlers.ofString()).statusCode());

            A2aClient client = new A2aClient();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.card(URI.create("https://example.com")));
            A2aResponse created =
                    client.send(base, new A2aMessage("msg-a", "ctx-a", null, "CFG-001", "start"));
            A2aResponse rejected =
                    client.sendForResponse(
                            base,
                            new A2aMessage(
                                    "msg-b", "different-context", created.task().id(), "CFG-001", "continue"));
            assertEquals("ID_MISMATCH", rejected.error().code());
        }
    }

    @Test
    void supportsDirectMessageAndRejectsInvalidCardTaskAndArtifactContracts() throws Exception {
        try (A2aServer server = new A2aServer(uniqueRoot("a2a-direct"))) {
            URI base = server.start(0);
            A2aClient client = new A2aClient();

            A2aResponse direct =
                    client.send(
                            base,
                            new A2aMessage(
                                    "msg-direct", "ctx-direct", null, "CFG-001", "reply-only"));
            assertEquals("message", direct.kind());
            assertEquals("ctx-direct", direct.message().contextId());

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            A2aClient.validateCard(
                                    new AgentCard(
                                            "bad",
                                            "1.1",
                                            Map.of("messageSend", "/wrong"),
                                            List.of())));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            A2aClient.validateTask(
                                    new A2aTask(
                                            "task-1",
                                            "ctx-1",
                                            "CFG-001",
                                            "COMPLETED",
                                            List.of(new A2aArtifact(null, "CFG-001", "name", "text")))));
        }
    }

    @Test
    void getTaskRejectsResponseIdContextAndBusinessMismatch() throws Exception {
        A2aClient client = new A2aClient();
        try (StubServer stub =
                stubServer(
                        "/tasks/task-expected",
                        A2aServer.MEDIA_TYPE,
                        taskResponseJson("task-other", "ctx-client", "CFG-001"))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> client.getTask(stub.base(), "task-expected", "ctx-client", "CFG-001"));
        }
        try (StubServer stub =
                stubServer(
                        "/tasks/task-expected",
                        A2aServer.MEDIA_TYPE,
                        taskResponseJson("task-expected", "ctx-other", "CFG-001"))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> client.getTask(stub.base(), "task-expected", "ctx-client", "CFG-001"));
        }
        try (StubServer stub =
                stubServer(
                        "/tasks/task-expected",
                        A2aServer.MEDIA_TYPE,
                        taskResponseJson("task-expected", "ctx-client", "OTHER-001"))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> client.getTask(stub.base(), "task-expected", "ctx-client", "CFG-001"));
        }
    }

    @Test
    void replyOnlyWithTaskRequiresExistingConsistentMapping() throws Exception {
        try (A2aServer server = new A2aServer(uniqueRoot("a2a-reply-task"))) {
            URI base = server.start(0);
            A2aClient client = new A2aClient();

            A2aMessage unknown =
                    new A2aMessage(
                            "msg-reply-unknown",
                            "ctx-known",
                            "task-missing",
                            "CFG-001",
                            "reply-only");
            assertEquals(404, postMessage(base, unknown).statusCode());

            A2aTask created =
                    client.send(
                                    base,
                                    new A2aMessage(
                                            "msg-reply-create",
                                            "ctx-known",
                                            null,
                                            "CFG-001",
                                            "diagnose"))
                            .task();
            A2aMessage wrongMapping =
                    new A2aMessage(
                            "msg-reply-wrong",
                            "ctx-other",
                            created.id(),
                            "CFG-001",
                            "reply-only");
            assertEquals(409, postMessage(base, wrongMapping).statusCode());

            A2aResponse reply =
                    client.send(
                            base,
                            new A2aMessage(
                                    "msg-reply-known",
                                    "ctx-known",
                                    created.id(),
                                    "CFG-001",
                                    "reply-only"));
            assertEquals("message", reply.kind());
            assertEquals(created.id(), reply.message().taskId());
        }
    }

    @Test
    void clientRejectsOversizedWrongMediaTypeConflictingAndMismatchedResponses()
            throws Exception {
        A2aClient client = new A2aClient();
        A2aMessage request =
                new A2aMessage("msg-client", "ctx-client", null, "CFG-001", "diagnose");

        try (StubServer stub =
                stubServer(
                        "application/a2a+json-evil",
                        "{\"kind\":\"error\",\"error\":{\"code\":\"BAD\",\"message\":\"bad\"}}")) {
            assertThrows(IllegalStateException.class, () -> client.sendForResponse(stub.base(), request));
        }

        try (StubServer stub = stubServer(A2aServer.MEDIA_TYPE, "x".repeat(65_537))) {
            assertThrows(IllegalStateException.class, () -> client.sendForResponse(stub.base(), request));
        }

        A2aTask mismatchedContext =
                new A2aTask(
                        "task-1",
                        "ctx-other",
                        "CFG-001",
                        "COMPLETED",
                        List.of(
                                new A2aArtifact(
                                        "artifact-1", "CFG-001", "result", "complete")));
        assertThrows(
                IllegalStateException.class,
                () -> A2aClient.validateResponse(A2aResponse.task(mismatchedContext), request));

        A2aTask mismatchedBusiness =
                new A2aTask(
                        "task-1",
                        "ctx-client",
                        "OTHER-001",
                        "COMPLETED",
                        List.of(
                                new A2aArtifact(
                                        "artifact-1", "OTHER-001", "result", "complete")));
        assertThrows(
                IllegalStateException.class,
                () -> A2aClient.validateResponse(A2aResponse.task(mismatchedBusiness), request));

        A2aMessage followUpRequest =
                new A2aMessage(
                        "msg-follow", "ctx-client", "task-expected", "CFG-001", "continue");
        A2aTask mismatchedTaskId =
                new A2aTask(
                        "task-other",
                        "ctx-client",
                        "CFG-001",
                        "COMPLETED",
                        List.of(
                                new A2aArtifact(
                                        "artifact-1", "CFG-001", "result", "complete")));
        assertThrows(
                IllegalStateException.class,
                () -> A2aClient.validateResponse(A2aResponse.task(mismatchedTaskId), followUpRequest));

        A2aTask mismatchedArtifactBusiness =
                new A2aTask(
                        "task-1",
                        "ctx-client",
                        "CFG-001",
                        "COMPLETED",
                        List.of(
                                new A2aArtifact(
                                        "artifact-1", "OTHER-001", "result", "complete")));
        assertThrows(
                IllegalStateException.class,
                () -> A2aClient.validateResponse(A2aResponse.task(mismatchedArtifactBusiness), request));

        A2aResponse conflicting =
                new A2aResponse(
                        "task",
                        new A2aMessage(
                                "reply-1", "ctx-client", null, "CFG-001", "complete"),
                        mismatchedArtifactBusiness,
                        null);
        assertThrows(
                IllegalStateException.class,
                () -> A2aClient.validateResponse(conflicting, request));

        A2aMessage injectedTask =
                new A2aMessage(
                        "reply-injected", "ctx-client", "task-injected", "CFG-001", "complete");
        assertThrows(
                IllegalStateException.class,
                () -> A2aClient.validateResponse(A2aResponse.message(injectedTask), request));
    }

    @Test
    void clientClosesSlowResponseAtOverallDeadline() throws Exception {
        HttpServer server =
                HttpServer.create(
                        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.createContext(
                "/message:send",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", A2aServer.MEDIA_TYPE);
                    exchange.sendResponseHeaders(200, 100);
                    try (var output = exchange.getResponseBody()) {
                        output.write('{');
                        output.flush();
                        Thread.sleep(5_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.setExecutor(executor);
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            A2aMessage message =
                    new A2aMessage("msg-slow", "ctx-slow", null, "CFG-001", "diagnose");

            assertTimeout(
                    Duration.ofSeconds(4),
                    () ->
                            assertThrows(
                                    IllegalStateException.class,
                                    () -> new A2aClient().sendForResponse(base, message)));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void slowBodiesExpireAndFourBlockedWorkersBecomeAvailable() throws Exception {
        try (A2aServer server = new A2aServer(uniqueRoot("a2a-slow"))) {
            URI base = server.start(0);
            List<Socket> sockets = new ArrayList<>();
            try {
                for (int index = 0; index < 4; index++) {
                    Socket socket = new Socket("127.0.0.1", base.getPort());
                    sockets.add(socket);
                    String headers =
                            "POST /message:send HTTP/1.1\r\n"
                                    + "Host: 127.0.0.1\r\n"
                                    + "A2A-Version: 1.0\r\n"
                                    + "Content-Type: application/a2a+json\r\n"
                                    + "Content-Length: 100\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }

                Thread.sleep(1_300);

                assertTimeout(
                        Duration.ofSeconds(3),
                        () -> assertEquals("1.0", new A2aClient().card(base).protocolVersion()));
            } finally {
                for (Socket socket : sockets) {
                    socket.close();
                }
            }
        }
    }

    @Test
    void saturatedExecutorRejectsPromptlyThenRecovers() throws Exception {
        try (A2aServer server = new A2aServer(uniqueRoot("a2a-queue"))) {
            URI base = server.start(0);
            List<Socket> sockets = new ArrayList<>();
            try {
                for (int index = 0; index < 12; index++) {
                    Socket socket = slowRequest(base);
                    sockets.add(socket);
                }

                try (Socket overflow = new Socket("127.0.0.1", base.getPort())) {
                    overflow.setSoTimeout(1_000);
                    overflow.getOutputStream()
                            .write(
                                    ("GET /.well-known/agent-card.json HTTP/1.1\r\n"
                                                    + "Host: 127.0.0.1\r\n\r\n")
                                            .getBytes(StandardCharsets.US_ASCII));
                    overflow.getOutputStream().flush();
                    try {
                        int firstByte = overflow.getInputStream().read();
                        if (firstByte != -1) {
                            String response =
                                    Character.toString((char) firstByte)
                                            + new String(
                                                    overflow.getInputStream().readNBytes(64),
                                                    StandardCharsets.US_ASCII);
                            assertTrue(response.contains("503"));
                        }
                    } catch (SocketTimeoutException timeout) {
                        fail("saturated executor left the overflow connection hanging", timeout);
                    } catch (SocketException expectedConnectionRefusal) {
                        // 有界执行器以连接关闭稳定拒绝超出容量的交换。
                    }
                }

                Thread.sleep(2_300);
                assertEquals("1.0", new A2aClient().card(base).protocolVersion());
            } finally {
                for (Socket socket : sockets) {
                    socket.close();
                }
            }
        }
    }

    @Test
    void fixedTaskCapacityReturnsStableServiceError() throws Exception {
        try (A2aServer server = new A2aServer(uniqueRoot("a2a-capacity"))) {
            URI base = server.start(0);
            A2aClient client = new A2aClient();
            for (int index = 0; index < A2aServer.MAX_TASKS; index++) {
                A2aResponse response =
                        client.send(
                                base,
                                new A2aMessage(
                                        "msg-cap-" + index,
                                        "ctx-cap-" + index,
                                        null,
                                        "CFG-001",
                                        "diagnose"));
                assertEquals("task", response.kind());
            }

            A2aResponse saturated =
                    client.sendForResponse(
                            base,
                            new A2aMessage(
                                    "msg-cap-overflow",
                                    "ctx-cap-overflow",
                                    null,
                                    "CFG-001",
                                    "diagnose"));

            assertEquals("error", saturated.kind());
            assertEquals("SERVER_CAPACITY_REACHED", saturated.error().code());
        }
    }

    @Test
    void unknownTaskRequiresReconciliationWithoutResending() throws Exception {
        try (A2aServer server = new A2aServer(uniqueRoot("a2a-unknown"))) {
            URI base = server.start(0);
            A2aClient client = new A2aClient();

            Reconciliation reconciliation = client.reconcile(base, "unknown-task");

            assertEquals("UNKNOWN_RECONCILIATION_REQUIRED", reconciliation.status());
            assertFalse(reconciliation.sendAttempted());
            assertEquals(0, server.messageSendCount());
        }
    }

    private Path uniqueRoot(String prefix) {
        return Path.of("target", "test-state", prefix + "-" + UUID.randomUUID());
    }

    private Socket slowRequest(URI base) throws IOException {
        Socket socket = new Socket("127.0.0.1", base.getPort());
        String headers =
                "POST /message:send HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "A2A-Version: 1.0\r\n"
                        + "Content-Type: application/a2a+json\r\n"
                        + "Content-Length: 100\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private StubServer stubServer(String contentType, String body) throws IOException {
        return stubServer("/message:send", contentType, body);
    }

    private StubServer stubServer(String path, String contentType, String body) throws IOException {
        HttpServer server =
                HttpServer.create(
                        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
        server.createContext(
                path,
                exchange -> {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();
        return new StubServer(server, executor);
    }

    private HttpResponse<String> postMessage(URI base, A2aMessage message) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(base.resolve("/message:send"))
                        .header("A2A-Version", "1.0")
                        .header("Content-Type", A2aServer.MEDIA_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(message)))
                        .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String taskResponseJson(String taskId, String contextId, String businessTaskId) {
        return "{\"kind\":\"task\",\"task\":{\"id\":\""
                + taskId
                + "\",\"contextId\":\""
                + contextId
                + "\",\"businessTaskId\":\""
                + businessTaskId
                + "\",\"status\":\"COMPLETED\",\"artifacts\":[{\"artifactId\":\"artifact-1\","
                + "\"businessTaskId\":\""
                + businessTaskId
                + "\",\"name\":\"result\",\"text\":\"complete\"}]}}";
    }

    private record StubServer(HttpServer server, ExecutorService executor) implements AutoCloseable {
        private URI base() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
