package dev.agentharness.demo.infra.a2a;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** 本机双进程教学适配器：只实现 A2A 1.0 的直接 Message 返回子集。 */
public final class A2aTransport {
    // 协议输入、输出分别设限；不把模型的 token 上限当成网络字节上限。
    static final int LIMIT = 8192, RESPONSE_LIMIT = 65536;
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static void sendTo(LocalConnection.Target target, String date) throws IOException {
        // 选择阶段已经结束；此方法只发一次，不包含换实例或重发逻辑。
        checkDate(date);
        String messageId = UUID.randomUUID().toString();
        byte[] request = JSON.writeValueAsBytes(Map.of("message", Map.of("messageId", messageId,
                "role", "ROLE_USER", "parts", List.of(Map.of("text", date)))));
        System.out.println("[A2A 发送] messageId=" + messageId + ", date=" + date);
        var response = exchange(target.port(), "/message:send", target.token(), request);
        // 本例仅接受直接 Message，不把 Task 当成已经完成的回答。
        var message = validatedMessage(response);
        System.out.println("[A2A 返回] Message，contextId=" + message.path("contextId").asText());
        // 模型文本只能出现在带前缀的单行内，不能伪造程序状态或终端控制序列。
        System.out.println("[远端回答，非验收] " + visible(message.path("parts").get(0).path("text").asText()));
        System.out.println("[A2A 客户端] 已接收回答；无 Task、无会话续接、无自动重试。");
    }

    public static void client(LocalConnection.Target target, String date) throws IOException {
        // 客户端只验证格式；日期是否存在交给远端工具判断。
        checkDate(date);
        var card = exchange(target.port(), "/.well-known/agent-card.json", null, null);
        validateCard(card, target.port());
        System.out.println("[A2A 客户端] Card 已核验，HTTP+JSON 1.0；使用固定本机服务");
        sendTo(target, date);
    }

    static void validateCard(JsonNode card, int port) throws IOException {
        // 接入信息需同时符合本例的能力、绑定、版本、文本模式和认证约定。
        try { fields(card, card(port).keySet()); }
        catch (IllegalArgumentException invalid) { throw new IOException("card"); }
        if (card.size() != card(port).size() || !card.path("capabilities").equals(JSON.valueToTree(card(port).get("capabilities")))) throw new IOException("card");
        var interfaces = card.path("supportedInterfaces");
        if (!interfaces.isArray() || interfaces.size() != 1) throw new IOException("card");
        var iface = interfaces.get(0);
        if (!iface.isObject() || iface.size() != 3 || !card.path("skills").isArray() || card.path("skills").size() != 1) throw new IOException("card");
        if (!iface.path("protocolBinding").asText().equals("HTTP+JSON")
                || !iface.path("protocolVersion").asText().equals("1.0")
                || !iface.path("url").asText().equals("http://127.0.0.1:" + port)
                || !card.path("defaultInputModes").equals(JSON.valueToTree(List.of("text/plain")))
                || !card.path("defaultOutputModes").equals(JSON.valueToTree(List.of("text/plain")))
                || !card.path("securitySchemes").equals(JSON.valueToTree(card(port).get("securitySchemes")))
                || !card.path("securityRequirements").equals(JSON.valueToTree(card(port).get("securityRequirements")))) throw new IOException("card");
        boolean found = false;
        for (var skill : card.path("skills")) if (skill.path("id").asText().equals("check-date")) found = true;
        if (!found) throw new IOException("skill");
    }

    static JsonNode validatedMessage(JsonNode response) throws IOException {
        try {
            // 严格限定 direct Message；拒绝 Task、未知字段、伪造标识与空回答。
            fields(response, Set.of("message"));
            var message = response.path("message");
            fields(message, Set.of("messageId", "contextId", "role", "parts"));
            if (!message.path("messageId").isTextual() || !message.path("messageId").asText().matches("[A-Za-z0-9_-]{1,64}")
                    || !message.path("contextId").isTextual() || !message.path("contextId").asText().matches("[A-Za-z0-9_-]{1,64}")
                    || !message.path("role").asText().equals("ROLE_AGENT")
                    || !message.path("parts").isArray() || message.path("parts").size() != 1) throw new IllegalArgumentException();
            var part = message.path("parts").get(0);
            fields(part, Set.of("text"));
            if (!part.path("text").isTextual() || part.path("text").asText().isBlank()) throw new IllegalArgumentException();
            return message;
        } catch (IllegalArgumentException invalid) { throw new IOException("response"); }
    }

    static String visible(String text) {
        // 按码点转义控制字符和 Unicode 格式/行分隔符，保留其他可读文本。
        var out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT || cp == 0x2028 || cp == 0x2029)
                out.append(String.format("\\u%04X", cp));
            else out.appendCodePoint(cp);
        });
        return out.toString();
    }

    static JsonNode exchange(int port, String path, String token, byte[] body) throws IOException {
        // 传输方法本身也限制端口与路由，防止内部误用变成任意 URL 客户端。
        if (port < 1 || port > 65535 || !(path.equals("/.well-known/agent-card.json") && body == null
                || path.equals("/message:send") && body != null && body.length <= LIMIT)) throw new IOException("target");
        // 地址只由程序固定主机、已校验端口和固定路由构造，禁用代理和重定向。
        var connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(3000);
        // Card 探测不等待模型，读取超时短于实际消息调用；这是读操作期限，不是总请求期限。
        connection.setReadTimeout(body == null ? 1500 : 210000);
        connection.setRequestProperty("A2A-Version", "1.0");
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/a2a+json");
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (var output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                // 仅输出 HTTP 状态；不把远端错误正文插入模型上下文或终端。
                System.err.println("[A2A HTTP] status=" + status);
                throw new IOException("status");
            }
            if (!"application/a2a+json".equals(connection.getHeaderField("Content-Type"))) throw new IOException("content-type");
            try (var input = connection.getInputStream()) { return JSON.readTree(readBounded(input, RESPONSE_LIMIT)); }
        } finally { connection.disconnect(); }
    }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        // 多读一个字节识别超限，避免仅截断后继续解析。
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("size");
        return bytes;
    }

    public static void checkDate(String date) {
        // 本层只检查交接格式，不抢先把业务上的无效日期当作协议错误。
        if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
    }

    static String dateFrom(byte[] bytes) throws IOException {
        var root = JSON.readTree(bytes);
        // 教学子集只接收新的一轮消息，不默默忽略客户端要求的续接、配置和元数据。
        fields(root, Set.of("message"));
        var message = root.path("message");
        fields(message, Set.of("messageId", "role", "parts"));
        if (!message.path("messageId").isTextual() || !message.path("messageId").asText().matches("[A-Za-z0-9_-]{1,64}")
                || !message.path("role").asText().equals("ROLE_USER")
                || !message.path("parts").isArray() || message.path("parts").size() != 1) throw new IllegalArgumentException();
        var part = message.path("parts").get(0);
        fields(part, Set.of("text"));
        if (!part.path("text").isTextual()) throw new IllegalArgumentException();
        String date = part.path("text").asText();
        checkDate(date);
        return date;
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException();
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw new IllegalArgumentException(); });
    }

    public static final class Service implements AutoCloseable {
        private final HttpServer server;
        // 小队列和单执行许可限制模型费用；网络读取有独立的超时关闭机制。
        private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.AbortPolicy());
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        private final Semaphore active = new Semaphore(1);

        public Service(String token, Function<String, String> answer) throws IOException {
            // 凭据由装配层随机生成；测试也必须提供非空凭据与处理函数。
            if (token == null || token.isBlank() || answer == null) throw new IllegalArgumentException("service");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                // 整个连接最多 200 秒；请求体单独最多 5 秒，关闭后不再进入模型。
                var total = timer.schedule(exchange::close, 200, TimeUnit.SECONDS);
                try { handle(exchange, token, answer); }
                catch (IOException ignored) { /* 对方断开只结束传输，不能宣称模型或远端已取消。 */ }
                finally { total.cancel(false); exchange.close(); }
            });
            server.start();
        }
        public int port() { return server.getAddress().getPort(); }

        private void handle(HttpExchange exchange, String token, Function<String, String> answer) throws IOException {
            String path = exchange.getRequestURI().getRawPath();
            // 不接受浏览器跨源请求或 query；认证检查发生在读取业务数据之前。
            if (exchange.getRequestHeaders().containsKey("Origin") || exchange.getRequestURI().getRawQuery() != null) {
                error(exchange, 403, "PERMISSION_DENIED"); return;
            }
            if (path.equals("/.well-known/agent-card.json") && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, card(port())); return;
            }
            if (!path.equals("/message:send") || !exchange.getRequestMethod().equals("POST")) {
                error(exchange, 404, "NOT_FOUND"); return;
            }
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header == null || !MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                error(exchange, 401, "UNAUTHENTICATED"); return;
            }
            // 不支持的协议版本使用 A2A 专用原因，不与普通参数错误混为一类。
            if (!"1.0".equals(exchange.getRequestHeaders().getFirst("A2A-Version"))) {
                send(exchange, 400, Map.of("error", Map.of(
                        "code", 400, "status", "FAILED_PRECONDITION",
                        "message", "Requested A2A version is not supported; use 1.0.",
                        "details", List.of(Map.of(
                                "@type", "type.googleapis.com/google.rpc.ErrorInfo",
                                "reason", "VERSION_NOT_SUPPORTED", "domain", "a2a-protocol.org")))));
                return;
            }
            // 媒体类型检查独立保留，不将请求头原值反射到错误正文。
            if (!"application/a2a+json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                error(exchange, 400, "INVALID_ARGUMENT"); return;
            }
            String date;
            var reading = new java.util.concurrent.atomic.AtomicBoolean(true);
            var readDeadline = timer.schedule(() -> { if (reading.compareAndSet(true, false)) exchange.close(); }, 5, TimeUnit.SECONDS);
            try {
                date = dateFrom(readBounded(exchange.getRequestBody(), LIMIT));
                // 计时器先赢得状态时，即使最后一个字节刚到，也不再创建 Agent。
                if (!reading.compareAndSet(true, false)) throw new IOException("read timeout");
            }
            catch (IOException | IllegalArgumentException invalid) { error(exchange, 400, "INVALID_ARGUMENT"); return; }
            finally { reading.set(false); readDeadline.cancel(false); }
            // 不把并发请求排进模型队列，明确返回忙碌，让调用方自行决定后续动作。
            if (!active.tryAcquire()) { error(exchange, 429, "RESOURCE_EXHAUSTED"); return; }
            try {
                System.out.println("[A2A 接收] date=" + date + "，创建独立临时 Agent");
                String result = answer.apply(date);
                if (result == null || result.isBlank()) throw new IllegalStateException();
                var response = Map.of("message", Map.of("messageId", UUID.randomUUID().toString(),
                        "contextId", UUID.randomUUID().toString(), "role", "ROLE_AGENT",
                        "parts", List.of(Map.of("text", result))));
                if (JSON.writeValueAsBytes(response).length > RESPONSE_LIMIT) throw new IllegalStateException();
                send(exchange, 200, response);
            } catch (RuntimeException failure) { error(exchange, 500, "INTERNAL"); }
            finally { active.release(); }
        }
        private static void error(HttpExchange exchange, int status, String reason) throws IOException {
            send(exchange, status, Map.of("error", Map.of("code", status, "status", reason, "message", reason)));
        }
        private static void send(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/a2a+json");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        public void close() {
            // 先停止接入，再请求工作线程取消；不会声称供应商已停止生成。
            server.stop(0);
            workers.shutdownNow();
            timer.shutdownNow();
            // 等待有界收尾；供应商是否已撤销生成仍不能由此推断。
            boolean interrupted = false;
            try { if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("workers"); }
            catch (InterruptedException stop) { interrupted = true; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    static Map<String, Object> card(int port) {
        // Card 公开能力与接入要求，绝不携带凭证、提示词或历史。
        return Map.of("name", "Date Agent demo", "description", "本机教学子集：单个完整日期，直接返回 Message；不支持 Task 操作或续接。",
                "version", "0.1.0", "supportedInterfaces", List.of(Map.of("url", "http://127.0.0.1:" + port,
                        "protocolBinding", "HTTP+JSON", "protocolVersion", "1.0")),
                "capabilities", Map.of("streaming", false, "pushNotifications", false),
                "defaultInputModes", List.of("text/plain"), "defaultOutputModes", List.of("text/plain"),
                "skills", List.of(Map.of("id", "check-date", "name", "日期校验", "description", "接收 yyyy-MM-dd，说明日期及 valid 结果", "tags", List.of("date"))),
                "securitySchemes", Map.of("localBearer", Map.of("httpAuthSecurityScheme", Map.of("scheme", "Bearer"))),
                "securityRequirements", List.of(Map.of("schemes", Map.of("localBearer", Map.of("list", List.of())))));
    }
}
