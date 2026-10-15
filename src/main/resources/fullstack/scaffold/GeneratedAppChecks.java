package fullstack.generated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** 仅在隔离测试容器中运行的固定黑盒验收入口。 */
public final class GeneratedAppChecks {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        // self-test 在同一隔离容器内启动 loopback server，不依赖 shell 或第二进程。
        if (args.length == 7 && "self-test".equals(args[0])) {
            Path smokeTokenFile = Path.of(args[1]);
            Path handoffTokenFile = Path.of(args[2]);
            Path authConfig = Path.of(args[3]);
            // auth 与 feature 状态位于同一个专用可写 data mount。
            Path dataRoot = authConfig.getParent();
            boolean expected = Boolean.parseBoolean(args[4]);
            String origin = "http://127.0.0.1:18080";
            try (ConfigHttpServer app =
                    new ConfigHttpServer(
                            0, origin, authConfig, dataRoot, new GeneratedVersionPolicy())) {
                app.start();
                URI base = URI.create("http://127.0.0.1:" + app.port());
                runChecks(base, origin, smokeTokenFile, expected);
            }
            // 停止并以同一 candidate/data 重启，验证持久化配置可读且 revision 不回退。
            try (ConfigHttpServer restarted =
                    new ConfigHttpServer(
                            0, origin, authConfig, dataRoot, new GeneratedVersionPolicy())) {
                restarted.start();
                URI base = URI.create("http://127.0.0.1:" + restarted.port());
                runRestartChecks(
                        base, origin, smokeTokenFile, handoffTokenFile, expected);
            }
            // 固定负例验证损坏、链接和原子替换失败都不会被静默接受。
            runStoreFailureChecks(dataRoot);
            requireReadOnly(Path.of(args[5]), "READ_ONLY_BASELINE_PASS");
            requireReadOnly(Path.of(args[6]), "READ_ONLY_CANDIDATE_PASS");
            System.out.println("SELF_TEST_PASS:" + expected);
            return;
        }
        // 固定参数分别为 loopback base、受信 Origin 和只读 login token 文件。
        URI base = URI.create(args[0]);
        String origin = args[1];
        runChecks(base, origin, Path.of(args[2]), Boolean.parseBoolean(args[3]));
    }

    private static void runChecks(URI base, String origin, Path tokenFile, boolean expected)
            throws Exception {
        String token = Files.readString(tokenFile).strip();
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();

        // smoke-login：首次表单登录必须得到 HttpOnly/SameSite cookie 和 CSRF。
        AuthSession authenticated = login(client, base, token, "smoke-login");
        String sessionCookie = authenticated.cookie();
        String csrf = authenticated.csrf();

        // one-time-smoke：同一 token 第二次使用必须被拒绝。
        requireStatus("one-time-smoke", loginResponse(client, base, token), 401);

        String update =
                "{\"enabled\":true,\"minVersion\":\"2.9\",\"expectedRevision\":7}";
        // feature 列表也包含受保护配置，匿名读取必须失败。
        requireStatus(
                "features-session-required",
                send(client, request(base.resolve("/api/features")).GET().build()),
                401);
        // missing-session：状态变更缺 session 必须失败。
        requireStatus(
                "missing-session",
                send(client, mutation(base, origin, null, csrf, update, "PUT")),
                401);
        // bad-csrf：错误 CSRF 必须失败。
        requireStatus(
                "bad-csrf",
                send(client, mutation(base, origin, sessionCookie, "bad", update, "PUT")),
                403);
        // bad-origin：非精确 Origin 必须失败。
        requireStatus(
                "bad-origin",
                send(client, mutation(base, origin + ".invalid", sessionCookie, csrf, update, "PUT")),
                403);
        // 合法更新写入版本 2.10 并递增 revision。
        requireStatus(
                "valid-update",
                send(client, mutation(base, origin, sessionCookie, csrf, update, "PUT")),
                200);
        // revision-conflict：旧 revision 不能覆盖新状态。
        requireStatus(
                "revision-conflict",
                send(client, mutation(base, origin, sessionCookie, csrf, update, "PUT")),
                409);
        // body-too-large：超过 16KiB 的 JSON 必须在解析前拒绝。
        String tooLarge = "{\"enabled\":true,\"minVersion\":\"2.9\",\"expectedRevision\":8,\"pad\":\""
                + "x".repeat(17_000)
                + "\"}";
        requireStatus(
                "body-too-large",
                send(client, mutation(base, origin, sessionCookie, csrf, tooLarge, "PUT")),
                413);

        // 2.10 与 2.9 进入真实 GeneratedVersionPolicy；故障版应失败、修复版应通过。
        String preview = "{\"clientVersion\":\"2.10\"}";
        HttpResponse<String> previewResponse =
                send(client, mutation(base, origin, sessionCookie, csrf, preview, "POST"));
        requireStatus("version-preview", previewResponse, 200);
        JsonNode previewJson = JSON.readTree(previewResponse.body());
        assertPreviewShape(previewJson, expected, expected ? "VERSION_ALLOWED" : "VERSION_TOO_LOW", 8);
        // 无效版本必须返回专用错误，不得退化为通用请求错误。
        HttpResponse<String> invalidVersion =
                send(
                        client,
                        mutation(
                                base,
                                origin,
                                sessionCookie,
                                csrf,
                                "{\"clientVersion\":\"02.10\"}",
                                "POST"));
        requireStatus("invalid-version", invalidVersion, 400);
        if (!"INVALID_VERSION".equals(JSON.readTree(invalidVersion.body()).path("error").asText())) {
            throw new IllegalStateException("invalid version error mismatch");
        }
        // disabled 规则优先于版本比较并产生固定 reasonCode。
        String disable =
                "{\"enabled\":false,\"minVersion\":\"2.9\",\"expectedRevision\":8}";
        requireStatus(
                "disable-feature",
                send(client, mutation(base, origin, sessionCookie, csrf, disable, "PUT")),
                200);
        JsonNode disabledPreview =
                JSON.readTree(
                        send(client, mutation(base, origin, sessionCookie, csrf, preview, "POST"))
                                .body());
        assertPreviewShape(disabledPreview, false, "FEATURE_DISABLED", 9);
        // 自检结束前恢复 enabled=true，供重启持久化路径再次验证生成策略。
        String enable =
                "{\"enabled\":true,\"minVersion\":\"2.9\",\"expectedRevision\":9}";
        requireStatus(
                "enable-feature",
                send(client, mutation(base, origin, sessionCookie, csrf, enable, "PUT")),
                200);
        if (previewJson.path("effective").asBoolean() != expected) {
            throw new IllegalStateException("2.10 versus 2.9 result mismatch");
        }

        // 慢客户端在约五秒被关闭，随后健康请求仍可处理。
        runSlowClient(base, origin, sessionCookie, csrf);
        requireStatus(
                "health-after-slow-client",
                send(client, request(base.resolve("/health")).GET().build()),
                200);

        // static-page：页面必须可读且不包含危险 innerHTML。
        HttpResponse<String> page =
                send(client, request(base.resolve("/index.html")).GET().build());
        requireStatus("static-page", page, 200);
        if (page.body().contains("innerHTML")) {
            throw new IllegalStateException("static page contains innerHTML");
        }
    }

    private static void runRestartChecks(
            URI base,
            String origin,
            Path smokeTokenFile,
            Path handoffTokenFile,
            boolean expected)
            throws Exception {
        // 重启后 browser-handoff 仍未被 smoke 消费，可建立独立 session。
        String smokeToken = Files.readString(smokeTokenFile).strip();
        String handoffToken = Files.readString(handoffTokenFile).strip();
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        AuthSession browser = login(client, base, handoffToken, "browser-handoff-login");
        String cookie = browser.cookie();
        String csrf = browser.csrf();
        // 两个消费标记跨重启保留，smoke 与 handoff 重放都必须失败。
        requireStatus("smoke-replay-after-restart", loginResponse(client, base, smokeToken), 401);
        requireStatus("handoff-replay", loginResponse(client, base, handoffToken), 401);
        // GET 必须读回持久化字段和值，而不是重新初始化默认 revision 7。
        HttpResponse<String> get =
                send(
                        client,
                        request(base.resolve("/api/features"))
                                .header("Cookie", cookie)
                                .GET()
                                .build());
        requireStatus("restart-get", get, 200);
        JsonNode feature = JSON.readTree(get.body()).path("features").path(0);
        if (!"homepage_recommendation".equals(feature.path("featureId").asText())
                || !feature.path("enabled").asBoolean(false)
                || !"2.9".equals(feature.path("minVersion").asText())
                || feature.path("configRevision").asLong() != 10) {
            throw new IllegalStateException("persisted feature mismatch");
        }
        // 重启后的 preview 必须读取同一持久化 minVersion/revision。
        String preview = "{\"clientVersion\":\"2.10\"}";
        HttpResponse<String> response =
                send(client, mutation(base, origin, cookie, csrf, preview, "POST"));
        requireStatus("restart-preview", response, 200);
        JsonNode previewJson = JSON.readTree(response.body());
        assertPreviewShape(
                previewJson,
                expected,
                expected ? "VERSION_ALLOWED" : "VERSION_TOO_LOW",
                10);
        // 两次重放计入失败窗口，再补六次失败，第九次稳定返回 429。
        for (int attempt = 0; attempt < 6; attempt++) {
            requireStatus(
                    "login-failure-" + attempt,
                    loginResponse(client, base, "x".repeat(40) + attempt),
                    401);
        }
        HttpResponse<String> limited = loginResponse(client, base, "y".repeat(40));
        requireStatus("login-rate-limit", limited, 429);
        if (limited.body().contains("smoke") || limited.body().contains("browser-handoff")) {
            throw new IllegalStateException("login response disclosed token id");
        }
        System.out.println("PERSISTENCE_RESTART_PASS");
        System.out.println("DUAL_TOKEN_RESTART_PASS");
        System.out.println("LOGIN_RATE_LIMIT_PASS");
    }

    private static AuthSession login(
            HttpClient client, URI base, String token, String caseName) throws Exception {
        HttpResponse<String> response = loginResponse(client, base, token);
        requireStatus(caseName, response, 303);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        if (!cookie.contains("HttpOnly") || !cookie.contains("SameSite=Strict")) {
            throw new IllegalStateException("login cookie flags missing");
        }
        String sessionCookie = cookie.split(";", 2)[0];
        HttpResponse<String> session =
                send(
                        client,
                        request(base.resolve("/api/session"))
                                .header("Cookie", sessionCookie)
                                .GET()
                                .build());
        requireStatus(caseName + "-session", session, 200);
        String csrf = JSON.readTree(session.body()).path("csrfToken").asText();
        return new AuthSession(sessionCookie, csrf);
    }

    private static HttpResponse<String> loginResponse(
            HttpClient client, URI base, String token) throws Exception {
        // token 只进入 POST form body，不进入 URL、header 或日志。
        String body =
                "token="
                        + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8);
        return send(
                client,
                request(base.resolve("/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build());
    }

    private static void runSlowClient(
            URI base, String origin, String cookie, String csrf) throws Exception {
        long started = System.nanoTime();
        try (Socket socket = new Socket("127.0.0.1", base.getPort())) {
            // 测试最多等待八秒，避免失败时挂住容器。
            socket.setSoTimeout(8_000);
            String headers =
                    "PUT /api/features/homepage_recommendation HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: 100\r\n"
                            + "Origin: "
                            + origin
                            + "\r\nX-CSRF-Token: "
                            + csrf
                            + "\r\nCookie: "
                            + cookie
                            + "\r\nConnection: close\r\n\r\n{";
            // 只发送一个不完整 body，触发独立 reader deadline。
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            // 服务端超时会关闭连接；读取到 EOF 即证明资源被主动释放。
            while (socket.getInputStream().read() >= 0) {
                // 响应内容不参与判定。
            }
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        if (elapsedMillis < 4_500 || elapsedMillis > 7_500) {
            throw new IllegalStateException("slow client deadline mismatch");
        }
        System.out.println("SLOW_CLIENT_DEADLINE_PASS");
    }

    private static void assertPreviewShape(
            JsonNode value, boolean effective, String reasonCode, long revision) {
        // preview 响应只允许冻结的四个字段。
        java.util.Set<String> fields = new java.util.HashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(
                        java.util.Set.of(
                                "featureId", "effective", "reasonCode", "configRevision"))
                || !"homepage_recommendation".equals(value.path("featureId").asText())
                || value.path("effective").asBoolean() != effective
                || !reasonCode.equals(value.path("reasonCode").asText())
                || value.path("configRevision").asLong() != revision) {
            throw new IllegalStateException("preview contract mismatch");
        }
    }

    private static void runStoreFailureChecks(Path dataRoot) throws Exception {
        // 损坏 JSON 必须拒绝，不能回退并覆盖损坏现场。
        Path corrupt = Files.createDirectory(dataRoot.resolve("corrupt-state"));
        Files.writeString(corrupt.resolve("feature-config.json"), "{broken");
        expectRejected(() -> new FeatureStore(corrupt), "corrupt-state");

        // 预置 leaf 链接必须拒绝且外部目标字节保持不变。
        Path linked = Files.createDirectory(dataRoot.resolve("linked-state"));
        Path outside = Files.createTempFile("outside-feature-", ".json");
        Files.writeString(outside, "outside-unchanged");
        Files.createSymbolicLink(linked.resolve("feature-config.json"), outside);
        expectRejected(() -> new FeatureStore(linked), "linked-state");
        if (!"outside-unchanged".equals(Files.readString(outside))) {
            throw new IllegalStateException("linked outside target changed");
        }

        // 中间目录链接同样不能成为 data root。
        Path real = Files.createDirectory(dataRoot.resolve("real-state"));
        Path linkedRoot = dataRoot.resolve("linked-root");
        Files.createSymbolicLink(linkedRoot, real);
        expectRejected(() -> new FeatureStore(linkedRoot), "linked-root");

        // 最终原子移动失败时，原状态字节和进程内 revision 都必须保持不变。
        Path failing = Files.createDirectory(dataRoot.resolve("failing-state"));
        new FeatureStore(failing);
        byte[] before = Files.readAllBytes(failing.resolve("feature-config.json"));
        FeatureStore failingStore =
                new FeatureStore(
                        failing,
                        (source, target, store) -> {
                            throw new java.io.IOException("forced move failure");
                        });
        expectRejected(
                () ->
                        failingStore.update(
                                FeatureStore.FEATURE_ID, false, "2.9", 7),
                "atomic-write-failure");
        if (!java.util.Arrays.equals(
                        before, Files.readAllBytes(failing.resolve("feature-config.json")))
                || failingStore.get(FeatureStore.FEATURE_ID).configRevision() != 7) {
            throw new IllegalStateException("failed atomic write changed state");
        }

        // 平台不支持原子移动时同样必须失败关闭，不能降级为普通覆盖。
        Path unsupported = Files.createDirectory(dataRoot.resolve("atomic-unsupported"));
        new FeatureStore(unsupported);
        byte[] unsupportedBefore =
                Files.readAllBytes(unsupported.resolve("feature-config.json"));
        FeatureStore unsupportedStore =
                new FeatureStore(
                        unsupported,
                        (source, target, store) -> {
                            throw new java.nio.file.AtomicMoveNotSupportedException(
                                    source.toString(), target.toString(), "forced unsupported");
                        });
        expectRejected(
                () ->
                        unsupportedStore.update(
                                FeatureStore.FEATURE_ID, false, "2.9", 7),
                "atomic-move-unsupported");
        FeatureStore externalReader = new FeatureStore(unsupported);
        if (!java.util.Arrays.equals(
                        unsupportedBefore,
                        Files.readAllBytes(unsupported.resolve("feature-config.json")))
                || unsupportedStore.get(FeatureStore.FEATURE_ID).configRevision() != 7
                || externalReader.get(FeatureStore.FEATURE_ID).configRevision() != 7) {
            throw new IllegalStateException("unsupported atomic move changed state");
        }
        System.out.println("STORE_FAILURES_REJECTED");
    }

    private static void expectRejected(ThrowingAction action, String caseName) throws Exception {
        boolean rejected = false;
        try {
            // 负例只有抛出稳定运行时错误才算拒绝。
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            // 固定负例不输出路径或损坏内容。
            rejected = true;
        }
        if (!rejected) {
            throw new IllegalStateException(caseName + " was accepted");
        }
    }

    private static void requireReadOnly(Path path, String marker) throws Exception {
        // 写入前保存完整 hash 等价字节快照。
        byte[] before = Files.readAllBytes(path);
        try {
            // 测试容器主动尝试修改只读验收基线或候选自身。
            Files.writeString(path, "tamper");
            throw new IllegalStateException("read-only mount accepted write");
        } catch (java.nio.file.FileSystemException expected) {
            // 只读 bind mount 必须拒绝修改。
        }
        byte[] after = Files.readAllBytes(path);
        if (!java.util.Arrays.equals(before, after)) {
            throw new IllegalStateException("read-only artifact changed");
        }
        System.out.println(marker);
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
    }

    private static HttpRequest mutation(
            URI base,
            String origin,
            String cookie,
            String csrf,
            String body,
            String method) {
        String suffix = "POST".equals(method) ? "/preview" : "";
        HttpRequest.Builder builder =
                request(base.resolve("/api/features/homepage_recommendation" + suffix))
                        .header("Content-Type", "application/json")
                        .header("Origin", origin)
                        .header("X-CSRF-Token", csrf);
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request)
            throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void requireStatus(
            String caseName, HttpResponse<String> response, int expected) {
        if (response.statusCode() != expected) {
            throw new IllegalStateException(caseName + " status mismatch");
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        // 固定负例可以执行会抛出受检异常的最小动作。
        void run() throws Exception;
    }

    private record AuthSession(String cookie, String csrf) {}
}
