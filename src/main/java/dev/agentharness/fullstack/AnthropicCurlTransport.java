package dev.agentharness.fullstack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** 通过固定 curl argv 调用 Anthropic Messages API 的受控传输。 */
public final class AnthropicCurlTransport implements ModelTransport {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final List<String> CURL_ARGV =
            List.of("/usr/bin/curl", "--disable", "--config", "-");
    private static final int RESPONSE_LIMIT = 65_536;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final ModelBudget budget;
    private final CurlExecutor executor;

    public AnthropicCurlTransport(
            URI endpoint,
            String apiKey,
            String model,
            ModelBudget budget,
            CurlExecutor executor) {
        // endpoint 只能来自受信启动配置，并在此再次执行 HTTPS 约束。
        this.endpoint = validateEndpoint(endpoint);
        // 空密钥不能进入真实模式。
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("trusted model key is required");
        }
        // 空模型名不能触发宽泛供应商默认值。
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("trusted model name is required");
        }
        // 密钥仅保存在内存字段，后续只写入 curl stdin。
        this.apiKey = apiKey;
        // 模型同样固定于受信启动配置。
        this.model = model;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public static AnthropicCurlTransport fromTrustedEnvironment(ModelBudget budget) {
        // 真实模式只读取固定环境变量，不接受 CLI 或模型覆盖 endpoint。
        String base = System.getenv("ANTHROPIC_BASE_URL");
        String key = System.getenv("ANTHROPIC_API_KEY");
        String model = System.getenv("ANTHROPIC_MODEL");
        // 受信启动器提供 HTTPS 服务基路径，产品固定拼接 Messages endpoint。
        return new AnthropicCurlTransport(
                messagesEndpoint(base), key, model, budget, new ProcessCurlExecutor());
    }

    @Override
    public Reply complete(RolePrompt prompt) {
        // 请求参数必须完整，防止空角色或空 prompt 进入真实服务。
        Objects.requireNonNull(prompt, "prompt");
        boolean finalRound = prompt.toolResult() != null;
        // 请求计数与角色/阶段 cap 在启动 curl 前原子预留并持久化。
        ModelBudget.RequestWindow window = budget.beforeRequest(
                prompt.role(), prompt.invocation(), finalRound, prompt.maxTokens());
        try {
            // curl 的完整配置、密钥和 JSON body 只经 stdin 传输。
            CurlResult result =
                    executor.execute(
                            CURL_ARGV,
                            curlConfig(prompt, window.networkTimeout()),
                            window.processTimeout(),
                            RESPONSE_LIMIT);
            // 非 2xx 结果不自动重试，并按未知输出保留完整预留。
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw new IllegalStateException("model request failed with sanitized status");
            }
            // 只解析受限大小内的固定 Anthropic 响应结构。
            Reply reply = parseReply(result.body());
            // usage 成功解析后按真实输出结算并释放预留差额。
            budget.record(reply.usage());
            return reply;
        } catch (CurlFailure failure) {
            // 明确 pre-connect 释放输出 cap；其它未知结果保留完整 cap，均禁止重试。
            settleFailure(failure, failure.isPreConnectFailure());
            throw new IllegalStateException("model transport failed", failure);
        } catch (RuntimeException failure) {
            // 响应状态、usage 缺失或解析失败都视为结果未知并停止。
            settleFailure(failure, false);
            throw failure;
        }
    }

    private void settleFailure(Throwable primary, boolean definitelyPreConnect) {
        try {
            budget.recordFailure(definitelyPreConnect);
        } catch (RuntimeException ledgerFailure) {
            // 保留原始 transport/解析错误，ledger 错误作为受控附加原因。
            primary.addSuppressed(ledgerFailure);
        }
    }

    @Override
    public String modelName() {
        // 只返回受信模型标识，不返回 endpoint。
        return model;
    }

    private String curlConfig(RolePrompt prompt, Duration networkTimeout) {
        // 使用 Jackson 构造固定 Messages 请求，避免手拼不可信 JSON。
        Map<String, Object> request = new LinkedHashMap<>();
        // 工具 schema 对前端明确三个允许路径，其他角色仍只允许顶层 outputs。
        String payloadDescription =
                prompt.role() == FullStackRole.FRONTEND
                        ? "JSON object with exactly one top-level outputs object; outputs must contain only files with exactly web/index.html, web/styles.css, and web/app.js as non-empty strings whose combined UTF-8 content is at most 20480 bytes; include no plan, timeline, or explanation; role and invocation are server-bound"
                        : "JSON object with exactly one top-level outputs object; role and invocation are server-bound";
        request.put("model", model);
        request.put("max_tokens", prompt.maxTokens());
        request.put("system", prompt.systemPrompt());
        request.put(
                "tools",
                List.of(
                        Map.of(
                                "name",
                                "submit_role_output",
                                "description",
                                "Submit the role output JSON exactly once.",
                                "input_schema",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "payload",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        payloadDescription)),
                                        "required",
                                        List.of("payload"),
                                        "additionalProperties",
                                        false))));
        // 第一轮强制唯一工具，第二轮允许模型基于匹配结果输出最终文本。
        if (prompt.toolResult() == null) {
            request.put(
                    "tool_choice", Map.of("type", "tool", "name", "submit_role_output"));
        }
        // 第一轮只有普通 user message；第二轮保留 assistant tool_use 和匹配 user tool_result。
        if (prompt.toolResult() == null) {
            request.put(
                    "messages",
                    List.of(Map.of("role", "user", "content", prompt.userPrompt())));
        } else {
            if (prompt.priorToolId() == null || prompt.priorToolInputJson() == null) {
                throw new IllegalArgumentException("prior tool call is required for tool result");
            }
            JsonNode priorInput = parseObject(prompt.priorToolInputJson(), "prior tool input");
            request.put(
                    "messages",
                    List.of(
                            Map.of("role", "user", "content", prompt.userPrompt()),
                            Map.of(
                                    "role",
                                    "assistant",
                                    "content",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "tool_use",
                                                    "id",
                                                    prompt.priorToolId(),
                                                    "name",
                                                    "submit_role_output",
                                                    "input",
                                                    priorInput))),
                            Map.of(
                                    "role",
                                    "user",
                                    "content",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "tool_result",
                                                    "tool_use_id",
                                                    prompt.priorToolId(),
                                                    "content",
                                                    prompt.toolResult())))));
        }
        String body = toJson(request);
        // curl 配置自身包含 data、key 和 URL，因此 argv 始终固定且无秘密。
        return "url = \""
                + escapeConfig(endpoint.toString())
                + "\"\nrequest = \"POST\"\n"
                + "header = \"content-type: application/json\"\n"
                + "header = \"anthropic-version: 2023-06-01\"\n"
                + "header = \"x-api-key: "
                + escapeConfig(apiKey)
                + "\"\n"
                + "data = \""
                + escapeConfig(body)
                + "\"\n"
                + "proto = \"=https\"\nmax-redirs = 0\nconnect-timeout = 10\nmax-time = "
                + curlSeconds(networkTimeout)
                + "\n"
                + "silent\nshow-error\nwrite-out = \"\\nAGENT_HARNESS_STATUS:%{http_code}\"\n";
    }

    private static String curlSeconds(Duration timeout) {
        // curl 接受小数秒；至少保留一毫秒，避免临界剩余时间被格式化为零。
        long millis = Math.max(1L, timeout.toMillis());
        if (millis % 1_000L == 0) {
            return Long.toString(millis / 1_000L);
        }
        return java.math.BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString();
    }

    private static Reply parseReply(String body) {
        try {
            // 响应必须是单个 JSON 对象并包含 usage。
            JsonNode root = JSON.readTree(body);
            JsonNode usage = root.path("usage");
            if (!usage.has("input_tokens") || !usage.has("output_tokens")) {
                throw new IllegalStateException("model response usage missing");
            }
            ModelUsage modelUsage =
                    new ModelUsage(
                            usage.path("input_tokens").intValue(),
                            usage.path("output_tokens").intValue());
            JsonNode content = root.path("content");
            if (!content.isArray() || content.isEmpty()) {
                throw new IllegalStateException("unexpected model response blocks");
            }
            JsonNode toolUse = null;
            StringBuilder text = new StringBuilder();
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("text".equals(type) && block.path("text").isTextual()) {
                    text.append(block.path("text").asText());
                    continue;
                }
                if ("tool_use".equals(type)
                        && block.path("id").isTextual()
                        && "submit_role_output".equals(block.path("name").asText())
                        && block.path("input").isObject()
                        && toolUse == null) {
                    toolUse = block;
                    continue;
                }
                throw new IllegalStateException("unexpected model response block");
            }
            // 允许说明文本伴随至多一个固定工具调用，工具调用优先驱动 AgentScope。
            if (toolUse != null) {
                return new Reply(
                        null,
                        toolUse.path("id").asText(),
                        toolUse.path("name").asText(),
                        toolUse.path("input").toString(),
                        modelUsage);
            }
            if (!text.isEmpty()) {
                return new Reply(text.toString(), null, null, null, modelUsage);
            }
            throw new IllegalStateException("model response content empty");
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid model response", exception);
        }
    }

    private static URI validateEndpoint(URI endpoint) {
        // endpoint 必须是无 userinfo/query/fragment 的绝对 HTTPS 地址。
        if (endpoint == null
                || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("trusted model endpoint must be HTTPS");
        }
        return endpoint;
    }

    private static URI messagesEndpoint(String base) {
        // 缺失配置在创建任何进程前稳定失败。
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("trusted model base is required");
        }
        URI trustedBase = validateEndpoint(URI.create(base));
        String value = trustedBase.toString();
        // 显式可信 endpoint 保持原样，否则只在受信服务基路径后拼固定 API 路径。
        if (trustedBase.getPath() != null && trustedBase.getPath().endsWith("/v1/messages")) {
            return trustedBase;
        }
        return validateEndpoint(URI.create(value.replaceFirst("/+$", "") + "/v1/messages"));
    }

    private static JsonNode parseObject(String json, String label) {
        try {
            JsonNode value = JSON.readTree(json);
            if (!value.isObject()) {
                throw new IllegalArgumentException(label + " must be a JSON object");
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(label + " is invalid", exception);
        }
    }

    private static String escapeConfig(String value) {
        // curl config 双引号值只允许显式转义反斜杠、引号和换行。
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot encode model request", exception);
        }
    }

    public interface CurlExecutor {
        CurlResult execute(
                        List<String> argv, String stdin, Duration timeout, int responseLimitBytes)
                throws CurlFailure;
    }

    public record CurlResult(int statusCode, String body, String stderr) {}

    public static final class CurlFailure extends Exception {
        private final boolean preConnectFailure;

        private CurlFailure(String message, boolean preConnectFailure) {
            super(message);
            this.preConnectFailure = preConnectFailure;
        }

        public static CurlFailure preConnectFailure() {
            return new CurlFailure("pre-connect failure", true);
        }

        public static CurlFailure resultUnknown() {
            return new CurlFailure("request result unknown", false);
        }

        public static CurlFailure responseTooLarge() {
            return new CurlFailure("response too large", false);
        }

        boolean isPreConnectFailure() {
            return preConnectFailure;
        }
    }

    private static final class ProcessCurlExecutor implements CurlExecutor {
        @Override
        public CurlResult execute(
                        List<String> argv, String stdin, Duration timeout, int responseLimitBytes)
                throws CurlFailure {
            Process process = null;
            try {
                // ProcessBuilder 只接收固定 argv，不经 shell 解析。
                process = new ProcessBuilder(argv).start();
                // 配置、密钥和 body 一次性写入子进程 stdin，随后立即关闭。
                try (OutputStream output = process.getOutputStream()) {
                    output.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
                Process running = process;
                // stdout 与 stderr 分别并发有界读取，避免任一管道阻塞进程。
                CompletableFuture<byte[]> stdout =
                        CompletableFuture.supplyAsync(
                                () ->
                                        readBounded(
                                                running.getInputStream(),
                                                responseLimitBytes + 64,
                                                running));
                CompletableFuture<byte[]> stderr =
                        CompletableFuture.supplyAsync(
                                () ->
                                        readBounded(
                                                running.getErrorStream(),
                                                responseLimitBytes,
                                                running));
                // Java 侧 125 秒 deadline 包住 curl 固定 120 秒总时限和流回收。
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw CurlFailure.resultUnknown();
                }
                // curl 退出码 28 可能发生在请求发送后，结果未知时禁止自动重试。
                if (process.exitValue() == 28) {
                    throw CurlFailure.resultUnknown();
                }
                String out = new String(stdout.join(), StandardCharsets.UTF_8);
                String err = new String(stderr.join(), StandardCharsets.UTF_8);
                // DNS 或连接建立失败明确发生在发送前，最多允许重试一次。
                if (process.exitValue() == 6 || process.exitValue() == 7) {
                    throw CurlFailure.preConnectFailure();
                }
                // 其他非零退出码属于不可重试的传输失败。
                if (process.exitValue() != 0) {
                    throw new CurlFailure("curl exited unsuccessfully", false);
                }
                String marker = "\nAGENT_HARNESS_STATUS:";
                int markerIndex = out.lastIndexOf(marker);
                if (markerIndex < 0) {
                    throw new CurlFailure("missing status", false);
                }
                String body = out.substring(0, markerIndex);
                if (body.getBytes(StandardCharsets.UTF_8).length > responseLimitBytes) {
                    throw CurlFailure.responseTooLarge();
                }
                int status = Integer.parseInt(out.substring(markerIndex + marker.length()).trim());
                return new CurlResult(status, body, err);
            } catch (CurlFailure failure) {
                throw failure;
            } catch (CompletionException exception) {
                // 任一输出流超过上限时读取线程已主动终止进程。
                if (hasCause(exception, OutputLimitException.class)) {
                    throw CurlFailure.responseTooLarge();
                }
                throw new CurlFailure("curl stream failed", false);
            } catch (Exception exception) {
                throw new CurlFailure("curl execution failed", false);
            } finally {
                if (process != null) {
                    // 无论解析或预算结果如何，都主动关闭全部子进程流。
                    process.destroyForcibly();
                    closeQuietly(process.getInputStream());
                    closeQuietly(process.getErrorStream());
                    closeQuietly(process.getOutputStream());
                }
            }
        }

        private static byte[] readBounded(InputStream input, int limit, Process process) {
            try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8_192];
                int total = 0;
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    total += read;
                    if (total > limit) {
                        // 超限时立即终止子进程并关闭流，不能等待远端继续发送。
                        process.destroyForcibly();
                        throw new OutputLimitException();
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } catch (IOException exception) {
                throw new IllegalStateException("cannot read bounded process output", exception);
            }
        }

        private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
            // 仅遍历异常 cause 链，不读取或拼接可能含供应商正文的消息。
            Throwable current = error;
            while (current != null) {
                if (type.isInstance(current)) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }

        private static final class OutputLimitException extends RuntimeException {}

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 清理失败不能覆盖真实传输失败。
            }
        }
    }
}
