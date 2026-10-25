package dev.agentharness.demo.operations;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agentharness.demo.domain.DateValidation;
import dev.agentharness.demo.infra.AgentScopeGateway;
import dev.agentharness.demo.infra.AppConfig;
import io.agentscope.core.tool.Toolkit;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 固定产物的本机运行实验；运行阶段直接装配 AgentScope 与日期工具。 */
public final class OperationsMain {
    private static final Path PRIVATE_DIR = Path.of("target", "operations-bundle", "private");
    private static final Path CONNECTION = PRIVATE_DIR.resolve("connection");
    private static final Path CONNECTION_TEMP = PRIVATE_DIR.resolve("connection.tmp");
    private static final Pattern REQUEST_ID = Pattern.compile("r-[0-9a-f]{16}");
    private static final Pattern RESPONSE = Pattern.compile("\\{\"requestId\":\"(r-[0-9a-f]{16})\",\"date\":\"([0-9]{4}-[0-9]{2}-[0-9]{2})\",\"valid\":(true|false)\\}");
    private static final AtomicReference<String> LAST_COMPLETED_REQUEST = new AtomicReference<>("none");
    private static final Semaphore MODEL_PERMIT = new Semaphore(1);
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private OperationsMain() { }

    public static void main(String[] args) throws Exception {
        // 只允许脚本使用的两个固定入口，拒绝额外参数。
        if (args.length == 2 && "server".equals(args[0]) && ("deepseek".equals(args[1]) || "offline".equals(args[1]))) {
            serve("deepseek".equals(args[1]));
        } else if (args.length == 2 && "client".equals(args[0]) && "2024-02-29".equals(args[1])) {
            client();
        } else {
            throw new IllegalArgumentException("只支持固定 server 或 client 2024-02-29 入口");
        }
    }

    static boolean validRequestId(String value) {
        // 请求标识只使用受限 ASCII，避免日志注入和任意反射。
        return value != null && REQUEST_ID.matcher(value).matches();
    }

    static boolean privatePathIsSafe(Path dir) {
        // 拒绝目标目录及其上级的符号链接，避免连接资料越界。
        for (Path part = dir.toAbsolutePath().normalize(); part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) {
                return false;
            }
        }
        // 已存在的目录必须确实是目录。
        return !Files.exists(dir, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS);
    }

    private static void prepareConnectionDirectory() throws IOException {
        // 连接文件与目录均不能借符号链接转向其他位置。
        if (!privatePathIsSafe(PRIVATE_DIR) || Files.exists(CONNECTION, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(CONNECTION_TEMP, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("连接目录或文件已占用或不安全");
        }
        // 用私有权限创建目录；演示仅支持 POSIX 本机环境。
        Files.createDirectories(PRIVATE_DIR);
        Files.setPosixFilePermissions(PRIVATE_DIR, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }

    private static void serve(boolean deepseek) throws Exception {
        // 真实路径只从进程环境取得 Key，公开配置沿用现有加载器。
        AppConfig config = deepseek ? new AppConfig(Path.of("."), "deepseek", System.getenv()) : null;
        // operations 提示词与通用助手提示词分离，避免模型跳过本例工具。
        String prompt = deepseek ? Files.readString(Path.of("config", "operations-prompt.txt"), StandardCharsets.UTF_8) : "";
        // 启动前确认连接资料只落在本实验的私有目录。
        prepareConnectionDirectory();
        // 每次启动生成独立的 256 位本机 Bearer，绝不写入日志或命令行。
        byte[] randomToken = new byte[32];
        new SecureRandom().nextBytes(randomToken);
        String token = HexFormat.of().formatHex(randomToken);
        // 仅监听环回地址，由系统选择未占用端口。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        // 两个工作线程和八个候选任务形成明确的本机资源上限。
        ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);
        server.createContext("/", exchange -> handle(exchange,
                (id, date) -> deepseek ? agentValidation(id, date, config, prompt) : new DateValidation().isValid(date), token));
        // 真正的 JVM 停止信号触发有界资源收尾。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("STOP phase=stop_accepting requestId=" + LAST_COMPLETED_REQUEST.get());
            server.stop(0);
            System.out.println("STOP phase=workers_wait");
            workers.shutdown();
            try {
                // 已接收的请求最多等待三秒。
                if (!workers.awaitTermination(3, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                }
                // 连接资料只删除本服务创建的固定文件。
                Files.deleteIfExists(CONNECTION);
                Files.deleteIfExists(CONNECTION_TEMP);
                System.out.println("STOP phase=connection_removed");
            } catch (InterruptedException | IOException failure) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
                System.err.println("STOP phase=cleanup_failed type=" + failure.getClass().getSimpleName());
            }
            System.out.println("STOP phase=done");
        }, "operations-shutdown"));
        // 先启动接入，再公布端口，客户端不会读到尚未监听的地址。
        server.start();
        try {
            // 创建时就设置 0600，避免先宽权限落盘再 chmod 的短暂窗口。
            Files.createFile(CONNECTION_TEMP, PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS));
            Files.writeString(CONNECTION_TEMP, "port=" + server.getAddress().getPort() + "\ntoken=" + token + "\n", StandardCharsets.US_ASCII,
                    StandardOpenOption.WRITE);
            // 同一私有目录内原子公布完整内容，客户端不会读到半写入文件。
            Files.move(CONNECTION_TEMP, CONNECTION, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("START host=127.0.0.1 port=" + server.getAddress().getPort()
                    + " provider=" + (deepseek ? "deepseek" : "offline") + " model=" + (deepseek ? config.model : "rule"));
            // 主线程等待真实 JVM 停止信号，不用 close() 冒充信号。
            Thread.currentThread().join();
        } catch (Exception failure) {
            server.stop(0);
            workers.shutdownNow();
            Files.deleteIfExists(CONNECTION);
            Files.deleteIfExists(CONNECTION_TEMP);
            throw failure;
        }
    }

    static void handle(HttpExchange exchange, BiFunction<String, String, Boolean> validation, String token) throws IOException {
        // 仅保留通过白名单的关联标识，拒绝值绝不进入日志。
        String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if ("GET".equals(exchange.getRequestMethod()) && "/health".equals(exchange.getRequestURI().getRawPath())
                && exchange.getRequestURI().getRawQuery() == null) {
            respond(exchange, 200, "ok");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod()) || !"/validate".equals(exchange.getRequestURI().getRawPath())
                || exchange.getRequestURI().getRawQuery() != null || !validRequestId(requestId)) {
            System.out.println("REJECT reason=route_or_request_id");
            respond(exchange, 400, "rejected");
            return;
        }
        // 付费端点在读取正文或创建 Agent 之前核验唯一 Bearer。
        List<String> authorization = exchange.getRequestHeaders().get("Authorization");
        if (authorization == null || authorization.size() != 1 || !constantTimeTokenEquals(token, authorization.get(0))) {
            System.out.println("REJECT reason=auth");
            respond(exchange, 401, "unauthorized");
            return;
        }
        // 请求体固定十字节，超过限制或包含非日期文本均拒绝。
        byte[] body = exchange.getRequestBody().readNBytes(11);
        if (body.length != 10 || exchange.getRequestBody().read() != -1) {
            System.out.println("REJECT requestId=" + requestId + " reason=body_size");
            respond(exchange, 400, "rejected");
            return;
        }
        String date = new String(body, StandardCharsets.US_ASCII);
        System.out.println("RECEIVE requestId=" + requestId);
        // 同一时间只允许一个模型请求；忙碌请求立即返回 429。
        if (!tryReserveModel()) {
            System.out.println("REJECT requestId=" + requestId + " reason=busy");
            respond(exchange, 429, "busy");
            return;
        }
        boolean valid;
        try {
            // 真实路径必须由 AgentScope 工具回填日期事实；离线路径可注入确定性规则。
            valid = processDate(requestId, date, value -> validation.apply(requestId, value));
        } catch (IllegalArgumentException invalidDate) {
            System.out.println("REJECT requestId=" + requestId + " reason=date_format");
            respond(exchange, 400, "rejected");
            return;
        } catch (Exception failure) {
            // 模型失败与未调用工具均不伪装成业务成功。
            System.err.println("AGENT requestId=" + requestId + " outcome=failed type=" + failure.getClass().getSimpleName());
            respond(exchange, 502, "agent_failed");
            return;
        } finally {
            releaseModel();
        }
        System.out.println("RULE requestId=" + requestId + " valid=" + valid);
        // 白名单字段构造响应；日期和标识均已经严格校验。
        respond(exchange, 200, "{\"requestId\":\"" + requestId + "\",\"date\":\"" + date + "\",\"valid\":" + valid + "}");
        LAST_COMPLETED_REQUEST.set(requestId);
        System.out.println("RESPONSE requestId=" + requestId + " status=200");
    }

    static boolean tryReserveModel() {
        // 不等待已有付费请求完成，调用方据此返回 429。
        return MODEL_PERMIT.tryAcquire();
    }

    static void releaseModel() {
        // 每项通过许可的请求只释放一次。
        MODEL_PERMIT.release();
    }

    static boolean constantTimeTokenEquals(String expected, String header) {
        // Bearer 前缀与 64 位十六进制长度固定，比较阶段不泄露匹配前缀。
        if (header == null || !header.matches("Bearer [0-9a-f]{64}")) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                header.substring(7).getBytes(StandardCharsets.US_ASCII));
    }

    static boolean processDate(String requestId, String date, Function<String, Boolean> rule) {
        // 在执行函数前校验标识和格式；离线测试能注入无外部调用的函数。
        if (!validRequestId(requestId)) {
            throw new IllegalArgumentException("请求标识错误");
        }
        new DateValidation().isValid(date);
        // 函数返回空值不能被当成 false 业务结果。
        return java.util.Objects.requireNonNull(rule.apply(date), "日期规则未返回结果");
    }

    private static boolean agentValidation(String requestId, String date, AppConfig config, String prompt) {
        // 每项请求独立装配工具与 Gateway，避免跨请求共享模型会话。
        OperationsDateTool tool = new OperationsDateTool(requestId);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        try (AgentScopeGateway gateway = new AgentScopeGateway(config, prompt, toolkit)) {
            // 固定问题只带入已校验日期；响应结论取工具记录而非模型措辞。
            gateway.ask("请调用 check_operations_date 工具校验日期 " + date + "，然后简短回答。");
            Boolean result = tool.singleResultFor(date);
            if (result == null) {
                throw new IllegalStateException("模型没有调用日期工具");
            }
            System.out.println("AGENT requestId=" + requestId + " outcome=tool_called");
            return result;
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        exchange.getResponseHeaders().set("Content-Type", status == 200 && body.startsWith("{") ? "application/json" : "text/plain; charset=US-ASCII");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record BoundedResponse(int status, String body) { }

    private static BoundedResponse sendBounded(HttpClient http, HttpRequest request) throws Exception {
        // 所有客户端响应最多读 129 字节，超过限制直接按失败处理。
        HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            byte[] bytes = stream.readNBytes(129);
            String body = bytes.length <= 128 && stream.read() == -1
                    ? new String(bytes, StandardCharsets.US_ASCII) : "";
            return new BoundedResponse(response.statusCode(), body);
        }
    }

    private static void client() throws Exception {
        // 客户端只读取固定目录中的数字端口，拒绝链接或异常内容。
        if (!privatePathIsSafe(PRIVATE_DIR) || !Files.isRegularFile(CONNECTION, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(CONNECTION, LinkOption.NOFOLLOW_LINKS).equals(PRIVATE_FILE_PERMISSIONS)
                || !Files.getPosixFilePermissions(PRIVATE_DIR, LinkOption.NOFOLLOW_LINKS).equals(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
                || !Files.getOwner(CONNECTION, LinkOption.NOFOLLOW_LINKS).getName().equals(System.getProperty("user.name"))
                || !Files.getOwner(PRIVATE_DIR, LinkOption.NOFOLLOW_LINKS).getName().equals(System.getProperty("user.name"))
                || Files.size(CONNECTION) > 100) {
            throw new IOException("连接资料不安全");
        }
        String connectionText = Files.readString(CONNECTION, StandardCharsets.US_ASCII);
        Matcher connection = Pattern.compile("port=([0-9]{1,5})\\ntoken=([0-9a-f]{64})\\n").matcher(connectionText);
        if (!connection.matches()) {
            throw new IOException("端口格式错误");
        }
        int port = Integer.parseInt(connection.group(1));
        String token = connection.group(2);
        if (port < 1 || port > 65535) {
            throw new IOException("端口越界");
        }
        // 安全随机数生成受限 requestId，不使用用户输入。
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        StringBuilder id = new StringBuilder("r-");
        for (byte value : random) {
            id.append(String.format("%02x", value & 0xff));
        }
        // 显式禁用系统代理，客户端只直连本机环回服务。
        ProxySelector direct = new ProxySelector() {
            @Override public List<Proxy> select(URI ignored) { return List.of(Proxy.NO_PROXY); }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
        };
        HttpClient http = HttpClient.newBuilder().proxy(direct).connectTimeout(Duration.ofSeconds(2)).build();
        URI uri = URI.create("http://127.0.0.1:" + port + "/validate");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(210)).header("X-Request-Id", id.toString())
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("2024-02-29")).build();
        // 无凭据请求先行，服务日志可证明此时还未进入 Agent。
        HttpRequest unauthorized = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).header("X-Request-Id", id.toString())
                .POST(HttpRequest.BodyPublishers.ofString("2024-02-29")).build();
        if (sendBounded(http, unauthorized).status() != 401) {
            throw new IOException("匿名模型请求未被拒绝");
        }
        System.out.println("CLIENT reject=401 reason=auth");
        // 错误、重复及超长 Authorization 都必须在模型入口前拒绝。
        for (String wrong : List.of("Bearer " + "0".repeat(64), "Bearer " + token + "x")) {
            HttpRequest invalidAuth = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                    .header("X-Request-Id", id.toString()).header("Authorization", wrong)
                    .POST(HttpRequest.BodyPublishers.ofString("2024-02-29")).build();
            if (sendBounded(http, invalidAuth).status() != 401) {
                throw new IOException("错误凭据未被拒绝");
            }
        }
        HttpRequest duplicateAuth = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                .header("X-Request-Id", id.toString()).header("Authorization", "Bearer " + token)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("2024-02-29")).build();
        if (sendBounded(http, duplicateAuth).status() != 401) {
            throw new IOException("重复凭据未被拒绝");
        }
        System.out.println("CLIENT reject=401 reason=auth_variants");
        BoundedResponse response = sendBounded(http, request);
        Matcher matched = RESPONSE.matcher(response.body());
        // 客户端逐项核对状态、关联标识、输入日期和规则结果。
        if (response.status() != 200 || !matched.matches() || !id.toString().equals(matched.group(1))
                || !"2024-02-29".equals(matched.group(2)) || !"true".equals(matched.group(3))) {
            throw new IOException("日期响应核对失败");
        }
        System.out.println("CLIENT requestId=" + id + " date=2024-02-29 valid=true status=200");
        // 错误 requestId 应被服务端拒绝，且不能污染关联日志。
        HttpRequest rejected = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).header("X-Request-Id", "bad-id")
                .POST(HttpRequest.BodyPublishers.ofString("2024-02-29")).build();
        BoundedResponse rejection = sendBounded(http, rejected);
        if (rejection.status() != 400 || !"rejected".equals(rejection.body())) {
            throw new IOException("非法 requestId 未被拒绝");
        }
        System.out.println("CLIENT reject=400 reason=request_id");
        // 路由白名单拒绝日期端点上的 GET。
        HttpRequest wrongRoute = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).header("X-Request-Id", id.toString())
                .header("Authorization", "Bearer " + token).GET().build();
        if (sendBounded(http, wrongRoute).status() != 400) {
            throw new IOException("非法方法未被拒绝");
        }
        System.out.println("CLIENT reject=400 reason=route");
        // 日期正文长度正确但含非数字字符时必须拒绝。
        HttpRequest wrongDate = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).header("X-Request-Id", id.toString())
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("2024-02-2x")).build();
        if (sendBounded(http, wrongDate).status() != 400) {
            throw new IOException("非法日期未被拒绝");
        }
        System.out.println("CLIENT reject=400 reason=date_format");
        // 只写一字节正文的连接必须由服务器接收期限主动关闭，不能占住工作线程。
        try (Socket slow = new Socket("127.0.0.1", port)) {
            slow.setSoTimeout(8_000);
            String prefix = "POST /validate HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Request-Id: " + id
                    + "\r\nAuthorization: Bearer " + token + "\r\nContent-Length: 10\r\nConnection: close\r\n\r\n2";
            slow.getOutputStream().write(prefix.getBytes(StandardCharsets.US_ASCII));
            slow.getOutputStream().flush();
            try {
                if (slow.getInputStream().read() != -1) {
                    throw new IOException("慢正文未被关闭");
                }
            } catch (java.net.SocketException closed) {
                // JDK 允许以连接复位表示超过读取期限。
            }
        }
        System.out.println("CLIENT slow_body_closed=true");
    }
}
