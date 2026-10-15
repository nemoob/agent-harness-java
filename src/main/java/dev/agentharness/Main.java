package dev.agentharness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.agentharness.a2a.A2aClient;
import dev.agentharness.a2a.A2aMessage;
import dev.agentharness.a2a.A2aResponse;
import dev.agentharness.a2a.A2aServer;
import dev.agentharness.a2a.AgentCard;
import dev.agentharness.diagnostic.DiagnosticService;
import dev.agentharness.fullstack.FullStackReport;
import dev.agentharness.fullstack.FullStackRequest;
import dev.agentharness.fullstack.FullStackService;
import dev.agentharness.fullstack.ModelMode;
import dev.agentharness.workflow.WorkflowScenario;
import dev.agentharness.workflow.WorkflowService;
import java.net.URISyntaxException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** 单 JAR 的五个公开教学命令入口。 */
public final class Main {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private Main() {}

    public static void main(String[] args) {
        try {
            // 至少需要一个白名单命令名。
            if (args.length == 0) {
                throw new IllegalArgumentException("command required: diagnose|resume|workflow|a2a-server|a2a-send|fullstack");
            }
            // 根据固定命令路由，不执行动态类或 shell。
            Object result =
                    switch (args[0]) {
                        case "diagnose" -> diagnose(parse(args, Set.of("state-root", "session", "missing-request-evidence")));
                        case "resume" -> resume(parse(args, Set.of("state-root", "session", "stop-after")));
                        case "workflow" -> workflow(parse(args, Set.of("state-root", "session", "scenario")));
                        case "a2a-server" -> {
                            serve(parse(args, Set.of("state-root", "port")));
                            yield null;
                        }
                        case "a2a-send" ->
                                send(
                                        parse(
                                                args,
                                                Set.of(
                                                        "url",
                                                        "message-id",
                                                        "context-id",
                                                        "task-id",
                                                        "business-task-id",
                                                        "text")));
                        case "fullstack" ->
                                fullstack(
                                        parse(
                                                args,
                                                Set.of(
                                                        "mode",
                                                        "requirement-file",
                                                        "state-root",
                                                        "session",
                                                        "preview-port")));
                        default -> throw new IllegalArgumentException("unknown command");
                    };
            // 阻塞服务命令自行输出启动信息，其余命令输出一行 JSON。
            if (result != null) {
                System.out.println(toJson(result));
            }
        } catch (RuntimeException e) {
            // CLI 只输出稳定错误类型，避免堆栈和主机路径泄漏。
            System.err.println(toJson(Map.of("status", "ERROR", "message", safeMessage(e))));
            // 失败命令以非零退出码结束。
            System.exit(2);
        }
    }

    private static Object diagnose(Map<String, String> options) {
        // 诊断命令使用受限状态根和白名单会话。
        DiagnosticService service = new DiagnosticService(stateRoot(options));
        // 缺证据开关只接受显式 true/false。
        boolean missing = booleanOption(options, "missing-request-evidence", false);
        // 运行完整模型→工具→模型闭环。
        return service.diagnose(required(options, "session"), missing);
    }

    private static Object resume(Map<String, String> options) {
        // 恢复命令复用同一个文件状态仓库。
        DiagnosticService service = new DiagnosticService(stateRoot(options));
        // stop-after 只允许 config，用于跨进程中断演示。
        String stopAfter = options.get("stop-after");
        if (stopAfter != null && !"config".equals(stopAfter)) {
            throw new IllegalArgumentException("stop-after must be config");
        }
        // 已完成动作由应用状态去重。
        return service.resume(required(options, "session"), stopAfter != null);
    }

    private static Object workflow(Map<String, String> options) {
        // 将公开场景名映射到固定 fixture 枚举。
        WorkflowScenario scenario =
                switch (options.getOrDefault("scenario", "pass")) {
                    case "pass" -> WorkflowScenario.PASS;
                    case "pending" -> WorkflowScenario.REQUIREMENT_PENDING;
                    case "incorrect" -> WorkflowScenario.INCORRECT_CHANGE;
                    default -> throw new IllegalArgumentException("scenario must be pass|pending|incorrect");
                };
        // 四个角色分别进入真实 AgentScope 循环。
        return new WorkflowService(stateRoot(options)).run(required(options, "session"), scenario);
    }

    private static void serve(Map<String, String> options) {
        // 端口只接受十进制整数并由服务再次检查范围。
        int port = integerOption(options, "port", 8080);
        // 服务只绑定 127.0.0.1。
        A2aServer server = new A2aServer(stateRoot(options));
        URI base = server.start(port);
        // 关闭钩子只停止本进程创建的本地服务。
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "a2a-shutdown"));
        // 启动成功后立即输出可供本地客户端使用的基址。
        System.out.println(toJson(Map.of("status", "RUNNING", "baseUrl", base.toString())));
        System.out.flush();
        try {
            // 服务进程保持前台运行，直到收到进程终止信号。
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            // 恢复中断状态并关闭服务。
            Thread.currentThread().interrupt();
            server.close();
        }
    }

    private static Object send(Map<String, String> options) {
        // 客户端会再次限制 URL 为带显式端口的 IPv4 环回 HTTP。
        URI base = URI.create(required(options, "url"));
        // 发送方提供 messageId；其他 ID 均保持独立字段。
        A2aMessage message =
                new A2aMessage(
                        required(options, "message-id"),
                        required(options, "context-id"),
                        options.get("task-id"),
                        options.getOrDefault("business-task-id", "CFG-001"),
                        options.getOrDefault("text", "diagnose"));
        // 先读取并验证 Card 的版本与固定接口。
        A2aClient client = new A2aClient();
        AgentCard card = client.card(base);
        // message/send 不做自动重试。
        A2aResponse response = client.send(base, message);
        // 输出 Card 与响应以展示 HTTP 语义映射。
        return Map.of("card", card, "response", response);
    }

    private static FullStackReport fullstack(Map<String, String> options) {
        // 模式必须显式指定，避免真实网络与离线回放之间存在默认歧义。
        ModelMode mode =
                switch (required(options, "mode")) {
                    case "real" -> ModelMode.REAL;
                    case "replay" -> ModelMode.REPLAY;
                    default -> throw new IllegalArgumentException("mode must be real|replay");
                };
        // 请求构造器统一验证文件、状态根、session 和端口边界。
        FullStackRequest request =
                new FullStackRequest(
                        mode,
                        Path.of(required(options, "requirement-file")),
                        Path.of(required(options, "state-root")),
                        required(options, "session"),
                        integerOption(options, "preview-port", 18080));
        try {
            // 基础 JAR 来自当前代码源，不能由 CLI 或模型覆盖。
            Path baseJar =
                    Path.of(
                            Main.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI());
            // fullstack 服务按固定八角色路由执行并返回脱敏摘要。
            return new FullStackService(baseJar).run(request);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("application artifact cannot be resolved", exception);
        }
    }

    private static Map<String, String> parse(String[] args, Set<String> allowlist) {
        // 命令后只接受 --key value 成对参数。
        if ((args.length - 1) % 2 != 0) {
            throw new IllegalArgumentException("options must use --key value pairs");
        }
        // 保持参数输入顺序便于错误定位。
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            // 选项必须以双短横线开头。
            String raw = args[index];
            if (!raw.startsWith("--") || raw.length() == 2) {
                throw new IllegalArgumentException("invalid option name");
            }
            // 删除固定前缀后检查命令级 allowlist。
            String key = raw.substring(2);
            if (!allowlist.contains(key)) {
                throw new IllegalArgumentException("unsupported option: " + key);
            }
            // 重复选项直接拒绝，避免优先级歧义。
            if (options.putIfAbsent(key, args[index + 1]) != null) {
                throw new IllegalArgumentException("duplicate option: " + key);
            }
        }
        // 返回本地解析结果。
        return options;
    }

    private static Path stateRoot(Map<String, String> options) {
        // 默认运行时状态落在仓库根定向忽略的 state 目录。
        return Path.of(options.getOrDefault("state-root", "state"));
    }

    private static String required(Map<String, String> options, String key) {
        // 必填值不得缺失或空白。
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required option: " + key);
        }
        // 返回通过最小校验的值，由领域层继续验证。
        return value;
    }

    private static boolean booleanOption(
            Map<String, String> options, String key, boolean defaultValue) {
        // 未提供开关时使用命令定义的默认值。
        String value = options.get(key);
        if (value == null) {
            return defaultValue;
        }
        // 仅接受无歧义的小写布尔文本。
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        // 转换已验证值。
        return Boolean.parseBoolean(value);
    }

    private static int integerOption(Map<String, String> options, String key, int defaultValue) {
        // 未提供整数时使用命令默认值。
        String value = options.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            // 使用十进制解析，不执行表达式。
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // 非整数输入以稳定参数错误拒绝。
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
    }

    private static String safeMessage(RuntimeException error) {
        // 只允许固定异常消息进入 CLI，不输出 cause 或堆栈。
        String message = error.getMessage();
        // 空消息回退到稳定通用说明。
        return message == null || message.isBlank() ? "command failed" : message;
    }

    private static String toJson(Object value) {
        try {
            // 所有 CLI 输出均使用固定 Jackson 编码。
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 固定结果无法编码属于应用错误。
            throw new IllegalStateException("cannot encode command result", e);
        }
    }
}
