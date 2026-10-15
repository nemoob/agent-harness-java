package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoopbackGatewayTest {
    @Test
    void rebuildsResponseWithOneLengthAndClosedConnection() throws Exception {
        byte[] sanitized =
                LoopbackGateway.sanitizeResponse(
                        bytes(
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                        + "Content-Length: 2\r\nConnection: keep-alive\r\n\r\n{}"));

        String response = new String(sanitized, StandardCharsets.ISO_8859_1);
        assertTrue(response.contains("Content-Length: 2\r\n"));
        assertTrue(response.contains("Connection: close\r\n"));
        assertArrayEquals(bytes("{}"), java.util.Arrays.copyOfRange(sanitized, sanitized.length - 2, sanitized.length));
    }

    @Test
    void rejectsAmbiguousHopByHopMalformedOversizedAndIncompleteResponses() {
        for (String raw :
                List.of(
                        "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx",
                        "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\nx",
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                        "HTTP/1.1 101 Switching Protocols\r\nContent-Length: 0\r\nUpgrade: websocket\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: x-hop\r\nX-Hop: injected\r\n\r\n",
                        "HTTP/1.1 099 Invalid\r\nContent-Length: 0\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Length: 65537\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nx",
                        "HTTP/1.1 200 OK\r\nBad Header: x\r\nContent-Length: 0\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nX-Test: bad\u0001value\r\nContent-Length: 0\r\n\r\n")) {
            assertThrows(Exception.class, () -> LoopbackGateway.sanitizeResponse(bytes(raw)), raw);
        }
    }

    @Test
    void slowResponseBodyUsesOneOverallDeadline() throws Exception {
        try (java.net.ServerSocket listener =
                new java.net.ServerSocket(
                        0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread server =
                    new Thread(
                            () -> {
                                try (java.net.Socket peer = listener.accept()) {
                                    peer.getOutputStream()
                                            .write(
                                                    bytes(
                                                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nx"));
                                    peer.getOutputStream().flush();
                                    Thread.sleep(2_000);
                                } catch (Exception ignored) {
                                    // 客户端 deadline 主动关闭连接是本负例的预期结果。
                                }
                            });
            server.start();
            try (java.net.Socket client =
                    new java.net.Socket("127.0.0.1", listener.getLocalPort())) {
                assertThrows(
                        java.net.SocketTimeoutException.class,
                        () -> LoopbackGateway.readResponse(client, Duration.ofMillis(200)));
            }
            server.join(3_000);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }
}
