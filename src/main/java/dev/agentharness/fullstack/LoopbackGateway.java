package dev.agentharness.fullstack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 固定目标、单请求、有限容量的预览 HTTP 网关。 */
public final class LoopbackGateway {
    private static final String TARGET_HOST = "candidate";
    private static final int TARGET_PORT = 8080;
    private static final int LISTEN_PORT = 8080;
    private static final int HEADER_LIMIT = 16_384;
    private static final int DIRECTION_LIMIT = 65_536;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private LoopbackGateway() {}

    public static void main(String[] args) throws Exception {
        // 网关不接受 URL、host、port 或代理目标参数。
        if (args.length != 0) {
            throw new IllegalArgumentException("gateway takes no arguments");
        }
        // 固定四 worker 和八连接队列；饱和连接立即关闭。
        ThreadPoolExecutor workers =
                new ThreadPoolExecutor(
                        4,
                        4,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(8),
                        new ThreadPoolExecutor.AbortPolicy());
        try (ServerSocket listener = new ServerSocket()) {
            // 容器只监听自己的 8080，宿主暴露由 Docker 精确绑定 127.0.0.1。
            listener.bind(new InetSocketAddress("0.0.0.0", LISTEN_PORT), 8);
            while (!Thread.currentThread().isInterrupted()) {
                Socket client = listener.accept();
                try {
                    // 每个连接最多处理一个 HTTP 请求。
                    workers.execute(() -> forward(client));
                } catch (RejectedExecutionException saturated) {
                    // 队列饱和时不排队扩张，也不回显请求内容。
                    client.close();
                }
            }
        } finally {
            // 进程退出时回收工作线程。
            workers.shutdownNow();
        }
    }

    private static void forward(Socket client) {
        try (client) {
            // 客户端读取使用固定十秒超时。
            client.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            // 先完整验证一个有限 HTTP 请求，恶意目标不会触发任何上游连接。
            byte[] request = readRequest(client);
            try (Socket upstream = new Socket()) {
                // 连接只拨固定 candidate:8080，且有独立十秒 connect deadline。
                upstream.connect(
                        new InetSocketAddress(
                                InetAddress.getByName(TARGET_HOST), TARGET_PORT),
                        SOCKET_TIMEOUT_MILLIS);
                // 上游读取同样使用十秒超时。
                upstream.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                upstream.getOutputStream().write(request);
                upstream.getOutputStream().flush();
                // 响应必须先通过完整 HTTP/1 结构与逐跳头校验，再以唯一长度转发。
                byte[] response = readResponse(upstream);
                client.getOutputStream().write(response);
                client.getOutputStream().flush();
            }
        } catch (IOException | IllegalArgumentException rejected) {
            // 固定网关拒绝错误不打印 Host、URL、Header 或正文。
        }
    }

    private static byte[] readRequest(Socket client) throws IOException {
        InputStream input = client.getInputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            // 每次阻塞读取都收紧到整体十秒 deadline 的剩余时间。
            setRemainingTimeout(client, deadline);
            int value = input.read();
            if (value < 0) {
                throw new IOException("request closed");
            }
            headerBytes.write(value);
            if (headerBytes.size() > HEADER_LIMIT) {
                throw new IOException("request headers too large");
            }
            // 只把 CRLFCRLF 识别为 header 终止符。
            state = nextHeaderState(state, value);
        }
        String headers = headerBytes.toString(StandardCharsets.ISO_8859_1);
        String[] lines = headers.substring(0, headers.length() - 4).split("\\r\\n", -1);
        if (lines.length == 0) {
            throw new IllegalArgumentException("request line missing");
        }
        String[] requestLine = lines[0].split(" ", -1);
        // 禁止 CONNECT、absolute-form、authority-form 和非 HTTP/1.x 请求。
        if (requestLine.length != 3
                || !Set.of("GET", "POST", "PUT").contains(requestLine[0])
                || !requestLine[1].startsWith("/")
                || requestLine[1].startsWith("//")
                || requestLine[1].contains("://")
                || !("HTTP/1.1".equals(requestLine[2]) || "HTTP/1.0".equals(requestLine[2]))) {
            throw new IllegalArgumentException("request target rejected");
        }
        int contentLength = 0;
        boolean contentLengthSeen = false;
        boolean connectionSeen = false;
        List<String> forwarded = new ArrayList<>();
        forwarded.add(lines[0]);
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("header invalid");
            }
            String rawName = line.substring(0, colon);
            if (!isToken(rawName)) {
                throw new IllegalArgumentException("header name invalid");
            }
            String name = rawName.toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (hasCtl(value)) {
                throw new IllegalArgumentException("header value invalid");
            }
            // 分块、升级、代理协议和客户端指定上游均被固定网关拒绝。
            if (Set.of(
                            "transfer-encoding",
                            "trailer",
                            "upgrade",
                            "keep-alive",
                            "proxy-connection",
                            "expect")
                    .contains(name)
                    || "forwarded".equals(name)
                    || name.startsWith("x-forwarded-")) {
                throw new IllegalArgumentException("proxy header rejected");
            }
            if ("content-length".equals(name)) {
                if (contentLengthSeen) {
                    throw new IllegalArgumentException("duplicate content length");
                }
                contentLengthSeen = true;
                contentLength = parseContentLength(value);
                forwarded.add("Content-Length: " + contentLength);
            } else if ("connection".equals(name)) {
                if (connectionSeen
                        || !Set.of("close", "keep-alive")
                                .contains(value.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("connection header rejected");
                }
                connectionSeen = true;
            } else if (!"host".equals(name) && !"connection".equals(name)) {
                forwarded.add(line);
            }
        }
        // 上游 Host 与连接生命周期由固定网关控制。
        forwarded.add("Host: candidate:8080");
        forwarded.add("Connection: close");
        byte[] rewritten =
                (String.join("\r\n", forwarded) + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1);
        if ((long) rewritten.length + contentLength > DIRECTION_LIMIT) {
            throw new IOException("request too large");
        }
        ByteArrayOutputStream request = new ByteArrayOutputStream(rewritten.length + contentLength);
        request.write(rewritten);
        request.write(readExact(client, input, contentLength, deadline));
        return request.toByteArray();
    }

    private static int nextHeaderState(int state, int value) {
        // 有限状态机匹配 CRLFCRLF，并允许新 CR 重新开始。
        int[] expected = {'\r', '\n', '\r', '\n'};
        if (value == expected[state]) {
            return state + 1;
        }
        return value == '\r' ? 1 : 0;
    }

    private static int parseContentLength(String value) {
        try {
            int length = Integer.parseInt(value);
            if (length < 0 || length > DIRECTION_LIMIT) {
                throw new IllegalArgumentException("content length rejected");
            }
            return length;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("content length invalid", invalid);
        }
    }

    private static byte[] readExact(
            Socket client, InputStream input, int length, long deadline) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            // 慢速持续发送也不能刷新整体十秒 deadline。
            if (client != null) {
                setRemainingTimeout(client, deadline);
            }
            int read = input.read(body, offset, length - offset);
            if (read < 0) {
                throw new IOException("request body incomplete");
            }
            offset += read;
        }
        return body;
    }

    private static byte[] readResponse(Socket upstream) throws IOException {
        // 上游响应也使用独立整体十秒 deadline，持续慢读不能无限占用 worker。
        return readResponse(upstream, Duration.ofSeconds(10));
    }

    static byte[] readResponse(Socket upstream, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        return readResponse(upstream, upstream.getInputStream(), deadline);
    }

    static byte[] sanitizeResponse(byte[] raw) throws IOException {
        // 单元测试使用相同解析器验证恶意响应，不建立任何网络连接。
        return readResponse(null, new java.io.ByteArrayInputStream(raw), Long.MAX_VALUE);
    }

    private static byte[] readResponse(Socket socket, InputStream input, long deadline)
            throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            if (socket != null) {
                setRemainingTimeout(socket, deadline);
            }
            int value = input.read();
            if (value < 0) {
                throw new IOException("response closed");
            }
            headerBytes.write(value);
            if (headerBytes.size() > HEADER_LIMIT) {
                throw new IOException("response headers too large");
            }
            state = nextHeaderState(state, value);
        }
        String headers = headerBytes.toString(StandardCharsets.ISO_8859_1);
        String[] lines = headers.substring(0, headers.length() - 4).split("\\r\\n", -1);
        String[] status = lines[0].split(" ", 3);
        if (status.length < 2
                || !("HTTP/1.1".equals(status[0]) || "HTTP/1.0".equals(status[0]))
                || !status[1].matches("[2-5][0-9]{2}")) {
            throw new IllegalArgumentException("response status invalid");
        }
        int contentLength = -1;
        boolean connectionSeen = false;
        List<String> forwarded = new ArrayList<>();
        forwarded.add(lines[0]);
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("response header invalid");
            }
            String rawName = line.substring(0, colon);
            if (!isToken(rawName)) {
                throw new IllegalArgumentException("response header name invalid");
            }
            String name = rawName.toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (hasCtl(value)) {
                throw new IllegalArgumentException("response header value invalid");
            }
            if (Set.of(
                            "transfer-encoding",
                            "trailer",
                            "upgrade",
                            "keep-alive",
                            "proxy-connection")
                    .contains(name)) {
                throw new IllegalArgumentException("response hop header rejected");
            }
            if ("content-length".equals(name)) {
                if (contentLength >= 0) {
                    throw new IllegalArgumentException("duplicate response content length");
                }
                contentLength = parseContentLength(value);
            } else if ("connection".equals(name)) {
                if (connectionSeen
                        || !Set.of("close", "keep-alive")
                                .contains(value.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("response connection rejected");
                }
                connectionSeen = true;
            } else {
                forwarded.add(line);
            }
        }
        if (contentLength >= 0
                && (long) headerBytes.size() + contentLength > DIRECTION_LIMIT) {
            throw new IOException("response length rejected");
        }
        // 固定请求强制上游 close；无长度响应只允许有界读到 EOF，再重建唯一长度。
        byte[] body =
                contentLength >= 0
                        ? readExact(socket, input, contentLength, deadline)
                        : readUntilEof(socket, input, deadline, DIRECTION_LIMIT - headerBytes.size());
        contentLength = body.length;
        // 网关重建连接语义和唯一长度，逐跳头不会从候选透传到浏览器。
        forwarded.add("Content-Length: " + contentLength);
        forwarded.add("Connection: close");
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(
                (String.join("\r\n", forwarded) + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
        response.write(body);
        return response.toByteArray();
    }

    private static byte[] readUntilEof(
            Socket socket, InputStream input, long deadline, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int total = 0;
        int read;
        while (true) {
            if (socket != null) {
                setRemainingTimeout(socket, deadline);
            }
            read = input.read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            total += read;
            if (total > limit) {
                throw new IOException("response too large");
            }
            output.write(buffer, 0, read);
        }
    }

    private static boolean isToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(Character.isLetterOrDigit(character)
                    || "!#$%&'*+-.^_`|~".indexOf(character) >= 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasCtl(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static void setRemainingTimeout(Socket socket, long deadline) throws IOException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new java.net.SocketTimeoutException("gateway request deadline");
        }
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        // SO_TIMEOUT 不接受 0，因为 0 表示无限；向上取整到至少一毫秒。
        socket.setSoTimeout((int) Math.max(1L, Math.min(Integer.MAX_VALUE, remainingMillis)));
    }

}
