package dev.agentharness.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agentharness.state.AppStateStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** 仅绑定环回地址的 A2A v1.0 HTTP+JSON 教学语义子集。 */
public final class A2aServer implements AutoCloseable {
    public static final String MEDIA_TYPE = "application/a2a+json";
    private static final String VERSION = "1.0";
    private static final int MAX_BODY_BYTES = 65_536;
    private static final long BODY_READ_TIMEOUT_MILLIS = 1_000;
    static final int MAX_TASKS = 64;
    static final int MAX_MESSAGE_IDS = 128;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final ObjectMapper JSON =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final Map<String, A2aTask> tasks = new ConcurrentHashMap<>();
    private final Set<String> messageIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger taskSequence = new AtomicInteger();
    private final AtomicInteger messageSendCount = new AtomicInteger();
    private final Object stateLock = new Object();
    private final AppStateStore stateStore;
    private final ScheduledExecutorService requestDeadlines =
            Executors.newSingleThreadScheduledExecutor();
    private HttpServer server;
    private ExecutorService executor;

    public A2aServer(Path stateRoot) {
        // 复用受限状态根校验，禁止服务将运行文件写到克隆外。
        this.stateStore = new AppStateStore(stateRoot);
    }

    public synchronized URI start(int requestedPort) {
        // 仅允许操作系统分配端口或有效非特权端口。
        if (requestedPort < 0 || requestedPort > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        // 同一个实例只启动一次，避免重复绑定。
        if (server != null) {
            throw new IllegalStateException("server already started");
        }
        try {
            // 固定绑定 IPv4 环回地址，不暴露到局域网。
            InetSocketAddress address =
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort);
            // 使用受限 backlog 创建 JDK 内置 HTTP 服务。
            server = HttpServer.create(address, 16);
            // Card 使用固定精确路径。
            server.createContext("/.well-known/agent-card.json", this::handleCard);
            // message/send 使用固定精确路径。
            server.createContext("/message:send", this::handleSend);
            // Task 查询由固定前缀处理，并在处理器内校验单段 ID。
            server.createContext("/tasks/", this::handleTask);
            // 固定四个工作线程和有限等待队列限制慢请求占用资源。
            executor =
                    new ThreadPoolExecutor(
                            4,
                            4,
                            0L,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(8),
                            new ThreadPoolExecutor.AbortPolicy());
            // 将受限执行器交给服务。
            server.setExecutor(executor);
            // 启动本地服务。
            server.start();
            // 返回调用方可直接使用的固定环回基址。
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        } catch (IOException e) {
            // 绑定失败时清理可能已创建的服务。
            close();
            // 暴露稳定启动错误。
            throw new IllegalStateException("无法启动 A2A 服务", e);
        }
    }

    public int messageSendCount() {
        // 该计数用于验证协调流程没有偷偷重发。
        return messageSendCount.get();
    }

    private void handleCard(HttpExchange exchange) throws IOException {
        // Card 只接受精确 GET。
        if (!"GET".equals(exchange.getRequestMethod())
                || !"/.well-known/agent-card.json".equals(exchange.getRequestURI().getPath())) {
            send(exchange, 405, A2aResponse.error("METHOD_NOT_ALLOWED", "GET required"));
            return;
        }
        // Card 明确声明本语义子集的协议版本、接口与能力。
        AgentCard card =
                new AgentCard(
                        "agent-harness-java",
                        VERSION,
                        Map.of(
                                "messageSend", "/message:send",
                                "taskGet", "/tasks/{id}"),
                        List.of("message", "task", "artifact"));
        // Card 返回相同的 A2A JSON 媒体类型。
        send(exchange, 200, card);
    }

    private void handleSend(HttpExchange exchange) throws IOException {
        // message/send 拒绝路径后缀和其他方法。
        if (!"POST".equals(exchange.getRequestMethod())
                || !"/message:send".equals(exchange.getRequestURI().getPath())) {
            send(exchange, 405, A2aResponse.error("METHOD_NOT_ALLOWED", "POST required"));
            return;
        }
        // 协议版本必须精确匹配本教学子集。
        if (!VERSION.equals(exchange.getRequestHeaders().getFirst("A2A-Version"))) {
            send(exchange, 400, A2aResponse.error("UNSUPPORTED_VERSION", "A2A-Version 1.0 required"));
            return;
        }
        // 请求媒体类型仅允许 A2A JSON，可带标准参数。
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (!matchesMediaType(contentType)) {
            send(exchange, 415, A2aResponse.error("UNSUPPORTED_MEDIA_TYPE", MEDIA_TYPE + " required"));
            return;
        }
        try {
            // 在解析前限制请求体字节数。
            byte[] body = readLimited(exchange);
            // 只反序列化为固定 Message 类型。
            A2aMessage message = JSON.readValue(body, A2aMessage.class);
            // 所有外部 ID 和文本先完成白名单与长度校验。
            validateMessage(message);
            // 在固定容量内原子登记消息，并为新请求预留或创建 Task。
            Admission admission = admit(message);
            if (admission.status() == AdmissionStatus.DUPLICATE) {
                send(exchange, 409, A2aResponse.error("DUPLICATE_MESSAGE_ID", "messageId already seen"));
                return;
            }
            // 达到固定消息或任务容量时稳定拒绝，避免状态集合无界增长。
            if (admission.status() == AdmissionStatus.SATURATED) {
                send(exchange, 503, A2aResponse.error("SERVER_CAPACITY_REACHED", "local state capacity reached"));
                return;
            }
            // 只有通过契约校验的请求才计入真实发送次数。
            messageSendCount.incrementAndGet();
            // 无远端 taskId 的 reply-only 是唯一可直接产生无 Task Message 的入口。
            if (message.taskId() == null) {
                if ("reply-only".equals(message.text())) {
                    A2aMessage reply =
                            new A2aMessage(
                                    "reply-" + message.messageId(),
                                    message.contextId(),
                                    null,
                                    message.businessTaskId(),
                                    "acknowledged");
                    send(exchange, 200, A2aResponse.message(reply));
                    return;
                }
                // 其他新消息返回已在 admit 中创建的服务端 Task。
                send(exchange, 200, A2aResponse.task(admission.task()));
                return;
            }
            // 有 taskId 的后续消息必须引用现存任务。
            A2aTask task = admission.task();
            if (task == null) {
                send(exchange, 404, A2aResponse.error("TASK_NOT_FOUND", "unknown taskId"));
                return;
            }
            // 后续消息必须保持 contextId 和业务 task 映射一致。
            if (!task.contextId().equals(message.contextId())
                    || !task.businessTaskId().equals(message.businessTaskId())) {
                send(exchange, 409, A2aResponse.error("ID_MISMATCH", "task/context/business mapping differs"));
                return;
            }
            // 带 taskId 的 reply-only 也必须先通过存在性和映射检查。
            if ("reply-only".equals(message.text())) {
                A2aMessage reply =
                        new A2aMessage(
                                "reply-" + message.messageId(),
                                message.contextId(),
                                task.id(),
                                message.businessTaskId(),
                                "acknowledged");
                send(exchange, 200, A2aResponse.message(reply));
                return;
            }
            // 匹配后返回同一个远端 Task，不创建重复任务。
            send(exchange, 200, A2aResponse.task(task));
        } catch (PayloadTooLargeException e) {
            // 超大请求体以稳定 413 拒绝。
            send(exchange, 413, A2aResponse.error("PAYLOAD_TOO_LARGE", "request body exceeds 65536 bytes"));
        } catch (RequestBodyTimeoutException e) {
            // 截止时间任务已经关闭 exchange；处理器直接返回释放工作线程。
            exchange.close();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // 解析和字段错误只返回通用契约错误，不回显不可信输入。
            send(exchange, 400, A2aResponse.error("INVALID_MESSAGE", "required fields are invalid"));
        }
    }

    private void handleTask(HttpExchange exchange) throws IOException {
        // Task 查询只接受 GET 和精确单段路径。
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, A2aResponse.error("METHOD_NOT_ALLOWED", "GET required"));
            return;
        }
        // 协议版本必须与 send 一致。
        if (!VERSION.equals(exchange.getRequestHeaders().getFirst("A2A-Version"))) {
            send(exchange, 400, A2aResponse.error("UNSUPPORTED_VERSION", "A2A-Version 1.0 required"));
            return;
        }
        // 去掉固定前缀后仅保留一个白名单 taskId。
        String taskId = exchange.getRequestURI().getPath().substring("/tasks/".length());
        if (!validId(taskId)) {
            send(exchange, 400, A2aResponse.error("INVALID_TASK_ID", "taskId is invalid"));
            return;
        }
        // 按服务端 taskId 查询，不接受业务 task ID 替代。
        A2aTask task = tasks.get(taskId);
        if (task == null) {
            send(exchange, 404, A2aResponse.error("TASK_NOT_FOUND", "unknown taskId"));
            return;
        }
        // Task 响应保持联合类型，便于客户端验证必填字段。
        send(exchange, 200, A2aResponse.task(task));
    }

    private A2aTask createTask(A2aMessage message) {
        // taskId 由服务端生成，与业务 ID 保持独立。
        String taskId = "task-" + taskSequence.incrementAndGet();
        // artifactId 由服务端生成并挂在远端 Task 下。
        A2aArtifact artifact =
                new A2aArtifact(
                        "artifact-" + taskId,
                        message.businessTaskId(),
                        "diagnostic-result",
                        "local semantic subset completed for " + message.businessTaskId());
        // 新任务在该同步教学实现中直接完成。
        return new A2aTask(
                taskId,
                message.contextId(),
                message.businessTaskId(),
                "COMPLETED",
                List.of(artifact));
    }

    private Admission admit(A2aMessage message) {
        // 消息去重、容量判断和新 Task 创建在同一临界区内完成。
        synchronized (stateLock) {
            // 已登记 messageId 返回稳定冲突，不宣称协议级幂等。
            if (messageIds.contains(message.messageId())) {
                return new Admission(AdmissionStatus.DUPLICATE, null);
            }
            // 固定消息集合饱和后拒绝新消息，避免长期运行无界增长。
            if (messageIds.size() >= MAX_MESSAGE_IDS) {
                return new Admission(AdmissionStatus.SATURATED, null);
            }
            // 新建 Task 前先检查固定 Task 容量。
            if (message.taskId() == null
                    && !"reply-only".equals(message.text())
                    && tasks.size() >= MAX_TASKS) {
                return new Admission(AdmissionStatus.SATURATED, null);
            }
            // 只有通过全部容量判断的消息才写入已见集合。
            messageIds.add(message.messageId());
            if (message.taskId() == null && !"reply-only".equals(message.text())) {
                // 新 Task 在同一临界区内创建并写入，避免并发越过容量。
                A2aTask created = createTask(message);
                tasks.put(created.id(), created);
                return new Admission(AdmissionStatus.ACCEPTED, created);
            }
            // 直接 Message 不读取空 taskId，避免把无 Task 结果误交给映射表。
            if (message.taskId() == null) {
                return new Admission(AdmissionStatus.ACCEPTED, null);
            }
            // 后续消息读取既有不可变 Task。
            return new Admission(AdmissionStatus.ACCEPTED, tasks.get(message.taskId()));
        }
    }

    private static void validateMessage(A2aMessage message) {
        // Jackson 的 JSON null 也必须按缺失字段拒绝。
        if (message == null
                || !validId(message.messageId())
                || !validId(message.contextId())
                || (message.taskId() != null && !validId(message.taskId()))
                || !"CFG-001".equals(message.businessTaskId())
                || message.text() == null
                || message.text().isBlank()
                || message.text().length() > 1_024) {
            throw new IllegalArgumentException("invalid message");
        }
    }

    private static boolean validId(String value) {
        // ID 只允许有限 ASCII，排除路径、控制字符和日志注入。
        return value != null && ID.matcher(value).matches();
    }

    private byte[] readLimited(HttpExchange exchange) throws IOException {
        // 原子标记区分真实网络错误和本地截止时间关闭。
        AtomicBoolean timedOut = new AtomicBoolean();
        // 请求流由本方法持有并在成功、失败或超时时统一关闭。
        try (InputStream input = exchange.getRequestBody();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            // 截止时间到达时主动关闭流与 exchange，解除阻塞读取。
            ScheduledFuture<?> deadline =
                    requestDeadlines.schedule(
                            () -> {
                                timedOut.set(true);
                                exchange.close();
                                try {
                                    input.close();
                                } catch (IOException ignored) {
                                    // exchange 已关闭，额外流关闭失败无需覆盖超时语义。
                                }
                            },
                            BODY_READ_TIMEOUT_MILLIS,
                            TimeUnit.MILLISECONDS);
            try {
                // 分块累计请求体，超过 64 KiB 时立即停止并关闭连接。
                byte[] chunk = new byte[8_192];
                int total = 0;
                int read;
                while ((read = input.read(chunk)) != -1) {
                    total += read;
                    if (total > MAX_BODY_BYTES) {
                        throw new PayloadTooLargeException();
                    }
                    output.write(chunk, 0, read);
                }
                // 流因截止时间关闭时不得把截断内容交给 JSON 解析器。
                if (timedOut.get()) {
                    throw new RequestBodyTimeoutException();
                }
                // 返回完整且受限的请求体。
                return output.toByteArray();
            } catch (IOException e) {
                // 截止时间关闭产生的 I/O 异常映射为稳定超时类别。
                if (timedOut.get()) {
                    throw new RequestBodyTimeoutException();
                }
                throw e;
            } finally {
                // 正常完成或其他失败时取消尚未触发的截止任务。
                deadline.cancel(false);
            }
        }
    }

    static boolean matchesMediaType(String value) {
        // 仅剥离分号后的参数，再对基础媒体类型做精确大小写无关匹配。
        if (value == null) {
            return false;
        }
        int parameterStart = value.indexOf(';');
        String base = (parameterStart >= 0 ? value.substring(0, parameterStart) : value).trim();
        return MEDIA_TYPE.equalsIgnoreCase(base);
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        // 使用固定 Jackson 编码响应，禁止手拼不可信 JSON。
        byte[] body = JSON.writeValueAsBytes(value);
        // 明确响应协议版本。
        exchange.getResponseHeaders().set("A2A-Version", VERSION);
        // 明确 A2A JSON 媒体类型和 UTF-8。
        exchange.getResponseHeaders().set("Content-Type", MEDIA_TYPE + "; charset=utf-8");
        // 禁止内容类型嗅探。
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        // 发送固定长度响应。
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            // 写入完整有限响应。
            output.write(body);
        } finally {
            // 显式关闭交换对象释放连接资源。
            exchange.close();
        }
    }

    @Override
    public synchronized void close() {
        // 已启动的服务立即停止接收新请求。
        if (server != null) {
            server.stop(0);
            server = null;
        }
        // 小线程池同步关闭，防止测试进程泄漏。
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        // 关闭请求体截止时间线程，避免测试和命令进程泄漏。
        requestDeadlines.shutdownNow();
    }

    private static final class PayloadTooLargeException extends IOException {}

    private static final class RequestBodyTimeoutException extends IOException {}

    private enum AdmissionStatus {
        ACCEPTED,
        DUPLICATE,
        SATURATED
    }

    private record Admission(AdmissionStatus status, A2aTask task) {}
}
