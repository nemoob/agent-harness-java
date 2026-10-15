package fullstack.generated;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 固定 HTTP、认证、JSON 与资源限制安全壳；模型不得修改本文件。 */
public final class ConfigHttpServer implements AutoCloseable {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private static final int MAX_SESSIONS = 16;
    private static final int MAX_LOGIN_FAILURES = 8;
    private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(5);
    private static final Duration LOGIN_FAILURE_WINDOW = Duration.ofSeconds(60);
    private final FeatureStore store;
    private final VersionPolicy versionPolicy;
    private final String origin;
    private final Map<String, String> tokenHashes;
    private final Path authStateRoot;
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Object loginLock = new Object();
    private final ArrayDeque<Long> loginFailures = new ArrayDeque<>();
    private final boolean healthFault;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final ThreadPoolExecutor bodyReaders;

    public ConfigHttpServer(
            int port,
            String origin,
            Path authConfigFile,
            Path dataRoot,
            VersionPolicy policy)
            throws IOException {
        // 容器自检默认使用健康模式。
        this(port, origin, authConfigFile, dataRoot, policy, false);
    }

    public ConfigHttpServer(
            int port,
            String origin,
            Path authConfigFile,
            Path dataRoot,
            VersionPolicy policy,
            boolean healthFault)
            throws IOException {
        // Origin 必须是受信启动参数，不允许空值或通配符。
        if (origin == null || origin.isBlank() || origin.contains("*")) {
            throw new IllegalArgumentException("exact origin is required");
        }
        // 候选只读取 tokenId 与 SHA-256，不接收任何登录 token 明文配置。
        this.tokenHashes = readAuthConfig(authConfigFile);
        // 消费标记位于专用 data/auth-state 目录并跨重启保留。
        this.authStateRoot = secureAuthStateRoot(dataRoot);
        // 持久化 store 只使用独立可写 data 目录，不读取生成源码目录。
        this.store = new FeatureStore(dataRoot);
        // 保存固定受信 Origin 与生成策略实例。
        this.origin = origin;
        this.versionPolicy = Objects.requireNonNull(policy, "policy");
        // 可信发布适配器可启用确定性 health 失败，用于回滚验收。
        this.healthFault = healthFault;
        // 容器内监听 8080；宿主发布仅允许由 Runner 映射到 127.0.0.1。
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 16);
        // 主处理器固定四线程和八请求队列，饱和时由 AbortPolicy 稳定拒绝连接。
        this.executor = boundedExecutor(4, 8);
        // 请求体读取使用独立有界线程池，慢客户端不会永久占用全部主处理线程。
        this.bodyReaders = boundedExecutor(4, 8);
        this.server.setExecutor(executor);
        // 所有路径进入同一固定路由器，避免宽泛动态 handler。
        this.server.createContext("/", this::handle);
    }

    public void start() {
        // 启动动作只由固定 main 或容器测试入口调用。
        server.start();
    }

    public int port() {
        // 端口 0 自检模式读取内核分配的实际 loopback 端口。
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        // 立即停止接收新请求，容器负责最终进程资源回收。
        server.stop(0);
        // 显式关闭自建线程池，避免容器自检完成后线程泄漏。
        executor.shutdownNow();
        // 请求体读取线程池与主处理池分别回收。
        bodyReaders.shutdownNow();
        // 会话仅保存在当前进程内，关闭时主动清空。
        sessions.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                // 固定路由只按 method 和规范化 path 精确匹配。
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                if ("GET".equals(method) && "/login".equals(path)) { loginForm(exchange); return; }
                if ("POST".equals(method) && "/login".equals(path)) { login(exchange); return; }
                if ("GET".equals(method) && "/api/session".equals(path)) { session(exchange); return; }
                if ("GET".equals(method) && "/api/features".equals(path)) { features(exchange); return; }
                if ("GET".equals(method) && "/health".equals(path)) {
                    sendJson(
                            exchange,
                            healthFault ? 503 : 200,
                            Map.of("status", healthFault ? "DOWN" : "UP"));
                    return;
                }
                if ("PUT".equals(method) && path.startsWith("/api/features/")) { update(exchange, featureId(path, "/api/features/")); return; }
                if ("POST".equals(method) && path.startsWith("/api/features/") && path.endsWith("/preview")) {
                    String id = featureId(path.substring(0, path.length() - "/preview".length()), "/api/features/");
                    preview(exchange, id);
                    return;
                }
                if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path) || "/styles.css".equals(path) || "/app.js".equals(path))) {
                    staticFile(exchange, "/".equals(path) ? "index.html" : path.substring(1));
                    return;
                }
                // 任何未声明 method/path 都返回固定 404。
                sendJson(exchange, 404, Map.of("error", "NOT_FOUND"));
            } catch (FeatureStore.UnknownFeatureException exception) {
                sendJson(exchange, 404, Map.of("error", "FEATURE_NOT_FOUND"));
            } catch (FeatureStore.RevisionConflictException exception) {
                sendJson(exchange, 409, Map.of("error", "REVISION_CONFLICT"));
            } catch (BodyTooLargeException exception) {
                sendJson(exchange, 413, Map.of("error", "BODY_TOO_LARGE"));
            } catch (RequestBodyBusyException exception) {
                sendJson(exchange, 503, Map.of("error", "SERVER_BUSY"));
            } catch (RequestBodyDeadlineException exception) {
                // 超时路径已主动关闭 exchange，不能再次写响应。
                return;
            } catch (InvalidVersionException exception) {
                sendJson(exchange, 400, Map.of("error", "INVALID_VERSION"));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, Map.of("error", "INVALID_REQUEST"));
            }
        }
    }

    private void loginForm(HttpExchange exchange) throws IOException {
        // 登录页只包含固定表单，不把 token 放入 URL、HTML 或脚本。
        byte[] body =
                ("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                                + "<title>本地预览登录</title><form method=\"post\" action=\"/login\">"
                                + "<label>一次性令牌<input name=\"token\" type=\"password\" autocomplete=\"off\"></label>"
                                + "<button type=\"submit\">登录</button></form></html>")
                        .getBytes(StandardCharsets.UTF_8);
        send(exchange, 200, "text/html; charset=utf-8", body);
    }

    private void login(HttpExchange exchange) throws IOException {
        // 表单只接受精确主类型并复用 16 KiB/5 秒有界读取。
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !"application/x-www-form-urlencoded"
                        .equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            throw new IllegalArgumentException("login content type invalid");
        }
        String body =
                new String(
                        readRequestBody(exchange, MAX_REQUEST_BYTES),
                        StandardCharsets.UTF_8);
        String supplied = parseFormToken(body);
        // 单个 loopback 地址在滚动窗口内达到八次失败后固定返回 429。
        if (loginRateLimited()) {
            sendJson(exchange, 429, Map.of("error", "LOGIN_RATE_LIMITED"));
            return;
        }
        String suppliedHash = sha256(supplied);
        String tokenId = matchingTokenId(suppliedHash);
        // 未匹配或已消费 token 都返回同一稳定拒绝，避免枚举 tokenId。
        if (tokenId == null) {
            recordLoginFailure();
            sendJson(exchange, 401, Map.of("error", "LOGIN_REJECTED"));
            return;
        }
        String sessionId;
        String csrfToken;
        synchronized (loginLock) {
            // session 容量在消费一次性 token 前检查，容量失败不损失凭据。
            if (sessions.size() >= MAX_SESSIONS) {
                sendJson(exchange, 503, Map.of("error", "SESSION_CAPACITY"));
                return;
            }
            // 已消费 token 与未知 token 采用同一登录失败响应。
            if (!consumeToken(tokenId)) {
                recordLoginFailure();
                sendJson(exchange, 401, Map.of("error", "LOGIN_REJECTED"));
                return;
            }
            // 每个成功 token 产生独立随机 session 与 CSRF，不复用 token 值。
            sessionId = randomToken();
            csrfToken = randomToken();
            sessions.put(sessionId, csrfToken);
        }
        // Cookie 阻止脚本读取和跨站发送；loopback HTTP 不虚假设置 Secure。
        exchange.getResponseHeaders()
                .add(
                        "Set-Cookie",
                        "AGENT_SESSION="
                                + sessionId
                                + "; Path=/; HttpOnly; SameSite=Strict");
        // 登录成功只重定向到固定相对路径，token 不进入 Location。
        exchange.getResponseHeaders().set("Location", "/");
        send(exchange, 303, "text/plain; charset=utf-8", new byte[0]);
    }

    private void session(HttpExchange exchange) throws IOException {
        // 只有当前进程签发的 cookie 能读取对应 CSRF。
        String csrf = sessionCsrf(exchange);
        if (csrf == null) {
            sendJson(exchange, 401, Map.of("error", "SESSION_REQUIRED"));
            return;
        }
        sendJson(exchange, 200, Map.of("csrfToken", csrf));
    }

    private void features(HttpExchange exchange) throws IOException {
        // feature 列表包含配置，只有已认证 session 可以读取。
        if (sessionCsrf(exchange) == null) {
            sendJson(exchange, 401, Map.of("error", "SESSION_REQUIRED"));
            return;
        }
        // 响应始终保持固定 features 数组形状。
        sendJson(exchange, 200, Map.of("features", store.list()));
    }

    private void update(HttpExchange exchange, String id) throws IOException {
        // PUT 同时验证可信 session、CSRF 与精确 Origin。
        if (!authorizeMutation(exchange)) {
            return;
        }
        JsonNode body = readJson(exchange);
        // DTO 字段按白名单逐项读取，不进行请求对象全量绑定。
        boolean enabled = requiredBoolean(body, "enabled");
        String minVersion = requiredText(body, "minVersion");
        long expectedRevision = requiredLong(body, "expectedRevision");
        // 版本语法先固定校验，再进入 store。
        validateVersion(minVersion);
        FeatureStore.Feature updated = store.update(id, enabled, minVersion, expectedRevision);
        sendJson(exchange, 200, updated);
    }

    private void preview(HttpExchange exchange, String id) throws IOException {
        // POST 使用与 PUT 相同的三重状态变更防护。
        if (!authorizeMutation(exchange)) {
            return;
        }
        JsonNode body = readJson(exchange);
        String clientVersion = requiredText(body, "clientVersion");
        validateVersion(clientVersion);
        FeatureStore.Feature feature = store.get(id);
        // disabled 规则优先，不执行生成策略。
        boolean effective =
                feature.enabled()
                        && versionPolicy.isAtLeast(clientVersion, feature.minVersion());
        // reasonCode 与固定业务规则一一对应，模型不能自行命名。
        String reasonCode =
                !feature.enabled()
                        ? "FEATURE_DISABLED"
                        : effective ? "VERSION_ALLOWED" : "VERSION_TOO_LOW";
        // 响应只暴露冻结的四个字段。
        sendJson(
                exchange,
                200,
                Map.of(
                        "featureId", id,
                        "effective", effective,
                        "reasonCode", reasonCode,
                        "configRevision", feature.configRevision()));
    }

    private boolean authorizeMutation(HttpExchange exchange) throws IOException {
        // Cookie 必须精确包含服务端签发的 session 值。
        String expectedCsrf = sessionCsrf(exchange);
        if (expectedCsrf == null) {
            sendJson(exchange, 401, Map.of("error", "SESSION_REQUIRED"));
            return false;
        }
        // CSRF token 使用常量时间比较。
        String csrf = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
        if (csrf == null
                || !java.security.MessageDigest.isEqual(
                        csrf.getBytes(StandardCharsets.UTF_8),
                        expectedCsrf.getBytes(StandardCharsets.UTF_8))) {
            sendJson(exchange, 403, Map.of("error", "CSRF_REJECTED"));
            return false;
        }
        // Origin 只接受启动时的精确值，不反射任意 Origin。
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
        if (!origin.equals(requestOrigin)) {
            sendJson(exchange, 403, Map.of("error", "ORIGIN_REJECTED"));
            return false;
        }
        return true;
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        // JSON endpoint 只接受精确 application/json 主类型。
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            throw new IllegalArgumentException("content type invalid");
        }
        byte[] body = readRequestBody(exchange, MAX_REQUEST_BYTES);
        try {
            JsonNode value = JSON.readTree(body);
            if (!value.isObject()) {
                throw new IllegalArgumentException("JSON object required");
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON invalid");
        }
    }

    private byte[] readRequestBody(HttpExchange exchange, int limit) throws IOException {
        Future<byte[]> read;
        try {
            // 阻塞读取只能进入独立有界 reader 池。
            read = bodyReaders.submit(() -> readBounded(exchange.getRequestBody(), limit));
        } catch (RejectedExecutionException saturated) {
            throw new RequestBodyBusyException();
        }
        try {
            // Future deadline 独立于客户端是否继续发送字节。
            return read.get(REQUEST_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            // 未发送响应前先关闭 exchange，使 JDK 直接关闭底层连接而不阻塞式 drain body。
            exchange.close();
            // 连接关闭后再关闭输入流，确保 reader 立即看到 EOF/closed。
            exchange.getRequestBody().close();
            // 中断作为补充信号，不依赖 InputStream 响应中断。
            read.cancel(true);
            // 固定标记不包含 body、token 或客户端地址。
            System.err.println("REQUEST_BODY_DEADLINE");
            throw new RequestBodyDeadlineException();
        } catch (InterruptedException interrupted) {
            // 恢复中断并关闭当前交换。
            Thread.currentThread().interrupt();
            exchange.close();
            read.cancel(true);
            throw new IOException("request interrupted", interrupted);
        } catch (ExecutionException failed) {
            // 仅转发受控读取异常，不暴露请求内容。
            Throwable cause = failed.getCause();
            if (cause instanceof BodyTooLargeException tooLarge) {
                throw tooLarge;
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("request body read failed", cause);
        }
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        // 单次读取仅负责大小边界；时间边界由调用方 Future 强制执行。
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new BodyTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void staticFile(HttpExchange exchange, String name) throws IOException {
        // 静态文件名只来自固定路由映射。
        try (InputStream input =
                ConfigHttpServer.class.getResourceAsStream("/web/" + name)) {
            if (input == null) {
                sendJson(exchange, 404, Map.of("error", "STATIC_NOT_FOUND"));
                return;
            }
            byte[] content = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (content.length > MAX_RESPONSE_BYTES) {
                throw new IOException("static response too large");
            }
            String type =
                    name.endsWith(".css")
                            ? "text/css; charset=utf-8"
                            : name.endsWith(".js")
                                    ? "application/javascript; charset=utf-8"
                                    : "text/html; charset=utf-8";
            send(exchange, 200, type, content);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new IOException("JSON response too large");
        }
        send(exchange, status, "application/json; charset=utf-8", body);
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body)
            throws IOException {
        // 所有响应统一启用 MIME 嗅探防护与禁止缓存。
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (body.length == 0) {
            // JDK HttpServer 的长度 0 表示 chunked；空响应改用 -1，避免网关接受分块语义。
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String featureId(String path, String prefix) {
        String id = path.substring(prefix.length());
        // ID 白名单排除分隔符、编码混淆和控制字符。
        if (!id.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("feature id invalid");
        }
        return id;
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " invalid");
        }
        return value.asText();
    }

    private static boolean requiredBoolean(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(field + " invalid");
        }
        return value.asBoolean();
    }

    private static long requiredLong(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " invalid");
        }
        return value.longValue();
    }

    private static void validateVersion(String version) {
        // 2–4 段；零允许单独出现，非零段禁止前导零。
        if (version == null || !version.matches("(?:0|[1-9][0-9]{0,5})(?:\\.(?:0|[1-9][0-9]{0,5})){1,3}")) {
            throw new InvalidVersionException();
        }
        // 正则长度上限已确保每段数值不会超过 999999。
        for (String segment : version.split("\\.")) {
            if (Integer.parseInt(segment) > 999_999) {
                throw new InvalidVersionException();
            }
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sessionCsrf(HttpExchange exchange) {
        // Cookie parser只接受固定名称并从服务端 Map 解析身份。
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) {
            return null;
        }
        for (String member : cookie.split("; ")) {
            if (member.startsWith("AGENT_SESSION=")) {
                return sessions.get(member.substring("AGENT_SESSION=".length()));
            }
        }
        return null;
    }

    private boolean loginRateLimited() {
        synchronized (loginLock) {
            // 每次判断前移除 60 秒窗口外的失败记录。
            trimLoginFailures(System.nanoTime());
            return loginFailures.size() >= MAX_LOGIN_FAILURES;
        }
    }

    private void recordLoginFailure() {
        synchronized (loginLock) {
            // 同一 loopback 服务只暴露一个本地失败桶，避免信任伪造来源 Header。
            long now = System.nanoTime();
            trimLoginFailures(now);
            loginFailures.addLast(now);
        }
    }

    private void trimLoginFailures(long now) {
        // 只保留当前滚动窗口内的失败时间戳。
        while (!loginFailures.isEmpty()
                && now - loginFailures.peekFirst() > LOGIN_FAILURE_WINDOW.toNanos()) {
            loginFailures.removeFirst();
        }
    }

    private static ThreadPoolExecutor boundedExecutor(int workers, int queueCapacity) {
        // core=max 禁止动态扩张；有界队列和 AbortPolicy 提供固定饱和边界。
        return new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private String matchingTokenId(String suppliedHash) {
        // 对两个固定 hash 都执行常量时间比较，不按前缀或明文分支。
        String matched = null;
        for (Map.Entry<String, String> entry : tokenHashes.entrySet()) {
            boolean equal =
                    MessageDigest.isEqual(
                            suppliedHash.getBytes(StandardCharsets.US_ASCII),
                            entry.getValue().getBytes(StandardCharsets.US_ASCII));
            if (equal) {
                matched = entry.getKey();
            }
        }
        return matched;
    }

    private boolean consumeToken(String tokenId) {
        Path marker = authStateRoot.resolve(tokenId + ".consumed");
        // tokenId 来自固定配置白名单，仍限制字符以保护 leaf 名称。
        if (!tokenId.matches("[a-z0-9-]{1,32}")) {
            throw new IllegalStateException("auth token id invalid");
        }
        try {
            // CREATE_NEW 是消费动作的持久化原子点；已存在表示重放。
            Files.writeString(
                    marker,
                    "consumed\n",
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
            return true;
        } catch (java.nio.file.FileAlreadyExistsException replay) {
            return false;
        } catch (IOException exception) {
            throw new IllegalStateException("auth token state cannot be persisted", exception);
        }
    }

    private static String parseFormToken(String body) {
        // 表单必须只含一个 token 字段，禁止重复键优先级歧义。
        String[] members = body.split("&", -1);
        if (members.length != 1 || !members[0].startsWith("token=")) {
            throw new IllegalArgumentException("login form invalid");
        }
        try {
            String value =
                    java.net.URLDecoder.decode(
                            members[0].substring("token=".length()), StandardCharsets.UTF_8);
            if (value.length() < 32 || value.length() > 128) {
                throw new IllegalArgumentException("login token invalid");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("login form invalid");
        }
    }

    private static String sha256(String value) {
        try {
            // 只在内存中计算明文 token 的 SHA-256，结果用于固定 config 匹配。
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static Map<String, String> readAuthConfig(Path config) throws IOException {
        // 只读 config 必须是未跟随链接的普通小文件。
        if (config == null
                || Files.isSymbolicLink(config)
                || !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)
                || Files.size(config) > 4_096) {
            throw new IllegalArgumentException("auth config invalid");
        }
        JsonNode root = JSON.readTree(config.toFile());
        JsonNode tokens = root.path("tokens");
        Map<String, String> hashes = new LinkedHashMap<>();
        // 配置必须恰好包含 smoke 与 browser-handoff 两个 tokenId。
        if (!tokens.isArray() || tokens.size() != 2) {
            throw new IllegalArgumentException("auth config tokens invalid");
        }
        for (JsonNode token : tokens) {
            String id = token.path("tokenId").asText();
            String hash = token.path("sha256").asText();
            if (!List.of("smoke", "browser-handoff").contains(id)
                    || !hash.matches("[0-9a-f]{64}")
                    || hashes.putIfAbsent(id, hash) != null) {
                throw new IllegalArgumentException("auth config token invalid");
            }
        }
        if (!hashes.keySet().equals(java.util.Set.of("smoke", "browser-handoff"))) {
            throw new IllegalArgumentException("auth config token ids invalid");
        }
        return Map.copyOf(hashes);
    }

    private static Path secureAuthStateRoot(Path dataRoot) throws IOException {
        // dataRoot 由 FeatureStore 完成真实目录校验；auth-state 仍独立拒绝链接。
        Path requested = dataRoot.toAbsolutePath().normalize().resolve("auth-state");
        if (Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(requested)
                    || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)
                    || !requested.toRealPath().equals(requested)) {
                throw new IllegalArgumentException("auth state root invalid");
            }
            return requested;
        }
        return Files.createDirectory(requested).toRealPath();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        int port = Integer.parseInt(options.get("port"));
        String origin = options.get("origin");
        Path authConfig = Path.of(options.get("auth-config"));
        Path dataRoot = Path.of(options.get("data-root"));
        boolean healthFault = Boolean.parseBoolean(options.get("health-fail"));
        try (ConfigHttpServer app =
                new ConfigHttpServer(
                        port,
                        origin,
                        authConfig,
                        dataRoot,
                        new GeneratedVersionPolicy(),
                        healthFault)) {
            app.start();
            // 容器以进程生命周期承载服务，不读取 stdin 命令。
            Thread.currentThread().join();
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (index + 1 >= args.length || !args[index].startsWith("--")) {
                throw new IllegalArgumentException("invalid fixed server arguments");
            }
            String key = args[index].substring(2);
            if (!List.of("port", "origin", "auth-config", "data-root", "health-fail")
                    .contains(key)) {
                throw new IllegalArgumentException("unknown server argument");
            }
            options.put(key, args[index + 1]);
        }
        if (!options.keySet()
                .equals(
                        java.util.Set.of(
                                "port",
                                "origin",
                                "auth-config",
                                "data-root",
                                "health-fail"))) {
            throw new IllegalArgumentException("server arguments missing");
        }
        return options;
    }

    private static final class BodyTooLargeException extends IOException {}

    private static final class RequestBodyBusyException extends IOException {}

    private static final class RequestBodyDeadlineException extends IOException {}

    private static final class InvalidVersionException extends IllegalArgumentException {}
}
