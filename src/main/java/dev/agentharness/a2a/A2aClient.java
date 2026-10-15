package dev.agentharness.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** 固定环回目标、禁重定向且有超时的 A2A 教学客户端。 */
public final class A2aClient {
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private static final Duration RESPONSE_DEADLINE = Duration.ofSeconds(3);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final ObjectMapper JSON =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final ScheduledExecutorService RESPONSE_DEADLINES =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        // 守护线程只负责关闭超过整体截止时间的响应流。
                        Thread thread = new Thread(runnable, "a2a-client-response-deadline");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public AgentCard card(URI base) {
        // Card URL 只能从已校验环回基址构造。
        HttpRequest request =
                request(base, "/.well-known/agent-card.json")
                        .GET()
                        .build();
        // 读取并严格验证本子集所需 Card 字段。
        AgentCard card = parse(send(request, 200), AgentCard.class);
        // 返回已通过版本与接口校验的 Card。
        return validateCard(card);
    }

    public A2aResponse send(URI base, A2aMessage message) {
        // 成功便捷入口复用可读取错误响应的底层调用。
        A2aResponse response = sendForResponse(base, message);
        if (response.error() != null) {
            throw new IllegalStateException(response.error().code() + ": " + response.error().message());
        }
        // 返回 Message 或 Task 联合结果。
        return response;
    }

    public A2aResponse sendForResponse(URI base, A2aMessage message) {
        try {
            // 使用 Jackson 序列化固定消息类型。
            byte[] body = JSON.writeValueAsBytes(message);
            // 构造固定路径、版本与媒体类型请求。
            HttpRequest request =
                    request(base, "/message:send")
                            .header("A2A-Version", "1.0")
                            .header("Content-Type", A2aServer.MEDIA_TYPE)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build();
            // 错误状态也解析稳定 A2A 错误体供调用方判断。
            A2aResponse response = parse(sendAnyStatus(request), A2aResponse.class);
            // 联合类型和所有请求关联 ID 必须在返回调用方前完成校验。
            return validateResponse(response, message);
        } catch (JsonProcessingException e) {
            // 固定消息无法序列化属于本地编程错误。
            throw new IllegalStateException("无法序列化 A2A 消息", e);
        }
    }

    public A2aTask getTask(
            URI base, String taskId, String expectedContextId, String expectedBusinessTaskId) {
        // GET Task 的三个关联 ID 都必须在请求前通过同一有限白名单。
        if (!validId(taskId)
                || !validId(expectedContextId)
                || !validId(expectedBusinessTaskId)) {
            throw new IllegalArgumentException("invalid task/context/business id");
        }
        // 取得 Task 后同时校验远端 ID、上下文和业务任务映射。
        return getTaskInternal(base, taskId, expectedContextId, expectedBusinessTaskId);
    }

    private A2aTask getTaskInternal(
            URI base, String taskId, String expectedContextId, String expectedBusinessTaskId) {
        // 内部协调入口至少要求受限 taskId，避免把任意路径交给 HTTP 客户端。
        if (!validId(taskId)) {
            throw new IllegalArgumentException("invalid taskId");
        }
        // GET Task 只携带版本与 Accept，不触发 message/send。
        HttpRequest request =
                request(base, "/tasks/" + taskId)
                        .header("A2A-Version", "1.0")
                        .GET()
                        .build();
        // 成功响应必须包含完整 Task。
        A2aResponse response = parse(send(request, 200), A2aResponse.class);
        validateResponse(response, null);
        if (response.task() == null) {
            throw new IllegalStateException("GET Task returned a non-Task response");
        }
        // 路径中的 taskId 必须与响应 Task.id 精确一致，阻止服务替换远端任务。
        if (!taskId.equals(response.task().id())) {
            throw new IllegalStateException("GET Task response id differs from request");
        }
        // 调用方提供映射时同时复核 contextId 和业务任务 ID。
        if (expectedContextId != null
                && (!expectedContextId.equals(response.task().contextId())
                        || !expectedBusinessTaskId.equals(response.task().businessTaskId()))) {
            throw new IllegalStateException("GET Task mapping differs from request");
        }
        // 返回验证后的远端任务。
        return response.task();
    }

    public Reconciliation reconcile(URI base, String knownTaskId) {
        try {
            // 已知 taskId 时只执行 GET Task，绝不自动重发 Message。
            A2aTask task = getTaskInternal(base, knownTaskId, null, null);
            // 找到远端 Task 后返回其当前状态。
            return new Reconciliation(task.status(), false, task);
        } catch (RemoteStatusException e) {
            // 404 表示结果未知，需要调用方人工协调。
            if (e.statusCode == 404) {
                return new Reconciliation("UNKNOWN_RECONCILIATION_REQUIRED", false, null);
            }
            // 其他协议失败继续暴露给调用方。
            throw e;
        }
    }

    private HttpRequest.Builder request(URI base, String path) {
        // 先限制基址，禁止任意 URL、代理转发和 SSRF。
        URI safeBase = validateBase(base);
        // 固定路径与已校验基址组合，不接收用户路径。
        URI target = safeBase.resolve(path);
        // 每个请求都有整体响应超时。
        return HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(3))
                .header("Accept", A2aServer.MEDIA_TYPE);
    }

    private byte[] send(HttpRequest request, int expectedStatus) {
        // 发送后先保留状态码和受限字节体。
        ExchangeResult response = exchange(request);
        if (response.statusCode() != expectedStatus) {
            throw new RemoteStatusException(response.statusCode());
        }
        // 返回受限响应体。
        return response.body();
    }

    private byte[] sendAnyStatus(HttpRequest request) {
        // message/send 的业务错误需要作为结构化响应返回。
        ExchangeResult response = exchange(request);
        // 返回结构化响应体供联合类型解析。
        return response.body();
    }

    private ExchangeResult exchange(HttpRequest request) {
        // 单调截止时间同时覆盖响应头等待与响应体流式读取。
        long deadlineNanos = System.nanoTime() + RESPONSE_DEADLINE.toNanos();
        try {
            // 先取得响应头和可关闭流，不让 JDK 在校验前无界聚合响应体。
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // 禁止将重定向当作成功或自动跟随。
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                response.body().close();
                throw new IllegalStateException("A2A redirect rejected");
            }
            // 在读取 JSON 前严格校验响应媒体类型。
            validateResponseMediaType(response.headers());
            // 分块读取并在 64 KiB 或整体截止时间处主动关闭流。
            byte[] body = readLimited(response.body(), deadlineNanos);
            // 返回只包含已验证有限字节的不可变交换结果。
            return new ExchangeResult(response.statusCode(), response.headers(), body);
        } catch (InterruptedException e) {
            // 恢复中断标志并停止网络等待。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A2A request interrupted", e);
        } catch (IOException e) {
            // 网络失败不触发任何自动重发。
            throw new IllegalStateException("A2A request failed without retry", e);
        }
    }

    private static byte[] readLimited(InputStream input, long deadlineNanos) {
        // 截止时间已经耗尽时立即关闭响应流。
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            closeQuietly(input);
            throw new IllegalStateException("A2A response deadline exceeded");
        }
        // 原子标记用于区分远端断流和本地截止时间关闭。
        AtomicBoolean timedOut = new AtomicBoolean();
        // 到期任务会主动关闭 InputStream 解除阻塞读取。
        ScheduledFuture<?> deadline =
                RESPONSE_DEADLINES.schedule(
                        () -> {
                            timedOut.set(true);
                            closeQuietly(input);
                        },
                        remainingNanos,
                        TimeUnit.NANOSECONDS);
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            // 逐块累计响应字节，超限时立即关闭并失败。
            byte[] chunk = new byte[8_192];
            int total = 0;
            int read;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    closeQuietly(input);
                    throw new IllegalStateException("A2A response too large");
                }
                output.write(chunk, 0, read);
            }
            // 截止任务触发后即使 read 返回 EOF 也必须报告超时。
            if (timedOut.get()) {
                throw new IllegalStateException("A2A response deadline exceeded");
            }
            // 返回完整且受限的响应体。
            return output.toByteArray();
        } catch (IOException e) {
            // 截止关闭产生的异常保留明确超时语义。
            if (timedOut.get()) {
                throw new IllegalStateException("A2A response deadline exceeded", e);
            }
            throw new IllegalStateException("A2A response read failed", e);
        } finally {
            // 正常完成或提前失败时取消尚未触发的关闭任务。
            deadline.cancel(false);
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            // 关闭响应流会取消底层 HTTP body 订阅并释放连接资源。
            input.close();
        } catch (IOException ignored) {
            // 原始大小或超时错误优先于关闭异常。
        }
    }

    private static URI validateBase(URI base) {
        // 仅允许精确 IPv4 环回 HTTP 基址和显式端口。
        if (base == null
                || !"http".equals(base.getScheme())
                || !"127.0.0.1".equals(base.getHost())
                || base.getPort() < 1
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null
                || !(base.getPath().isEmpty() || "/".equals(base.getPath()))) {
            throw new IllegalArgumentException("base URL must be http://127.0.0.1:<port>");
        }
        // 规范化为带末尾斜杠的安全基址。
        return URI.create("http://127.0.0.1:" + base.getPort() + "/");
    }

    private static void validateResponseMediaType(HttpHeaders headers) {
        // 剥离参数后必须精确匹配 A2A JSON，拒绝恶意后缀。
        String contentType = headers.firstValue("Content-Type").orElse("");
        if (!A2aServer.matchesMediaType(contentType)) {
            throw new IllegalStateException("invalid A2A response media type");
        }
    }

    private static <T> T parse(byte[] body, Class<T> type) {
        try {
            // 仅反序列化到固定公开记录类型。
            return JSON.readValue(body, type);
        } catch (IOException e) {
            // 不合法响应不得穿过契约边界。
            throw new IllegalStateException("invalid A2A JSON response", e);
        }
    }

    static AgentCard validateCard(AgentCard card) {
        // Card 版本和两个固定 HTTP 接口都必须精确匹配。
        if (card == null
                || !"1.0".equals(card.protocolVersion())
                || card.interfaces() == null
                || !"/message:send".equals(card.interfaces().get("messageSend"))
                || !"/tasks/{id}".equals(card.interfaces().get("taskGet"))) {
            throw new IllegalStateException("invalid agent card contract");
        }
        // 返回通过契约检查的 Card。
        return card;
    }

    static void validateTask(A2aTask task) {
        // Task 本身的 ID、映射和状态字段必须完整且受限。
        if (task == null
                || !validId(task.id())
                || !validId(task.contextId())
                || !validId(task.businessTaskId())
                || task.status() == null
                || task.status().isBlank()
                || task.artifacts() == null
                || task.artifacts().isEmpty()) {
            throw new IllegalStateException("invalid Task or Artifact contract");
        }
        // 每个 Artifact 都必须有独立 ID，并显式保持相同业务任务映射。
        for (A2aArtifact artifact : task.artifacts()) {
            if (artifact == null
                    || !validId(artifact.artifactId())
                    || !task.businessTaskId().equals(artifact.businessTaskId())
                    || artifact.name() == null
                    || artifact.name().isBlank()
                    || artifact.text() == null) {
                throw new IllegalStateException("invalid Task or Artifact contract");
            }
        }
    }

    static A2aResponse validateResponse(A2aResponse response, A2aMessage request) {
        // Message、Task、Error 三个联合分支必须恰好出现一个。
        if (response == null) {
            throw new IllegalStateException("missing A2A response");
        }
        int branches =
                (response.message() == null ? 0 : 1)
                        + (response.task() == null ? 0 : 1)
                        + (response.error() == null ? 0 : 1);
        if (branches != 1) {
            throw new IllegalStateException("A2A response must contain exactly one result branch");
        }
        // kind 必须与唯一非空分支一致，不能依赖 Jackson 的空字段容错。
        if (response.message() != null && !"message".equals(response.kind())
                || response.task() != null && !"task".equals(response.kind())
                || response.error() != null && !"error".equals(response.kind())) {
            throw new IllegalStateException("A2A response kind does not match result branch");
        }
        if (response.error() != null) {
            // 错误分支必须提供稳定非空错误码和说明。
            if (response.error().code() == null
                    || response.error().code().isBlank()
                    || response.error().message() == null
                    || response.error().message().isBlank()) {
                throw new IllegalStateException("invalid A2A error contract");
            }
            return response;
        }
        if (response.task() != null) {
            // Task 与所有 Artifact 先完成内部契约校验。
            validateTask(response.task());
            if (request != null) {
                // 返回 Task 必须保持请求的 context 和业务任务映射。
                if (!request.contextId().equals(response.task().contextId())
                        || !request.businessTaskId().equals(response.task().businessTaskId())
                        || request.taskId() != null
                                && !request.taskId().equals(response.task().id())) {
                    throw new IllegalStateException("A2A Task mapping differs from request");
                }
            }
            return response;
        }
        // 直接 Message 的必填 ID 和文本必须完整。
        A2aMessage message = response.message();
        if (!validId(message.messageId())
                || !validId(message.contextId())
                || (message.taskId() != null && !validId(message.taskId()))
                || !validId(message.businessTaskId())
                || message.text() == null
                || message.text().isBlank()) {
            throw new IllegalStateException("invalid A2A Message contract");
        }
        if (request != null
                && (!request.contextId().equals(message.contextId())
                        || !request.businessTaskId().equals(message.businessTaskId())
                        || request.taskId() == null && message.taskId() != null
                        || request.taskId() != null && !request.taskId().equals(message.taskId()))) {
            throw new IllegalStateException("A2A Message mapping differs from request");
        }
        return response;
    }

    private static boolean validId(String value) {
        // 响应 ID 使用与服务端一致的有限 ASCII 白名单。
        return value != null && ID.matcher(value).matches();
    }

    private record ExchangeResult(int statusCode, HttpHeaders headers, byte[] body) {}

    private static final class RemoteStatusException extends IllegalStateException {
        private final int statusCode;

        private RemoteStatusException(int statusCode) {
            // 错误消息只记录状态码，不包含不可信响应体。
            super("unexpected A2A HTTP status " + statusCode);
            // 保存状态码供协调流程识别 404。
            this.statusCode = statusCode;
        }
    }
}
