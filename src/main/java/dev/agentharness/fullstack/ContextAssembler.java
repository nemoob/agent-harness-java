package dev.agentharness.fullstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将已验证制品与有界运行证据组装成角色可消费的结构化 JSON 数据块。 */
public final class ContextAssembler {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ARTIFACT_BYTES = 16_384;
    private static final int MAX_DYNAMIC_BYTES = 24_576;
    private static final int MAX_STDERR_BYTES = 8_192;
    private final Map<String, FullStackArtifact> artifacts;

    public ContextAssembler(List<FullStackArtifact> sourceArtifacts) {
        Map<String, FullStackArtifact> indexed = new LinkedHashMap<>();
        // 构造时索引服务端已接受制品，重复 ID 直接拒绝。
        for (FullStackArtifact artifact : List.copyOf(sourceArtifacts)) {
            if (indexed.putIfAbsent(artifact.artifactId(), artifact) != null) {
                throw new IllegalArgumentException("duplicate context artifact id");
            }
        }
        this.artifacts = Map.copyOf(indexed);
    }

    public AssembledContext assemble(
            FullStackRole role,
            int invocation,
            List<String> requiredArtifactIds,
            Map<String, ?> fixedContract,
            List<EvidenceBlock> evidenceBlocks) {
        ObjectNode root = JSON.createObjectNode();
        // 顶层固定标记帮助模型区分角色与不可覆盖的服务端契约。
        root.put("role", role.name());
        root.put("invocation", invocation);
        // 固定契约与 evidence 共用递归键规范化，避免 Map 插入序改变 context/prompt SHA。
        root.set(
                "fixedContract",
                canonicalize(JSON.valueToTree(Map.copyOf(fixedContract))));
        ArrayNode verified = root.putArray("verifiedArtifacts");
        int dynamicBytes = 0;
        List<String> includedArtifacts = new ArrayList<>();
        for (String artifactId : List.copyOf(requiredArtifactIds)) {
            FullStackArtifact artifact = artifacts.get(artifactId);
            if (artifact == null) {
                throw new IllegalStateException("required context artifact missing");
            }
            // 紧邻读取时复算 payload SHA，拒绝内存或状态篡改。
            if (!sha256(artifact.payloadJson()).equals(artifact.sha256())) {
                throw new IllegalStateException("context artifact hash mismatch");
            }
            ObjectNode content = contextContent(role, artifact);
            String contentJson;
            try {
                // 投影后再次规范化，确保 compact context 的字节与 SHA 跨 JVM 稳定。
                contentJson = JSON.writeValueAsString(canonicalize(content));
            } catch (Exception exception) {
                throw new IllegalStateException("context artifact cannot be encoded", exception);
            }
            byte[] bytes = contentJson.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_ARTIFACT_BYTES) {
                throw new IllegalStateException("context artifact exceeds 16 KiB");
            }
            dynamicBytes += bytes.length;
            ObjectNode block = verified.addObject();
            block.put("classification", "VERIFIED_ARTIFACT");
            block.put("artifactId", artifact.artifactId());
            block.put("revision", artifact.invocation());
            block.put("sha256", artifact.sha256());
            block.put("contentSha256", sha256(contentJson));
            block.set("content", parseObject(contentJson, "artifact"));
            includedArtifacts.add(artifact.artifactId());
        }
        ArrayNode evidence = root.putArray("evidence");
        List<String> includedEvidence = new ArrayList<>();
        for (EvidenceBlock source : List.copyOf(evidenceBlocks)) {
            // 运行证据同样复算 canonical content hash，不能只信元数据。
            if (!sha256(source.contentJson()).equals(source.sha256())) {
                throw new IllegalStateException("context evidence hash mismatch");
            }
            byte[] bytes = source.contentJson().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_ARTIFACT_BYTES) {
                throw new IllegalStateException("context evidence exceeds 16 KiB");
            }
            dynamicBytes += bytes.length;
            ObjectNode block = evidence.addObject();
            block.put("classification", "UNTRUSTED_EVIDENCE");
            block.put("artifactId", source.artifactId());
            block.put("revision", source.revision());
            block.put("sha256", source.sha256());
            block.set("content", parseObject(source.contentJson(), "evidence"));
            includedEvidence.add(source.artifactId());
        }
        // 必需动态上下文超限时完整失败，不截断制品或省略前序。
        if (dynamicBytes > MAX_DYNAMIC_BYTES) {
            throw new IllegalStateException("dynamic context exceeds 24 KiB");
        }
        try {
            String json = JSON.writeValueAsString(root);
            return new AssembledContext(
                    json,
                    sha256(json),
                    includedArtifacts,
                    includedEvidence,
                    dynamicBytes);
        } catch (Exception exception) {
            throw new IllegalStateException("context cannot be encoded", exception);
        }
    }

    public EvidenceBlock runnerEvidence(
            String artifactId,
            int revision,
            String candidateSha256,
            String sourceSha256,
            String runnerCase,
            int exitCode,
            boolean expected,
            boolean actual,
            String stderr) {
        ObjectNode content = JSON.createObjectNode();
        // 候选、源码、case 与 expected/actual 都来自确定性 runner，不信任角色自报。
        content.put("candidateSha256", candidateSha256);
        content.put("sourceSha256", sourceSha256);
        content.put("runnerCase", runnerCase);
        content.put("exitCode", exitCode);
        content.put("expected", expected);
        content.put("actual", actual);
        String raw = stderr == null ? "" : stderr;
        byte[] rawBytes = raw.getBytes(StandardCharsets.UTF_8);
        ObjectNode stderrNode = content.putObject("stderrSummary");
        // 原始 stderr hash 与 source artifactId 保留可追溯引用。
        stderrNode.put("originalReference", artifactId);
        stderrNode.put("originalSha256", sha256(raw));
        if (rawBytes.length > MAX_STDERR_BYTES) {
            String prefix = new String(rawBytes, 0, MAX_STDERR_BYTES, StandardCharsets.UTF_8);
            stderrNode.put("text", sanitize(prefix));
            stderrNode.put("truncated", true);
        } else {
            stderrNode.put("text", sanitize(raw));
            stderrNode.put("truncated", false);
        }
        return evidence(artifactId, revision, content);
    }

    public EvidenceBlock evidence(String artifactId, int revision, Map<String, ?> content) {
        // 普通结构化证据先由 Jackson 转为对象树再规范化。
        JsonNode value = JSON.valueToTree(Map.copyOf(content));
        return evidence(artifactId, revision, value);
    }

    private EvidenceBlock evidence(String artifactId, int revision, JsonNode content) {
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("evidence artifact id invalid");
        }
        if (revision <= 0 || !content.isObject()) {
            throw new IllegalArgumentException("evidence revision/content invalid");
        }
        try {
            // 递归规范化对象键，确保不同 Map 插入序得到相同证据字节与 SHA。
            String canonical = JSON.writeValueAsString(canonicalize(content));
            return new EvidenceBlock(artifactId, revision, sha256(canonical), canonical);
        } catch (Exception exception) {
            throw new IllegalStateException("evidence cannot be encoded", exception);
        }
    }

    private static JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode ordered = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            // 先收集当前对象的全部字段，避免依赖原始 Map 的迭代顺序。
            value.fieldNames().forEachRemaining(names::add);
            // Java 字符串词典序为所有进程提供确定性的 Unicode 键顺序。
            names.sort(String::compareTo);
            for (String name : names) {
                // 嵌套对象继续排序，字段值类型保持 JsonNode 原类型。
                ordered.set(name, canonicalize(value.get(name)));
            }
            return ordered;
        }
        if (value.isArray()) {
            ArrayNode ordered = JSON.createArrayNode();
            for (JsonNode element : value) {
                // 数组保持原有顺序，仅规范化数组元素内部的对象键。
                ordered.add(canonicalize(element));
            }
            return ordered;
        }
        // 标量节点直接复用，避免改变数字、布尔值或字符串类型。
        return value;
    }

    private static ObjectNode contextContent(FullStackRole targetRole, FullStackArtifact artifact) {
        ObjectNode source = parseObject(artifact.payloadJson(), "artifact");
        // FRONTEND 只接收架构摘要；阶段计划、时间线等非必要字段不进入 prompt。
        if (targetRole == FullStackRole.FRONTEND
                && artifact.producerRole() == FullStackRole.ARCHITECT) {
            JsonNode summary = source.path("outputs").path("summary");
            if (summary.isMissingNode() || summary.isNull()) {
                throw new IllegalStateException("frontend architecture summary missing");
            }
            ObjectNode projection = JSON.createObjectNode();
            projection.putObject("outputs").set("summary", summary);
            return projection;
        }
        return source;
    }

    private static ObjectNode parseObject(String json, String label) {
        try {
            JsonNode value = JSON.readTree(json);
            if (!value.isObject()) {
                throw new IllegalStateException(label + " context must be an object");
            }
            return (ObjectNode) value;
        } catch (Exception exception) {
            throw new IllegalStateException(label + " context JSON invalid", exception);
        }
    }

    private static String sanitize(String value) {
        // 有界 stderr 摘要删除常见秘密赋值与本机绝对路径。
        return value.replaceAll(
                        "(?i)(api[_-]?key|token|secret)\\s*[:=]\\s*\\S+",
                        "$1=<redacted>")
                .replaceAll("/Users/[^\\s]+", "<local-path>");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record EvidenceBlock(
            String artifactId, int revision, String sha256, String contentJson) {}

    public record AssembledContext(
            String json,
            String sha256,
            List<String> artifactIds,
            List<String> evidenceIds,
            int dynamicBytes) {
        public AssembledContext {
            // ID 列表保存为不可变快照供脱敏 provenance 使用。
            artifactIds = List.copyOf(artifactIds);
            evidenceIds = List.copyOf(evidenceIds);
        }
    }
}
