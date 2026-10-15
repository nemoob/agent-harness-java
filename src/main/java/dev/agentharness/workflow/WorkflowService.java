package dev.agentharness.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.agentharness.state.AppStateStore;
import dev.agentharness.state.LockedSession;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 以确定性门槛协调四个各自拥有 ReActAgent 的教学角色。 */
public final class WorkflowService {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final AppStateStore store;
    private final WorkflowStore workflowStore = new WorkflowStore();

    public WorkflowService(Path stateRoot) {
        // 复用同一安全状态根和应用层会话锁。
        this.store = new AppStateStore(stateRoot);
    }

    public WorkflowReport run(String sessionId, WorkflowScenario scenario) {
        // 保存有序路由，作为门槛交接证据。
        List<String> route = new ArrayList<>();
        // 保存每个角色真实 Agent 循环生成的制品。
        List<RoleArtifact> artifacts = new ArrayList<>();
        // 初始诊断 packet 不依赖前置制品。
        TaskPacket diagnosisPacket =
                packet(
                        sessionId,
                        "packet-1-diagnosis",
                        "coordinator",
                        "diagnosis",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("collect-fixture-evidence"),
                        "UNKNOWN");
        // 诊断角色必须先产出真实 fixture 证据。
        RoleArtifact diagnosis = runRole(diagnosisPacket, route, 1);
        artifacts.add(diagnosis);
        // 待确认场景将 unknowns 明确交给需求角色。
        List<String> requirementUnknowns =
                scenario == WorkflowScenario.REQUIREMENT_PENDING
                        ? List.of("unknown-owner")
                        : List.of();
        // 需求 packet 继承诊断 artifact 和证据。
        TaskPacket requirementPacket =
                packet(
                        sessionId,
                        "packet-2-requirements",
                        "diagnosis",
                        "requirements",
                        List.of(diagnosis.artifactId()),
                        diagnosis.evidenceIds(),
                        requirementUnknowns,
                        List.of("define-requirement"),
                        "DRAFT");
        // 需求角色通过自身 Agent 循环计算冻结状态。
        RoleArtifact requirement = runRole(requirementPacket, route, 2);
        artifacts.add(requirement);
        // 未冻结需求立即停止，不生成开发或测试制品。
        if (!"FROZEN".equals(requirement.status())) {
            return new WorkflowReport(
                    sessionId, "PENDING_REQUIREMENT", List.copyOf(route), List.copyOf(artifacts));
        }
        // 场景只选择固定教学变更输入，不预填测试结果。
        String changeAction =
                scenario == WorkflowScenario.INCORRECT_CHANGE
                        ? "fixture-change:disabled"
                        : "fixture-change:enabled";
        // 开发 packet 只引用已冻结的需求 artifact。
        TaskPacket developmentPacket =
                packet(
                        sessionId,
                        "packet-3-development",
                        "requirements",
                        "development",
                        List.of(requirement.artifactId()),
                        requirement.evidenceIds(),
                        List.of(),
                        List.of("create-teaching-change", changeAction),
                        "FROZEN");
        // 开发角色仅生成教学 JSON 变更制品。
        RoleArtifact development = runRole(developmentPacket, route, 3);
        artifacts.add(development);
        // 测试 packet 同时引用冻结需求与具体教学变更。
        TaskPacket testingPacket =
                packet(
                        sessionId,
                        "packet-4-testing",
                        "development",
                        "testing",
                        List.of(requirement.artifactId(), development.artifactId()),
                        development.evidenceIds(),
                        List.of(),
                        List.of("evaluate-teaching-fixture"),
                        "FROZEN");
        // 测试角色实际比较公开 fixture 的期望值和变更值。
        RoleArtifact testing = runRole(testingPacket, route, 4);
        artifacts.add(testing);
        // 通过真实求值后工作流完成。
        if ("PASS".equals(testing.status())) {
            return new WorkflowReport(
                    sessionId, "COMPLETE", List.copyOf(route), List.copyOf(artifacts));
        }
        // 失败类型由测试制品决定回流目标，本场景为开发实现不匹配。
        String returnTarget = testing.returnTarget();
        // 回流 packet 带齐需求、变更、测试三段 lineage 和具体测试证据。
        TaskPacket returnPacket =
                packet(
                        sessionId,
                        "packet-5-return",
                        "testing",
                        returnTarget,
                        List.of(
                                requirement.artifactId(),
                                development.artifactId(),
                                testing.artifactId()),
                        testing.evidenceIds(),
                        List.of(),
                        List.of("analyze-test-failure"),
                        "FROZEN");
        // 目标角色通过自身 Agent 循环生成回流分析制品。
        artifacts.add(runRole(returnPacket, route, 5));
        // 返回不可变路由和制品列表。
        return new WorkflowReport(
                sessionId,
                "RETURNED_TO_" + returnTarget.toUpperCase(java.util.Locale.ROOT),
                List.copyOf(route),
                List.copyOf(artifacts));
    }

    private RoleArtifact runRole(TaskPacket packet, List<String> route, int sequence) {
        // 协调器先把完整 packet 放入本地仓库，再把 packetId 交给 Agent。
        workflowStore.putPacket(packet);
        // 为每个角色调用构造独立会话槽位，避免上下文串扰。
        String roleSession = packet.sessionId() + "-" + packet.targetRole() + "-" + sequence;
        // 应用层动作键同时包含顺序和 packetId。
        String action = "role-" + sequence + "-" + packet.packetId();
        // 每个角色调用持有自身文件锁。
        try (LockedSession session = store.lock(roleSession)) {
            // 记录角色待生成的教学制品。
            session.markPending(action, "ROLE_RUNNING");
            // Toolkit 只注册 JSON 制品工具。
            Toolkit toolkit = new Toolkit();
            RoleArtifactTool tool =
                    new RoleArtifactTool(
                            workflowStore, session, action, packet.targetRole());
            toolkit.registerTool(tool);
            // 每个角色使用自己的确定性 Model 实例。
            RoleModel model = new RoleModel(packet.targetRole(), packet.packetId());
            // 每个角色的 AgentScope 内部状态按会话保存。
            JsonFileAgentStateStore agentStateStore =
                    new JsonFileAgentStateStore(
                            store.agentStateRoot("teaching-user", roleSession));
            // 角色 Agent 在调用结束后显式关闭。
            try (ReActAgent agent =
                    ReActAgent.builder()
                            .name(packet.targetRole() + "-agent")
                            .sysPrompt("Produce only the requested local teaching JSON artifact.")
                            .model(model)
                            .toolkit(toolkit)
                            .maxIters(2)
                            .stateStore(agentStateStore)
                            .defaultSessionId(roleSession)
                            .build()) {
                // 运行上下文使用角色独立会话。
                RuntimeContext context =
                        RuntimeContext.builder()
                                .userId("teaching-user")
                                .sessionId(roleSession)
                                .build();
                // 紧邻调用前复核角色会话目录和全部固定 leaf/temp 文件。
                store.verifyAgentStateSlot("teaching-user", roleSession);
                Msg answer;
                try {
                    // 触发真实模型→工具→模型循环。
                    answer =
                            agent.call(
                                            List.of(
                                                    new UserMessage(
                                                            "consume TaskPacket "
                                                                    + toJson(packet))),
                                            context)
                                    .block();
                } finally {
                    // Agent 调用结束后立即复核框架没有留下链接或非普通固定文件。
                    store.verifyAgentStateSlot("teaching-user", roleSession);
                }
                // 空最终消息表示门槛失败。
                if (answer == null) {
                    throw new IllegalStateException("role agent returned no artifact");
                }
                // 解析固定 JSON 制品字段。
                RoleArtifact artifact = parse(answer.getTextContent());
                // 将制品动作标记完成并持久化。
                session.complete(action, "ROLE_COMPLETE");
                // 只有 Agent 成功后才推进协调器路由。
                route.add(packet.targetRole());
                // 返回附带真实调用次数的制品。
                return new RoleArtifact(
                        artifact.artifactId(),
                        artifact.producerRole(),
                        artifact.inputPacketId(),
                        artifact.predecessorArtifactIds(),
                        artifact.evidenceIds(),
                        artifact.kind(),
                        artifact.status(),
                        artifact.payload(),
                        artifact.returnTarget(),
                        model.calls(),
                        tool.calls());
            }
        }
    }

    private TaskPacket packet(
            String sessionId,
            String packetId,
            String sourceRole,
            String targetRole,
            List<String> predecessorArtifactIds,
            List<String> evidenceIds,
            List<String> unknowns,
            List<String> allowedActions,
            String requirementStatus) {
        // inputRevision 由当前本地 artifact 数量生成并由工具复核。
        return new TaskPacket(
                packetId,
                "CFG-001",
                "agent-harness-java",
                sessionId,
                workflowStore.artifactCount(),
                sourceRole,
                targetRole,
                List.copyOf(predecessorArtifactIds),
                List.copyOf(evidenceIds),
                List.copyOf(unknowns),
                List.copyOf(allowedActions),
                requirementStatus);
    }

    private static RoleArtifact parse(String json) {
        try {
            // AgentScope 将 String 工具结果编码为 JSON 字符串时先安全解包一层。
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(json);
            // 文本节点内容仍必须是固定本地工具生成的 JSON。
            String artifactJson = node.isTextual() ? node.asText() : node.toString();
            // 仅解析固定本地工具生成的完整制品类型。
            return JSON.readValue(artifactJson, RoleArtifact.class);
        } catch (JsonProcessingException e) {
            // 无法解析的制品不得通过角色门槛。
            throw new IllegalStateException("invalid role artifact", e);
        }
    }

    private static String toJson(TaskPacket packet) {
        try {
            // Agent 输入携带完整结构化 packet 字段以便轨迹审阅。
            return JSON.writeValueAsString(packet);
        } catch (JsonProcessingException e) {
            // 固定 packet 无法序列化时停止角色调用。
            throw new IllegalStateException("invalid task packet", e);
        }
    }
}
