package dev.agentharness.fullstack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

/** 单一角色输出工具；只接受与服务端任务包一致的结构化制品。 */
public final class RoleOutputTool {
    private static final ObjectMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
    private static final int MAX_PAYLOAD_BYTES = 262_144;
    private static final int MAX_FRONTEND_CONTENT_BYTES = 20_480;
    private static final int MAX_BACKEND_CONTENT_BYTES = 65_536;
    private static final String BACKEND_FILE_PATH =
            "fullstack/generated/GeneratedVersionPolicy.java";
    private final FullStackTaskPacket packet;
    private final AtomicReference<FullStackArtifact> artifact = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    public RoleOutputTool(FullStackTaskPacket packet) {
        // 工具只持有协调器生成的不可变任务包。
        this.packet = packet;
    }

    @Tool(
            name = "submit_role_output",
            description = "Submit the JSON output for the fixed current role.",
            readOnly = false,
            concurrencySafe = false)
    public String submit(
            @ToolParam(
                            name = "payload",
                            description =
                                    "JSON object with exactly one top-level outputs object; FRONTEND outputs.files must contain exactly web/index.html, web/styles.css, and web/app.js with at most 20480 combined UTF-8 bytes; role and invocation are server-bound")
                    String payload) {
        // 同一 Agent 调用只允许写入一个制品。
        if (calls.incrementAndGet() != 1 || artifact.get() != null) {
            throw new IllegalStateException("role output already submitted");
        }
        try {
            // 输入先做 UTF-8 大小限制，再进入固定 Jackson 树解析。
            if (payload == null
                    || payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                throw new IllegalStateException("role output size invalid");
            }
            JsonNode root = JSON.readTree(payload);
            // 顶层必须是对象，角色与调用序号不再接受模型自报。
            if (!root.isObject()) {
                throw new IllegalStateException("role output object required");
            }
            // 身份字段由服务端任务包绑定，payload 出现同名字段也拒绝。
            if (root.has("role") || root.has("invocation")) {
                throw new IllegalStateException("role output identity fields forbidden");
            }
            // 顶层只允许 outputs，避免模型注入额外控制字段。
            if (!root.has("outputs")) {
                throw new IllegalStateException("role output outputs missing");
            }
            if (root.size() != 1) {
                throw new IllegalStateException("role output fields invalid");
            }
            // outputs 必须是至少含一个字段的对象，拒绝看似成功的空制品。
            if (!root.path("outputs").isObject() || root.path("outputs").isEmpty()) {
                throw new IllegalStateException("role output outputs missing");
            }
            // 前端角色只接受既定三个文本文件，工具阶段即拒绝路径和类型扩张。
            validateRoleOutputs(root.path("outputs"));
            // 递归排序对象键，使跨 JVM 的新格式制品字节与 SHA 稳定。
            String canonical = JSON.writeValueAsString(canonicalize(root));
            String sha256 = sha256(canonical);
            String artifactId = "fs-" + sha256.substring(0, 24);
            // lineage 只从本地任务包继承，不读取模型 payload 中的同名字段。
            FullStackArtifact accepted =
                    new FullStackArtifact(
                            artifactId,
                            packet.role(),
                            packet.invocation(),
                            packet.packetId(),
                            packet.predecessorArtifactIds(),
                            canonical,
                            sha256);
            // CAS 防止并发或重复工具调用覆盖首个制品。
            if (!artifact.compareAndSet(null, accepted)) {
                throw new IllegalStateException("role output already submitted");
            }
            // 返回固定类型制品 JSON，供 AgentScope 回填 ToolResultBlock。
            return JSON.writeValueAsString(accepted);
        } catch (JsonProcessingException exception) {
            // 解析错误不回显可能含 prompt 或秘密的完整 payload。
            throw new IllegalStateException("role output JSON invalid", exception);
        }
    }

    public FullStackArtifact artifact() {
        // 调用方只能读取已经通过工具校验的制品。
        FullStackArtifact value = artifact.get();
        if (value == null) {
            throw new IllegalStateException("role output was not submitted");
        }
        return value;
    }

    public int calls() {
        // 返回框架实际工具调用次数用于闭环证据。
        return calls.get();
    }

    private static String sha256(String value) {
        try {
            // Java 标准库计算制品 SHA-256，不引入额外依赖。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static JsonNode canonicalize(JsonNode value) {
        // 对象字段按 Unicode 字典序递归写入新节点。
        if (value.isObject()) {
            ObjectNode ordered = JSON.createObjectNode();
            StreamSupport.stream(
                            ((Iterable<String>) value::fieldNames).spliterator(), false)
                    .sorted()
                    .forEach(field -> ordered.set(field, canonicalize(value.get(field))));
            return ordered;
        }
        // 数组保留业务顺序，仅递归规范化其中对象。
        if (value.isArray()) {
            ArrayNode ordered = JSON.createArrayNode();
            value.forEach(element -> ordered.add(canonicalize(element)));
            return ordered;
        }
        // 标量节点不可变且没有键顺序，直接复用。
        return value;
    }

    private void validateRoleOutputs(JsonNode outputs) {
        // 当前补丁只收紧已出现形状偏差的 BACKEND；其他角色保持冻结行为。
        if (packet.role() == FullStackRole.BACKEND) {
            validateBackendOutputs(outputs);
            return;
        }
        if (packet.role() != FullStackRole.FRONTEND) {
            return;
        }
        JsonNode files = outputs.path("files");
        List<String> expected =
                List.of("web/index.html", "web/styles.css", "web/app.js");
        // 前端 outputs 只允许 files，且文件集合必须与公开工作区 allowlist 完全一致。
        if (outputs.size() != 1 || !files.isObject() || files.size() != expected.size()) {
            throw new IllegalStateException("frontend output file set invalid");
        }
        for (String path : expected) {
            // 每个固定路径必须是非空字符串，禁止对象、数组或空内容进入工作区。
            if (!files.path(path).isTextual() || files.path(path).asText().isBlank()) {
                throw new IllegalStateException("frontend output content invalid");
            }
        }
        int contentBytes =
                expected.stream()
                        .mapToInt(
                                path ->
                                        files.path(path)
                                                .asText()
                                                .getBytes(StandardCharsets.UTF_8)
                                                .length)
                        .sum();
        // 三个前端文件合计限制为 20 KiB，避免大段说明或时间线挤占后续上下文。
        if (contentBytes > MAX_FRONTEND_CONTENT_BYTES) {
            throw new IllegalStateException("frontend output content exceeds 20 KiB");
        }
        // 文件数相同仍需逐键检查，拒绝替换为任意路径。
        files.fieldNames()
                .forEachRemaining(
                        path -> {
                            if (!expected.contains(path)) {
                                throw new IllegalStateException(
                                        "frontend output file set invalid");
                            }
                        });
    }

    private void validateBackendOutputs(JsonNode outputs) {
        // 两次后端调用都必须提交固定逻辑路径与源码内容。
        JsonNode file = outputs.path("file");
        if (!file.isObject()
                || file.size() != 2
                || !file.path("path").isTextual()
                || !BACKEND_FILE_PATH.equals(file.path("path").asText())
                || !file.path("content").isTextual()
                || file.path("content").asText().isBlank()
                || file.path("content").asText().getBytes(StandardCharsets.UTF_8).length
                        > MAX_BACKEND_CONTENT_BYTES) {
            throw new IllegalStateException("backend output file invalid");
        }
        if (packet.invocation() == 3) {
            // 初始缺陷制品只允许 file+fault，并验证已观察到的固定 fault 结构。
            if (outputs.size() != 2 || !validFault(outputs.path("fault"))) {
                throw new IllegalStateException("backend fault output invalid");
            }
            return;
        }
        if (packet.invocation() == 6) {
            // 修复制品只允许 file+repair，repair 必须是非空文本。
            if (outputs.size() != 2
                    || !outputs.path("repair").isTextual()
                    || outputs.path("repair").asText().isBlank()) {
                throw new IllegalStateException("backend repair output invalid");
            }
            return;
        }
        throw new IllegalStateException("backend invocation invalid");
    }

    private static boolean validFault(JsonNode fault) {
        // fault 仅接受本次已证实的字段集合和类型，拒绝扩展控制数据。
        if (!fault.isObject()
                || fault.size() != 6
                || !nonBlankText(fault, "description")
                || !nonBlankText(fault, "fix")
                || !nonBlankText(fault, "location")
                || !nonBlankText(fault, "method")
                || !nonBlankText(fault, "type")) {
            return false;
        }
        JsonNode example = fault.path("failingExample");
        // 失败向量的版本与解释为文本，实际/期望判断为布尔值。
        return example.isObject()
                && example.size() == 5
                && nonBlankText(example, "clientVersion")
                && nonBlankText(example, "minVersion")
                && nonBlankText(example, "explanation")
                && example.path("actualResult").isBoolean()
                && example.path("expectedResult").isBoolean();
    }

    private static boolean nonBlankText(JsonNode object, String field) {
        return object.path(field).isTextual() && !object.path(field).asText().isBlank();
    }
}
