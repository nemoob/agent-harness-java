package dev.agentharness.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.agentharness.state.LockedSession;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** 只生成教学 JSON 制品，不修改业务代码。 */
public final class RoleArtifactTool {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final WorkflowStore workflowStore;
    private final LockedSession session;
    private final String action;
    private final String role;
    private final AtomicInteger calls = new AtomicInteger();

    public RoleArtifactTool(
            WorkflowStore workflowStore, LockedSession session, String action, String role) {
        // 保存只含本次运行 packet 与 artifact 的本地仓库。
        this.workflowStore = workflowStore;
        // 保存持锁会话以原子记录本角色制品。
        this.session = session;
        // 保存协调器生成的固定动作键用于去重。
        this.action = action;
        // 保存当前 Agent 的固定角色，禁止模型自称其他角色。
        this.role = role;
    }

    @Tool(
            name = "produce_role_artifact",
            description = "Produce one local teaching JSON artifact for the current role.",
            readOnly = false,
            concurrencySafe = false)
    public String produce(
            @ToolParam(name = "packet_id", description = "Existing local TaskPacket id")
                    String packetId) {
        // 统计实际工具调用次数。
        calls.incrementAndGet();
        // 工具必须从本地仓库读取完整 packet，不能相信模型补充字段。
        TaskPacket packet = workflowStore.packet(packetId);
        // 对角色、修订号、前置制品和证据来源执行交接校验。
        List<RoleArtifact> predecessors = validatePacket(packet);
        // 仅依据本地 packet 与前置制品推导新制品。
        ArtifactDecision decision = decide(packet, predecessors);
        // 构造稳定 artifactId，直接体现本地输入修订号。
        String artifactId = packet.sessionId() + "-artifact-" + (packet.inputRevision() + 1);
        // 构造稳定 evidenceId 关联本次实际制品。
        String evidenceId = "ev-" + artifactId;
        // 新证据列表继承 packet 已验证证据并追加本制品证据。
        List<String> evidenceIds =
                java.util.stream.Stream.concat(packet.evidenceIds().stream(), java.util.stream.Stream.of(evidenceId))
                        .distinct()
                        .toList();
        // 工具生成的制品保留完整 packet 与 lineage。
        RoleArtifact artifact =
                new RoleArtifact(
                        artifactId,
                        role,
                        packet.packetId(),
                        List.copyOf(packet.predecessorArtifactIds()),
                        evidenceIds,
                        decision.kind(),
                        decision.status(),
                        decision.payload(),
                        decision.returnTarget(),
                        0,
                        0);
        // 工具返回前先记录应用层证据。
        session.recordEvidence(action, evidenceId, decision.status() + ":" + decision.kind());
        // 应用证据落盘后才把制品加入本地交接仓库。
        workflowStore.putArtifact(artifact);
        try {
            // 使用 Jackson 序列化完整固定类型制品。
            return JSON.writeValueAsString(artifact);
        } catch (JsonProcessingException e) {
            // 制品序列化失败时停止交接。
            throw new IllegalStateException("cannot serialize role artifact", e);
        }
    }

    public int calls() {
        // 返回框架实际执行工具的次数。
        return calls.get();
    }

    RoleArtifact artifact(String artifactId) {
        // 协调器只读取工具已保存的真实制品。
        return workflowStore.artifact(artifactId);
    }

    private List<RoleArtifact> validatePacket(TaskPacket packet) {
        // packet 必须明确投递给当前固定角色。
        if (!role.equals(packet.targetRole())) {
            throw new IllegalStateException("packet targetRole mismatch");
        }
        // business/project/session 均采用公开 fixture 的固定约束。
        if (!"CFG-001".equals(packet.businessTaskId())
                || !"agent-harness-java".equals(packet.project())
                || packet.sessionId() == null
                || packet.sessionId().isBlank()) {
            throw new IllegalStateException("packet identity mismatch");
        }
        // 输入修订号必须等于本地已有制品数量。
        if (packet.inputRevision() != workflowStore.artifactCount()) {
            throw new IllegalStateException("packet revision mismatch");
        }
        // 初始诊断 packet 不需要前置制品。
        if (packet.inputRevision() == 0) {
            if (!packet.predecessorArtifactIds().isEmpty()
                    || !"coordinator".equals(packet.sourceRole())) {
                throw new IllegalStateException("invalid initial lineage");
            }
            return List.of();
        }
        // 非初始 packet 必须列出至少一个前置制品。
        if (packet.predecessorArtifactIds().isEmpty()) {
            throw new IllegalStateException("predecessor artifact required");
        }
        // 按 packet 顺序加载所有真实前置制品。
        List<RoleArtifact> predecessors =
                packet.predecessorArtifactIds().stream().map(workflowStore::artifact).toList();
        // sourceRole 必须等于最后一个前置制品的生产角色。
        RoleArtifact immediate = predecessors.get(predecessors.size() - 1);
        if (!packet.sourceRole().equals(immediate.producerRole())) {
            throw new IllegalStateException("sourceRole does not match immediate predecessor");
        }
        // packet 必须继承每个前置制品的所有证据 ID。
        boolean evidenceComplete =
                predecessors.stream()
                        .flatMap(artifact -> artifact.evidenceIds().stream())
                        .allMatch(packet.evidenceIds()::contains);
        if (!evidenceComplete) {
            throw new IllegalStateException("packet evidence lineage incomplete");
        }
        // 返回已经验证的真实前置制品。
        return predecessors;
    }

    private ArtifactDecision decide(TaskPacket packet, List<RoleArtifact> predecessors) {
        // 每个角色只接受明确允许动作。
        if (packet.allowedActions() == null || packet.allowedActions().isEmpty()) {
            throw new IllegalStateException("allowed action required");
        }
        // 诊断角色从固定公开 fixture 形成证据制品。
        if ("diagnosis".equals(role)) {
            requireAction(packet, "collect-fixture-evidence");
            return new ArtifactDecision(
                    "diagnostic-report", "EVIDENCE_READY", "config=enabled;request=present", null);
        }
        // 需求角色根据 unknowns 决定是否冻结，不能由调用参数预填结果。
        if ("requirements".equals(role)) {
            requireAction(packet, "define-requirement");
            if (!packet.unknowns().isEmpty()) {
                return new ArtifactDecision(
                        "acceptance-contract",
                        "PENDING_REQUIREMENT",
                        "unknowns=" + String.join(",", packet.unknowns()),
                        "requirements");
            }
            return new ArtifactDecision(
                    "acceptance-contract", "FROZEN", "expected-effective-config=enabled", null);
        }
        // 开发角色既可生成教学变更，也可消费测试失败做回流分析。
        if ("development".equals(role)) {
            if (packet.allowedActions().contains("analyze-test-failure")) {
                RoleArtifact failedTest = predecessors.get(predecessors.size() - 1);
                if (!"verification-result".equals(failedTest.kind())
                        || !"FAIL".equals(failedTest.status())
                        || !"development".equals(failedTest.returnTarget())) {
                    throw new IllegalStateException("development return requires failed test lineage");
                }
                return new ArtifactDecision(
                        "test-failure-analysis",
                        "RETURN",
                        "source=" + failedTest.artifactId() + ";case=effective-config-matches-frozen-requirement",
                        "development");
            }
            requireAction(packet, "create-teaching-change");
            if (!"FROZEN".equals(packet.requirementStatus())) {
                throw new IllegalStateException("development requires frozen requirement");
            }
            RoleArtifact requirement = predecessors.get(predecessors.size() - 1);
            if (!"acceptance-contract".equals(requirement.kind())
                    || !"FROZEN".equals(requirement.status())) {
                throw new IllegalStateException("frozen requirement artifact required");
            }
            String value =
                    packet.allowedActions().contains("fixture-change:enabled")
                            ? "enabled"
                            : packet.allowedActions().contains("fixture-change:disabled")
                                    ? "disabled"
                                    : null;
            if (value == null) {
                throw new IllegalStateException("teaching fixture change value required");
            }
            return new ArtifactDecision(
                    "teaching-change",
                    "CREATED",
                    "requirementArtifactId=" + requirement.artifactId() + ";effective-config=" + value,
                    null);
        }
        // 测试角色加载冻结需求与具体教学变更后实际比较 fixture。
        if ("testing".equals(role)) {
            requireAction(packet, "evaluate-teaching-fixture");
            if (predecessors.size() != 2) {
                throw new IllegalStateException("testing requires requirement and change artifacts");
            }
            RoleArtifact requirement = predecessors.get(0);
            RoleArtifact change = predecessors.get(1);
            if (!"FROZEN".equals(requirement.status())
                    || !"teaching-change".equals(change.kind())) {
                throw new IllegalStateException("testing lineage types mismatch");
            }
            String expected = valueAfter(requirement.payload(), "expected-effective-config=");
            String actual = valueAfter(change.payload(), "effective-config=");
            boolean passed = expected.equals(actual);
            return new ArtifactDecision(
                    "verification-result",
                    passed ? "PASS" : "FAIL",
                    "case=effective-config-matches-frozen-requirement;expected="
                            + expected
                            + ";actual="
                            + actual,
                    passed ? null : "development");
        }
        // 任何未登记角色都不允许生成制品。
        throw new IllegalStateException("unsupported role");
    }

    private static void requireAction(TaskPacket packet, String action) {
        // allowedActions 是角色工具的能力白名单。
        if (!packet.allowedActions().contains(action)) {
            throw new IllegalStateException("packet action not allowed: " + action);
        }
    }

    private static String valueAfter(String payload, String marker) {
        // 从本地工具生成的分号字段中提取指定值。
        return java.util.Arrays.stream(payload.split(";"))
                .filter(field -> field.startsWith(marker))
                .map(field -> field.substring(marker.length()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("artifact payload field missing"));
    }

    private record ArtifactDecision(
            String kind, String status, String payload, String returnTarget) {}
}
