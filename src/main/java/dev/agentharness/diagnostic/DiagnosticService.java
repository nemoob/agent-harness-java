package dev.agentharness.diagnostic;

import dev.agentharness.state.AppState;
import dev.agentharness.state.AppStateStore;
import dev.agentharness.state.LockedSession;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 诊断与跨进程恢复旅程的协调器。 */
public final class DiagnosticService {
    private static final String CONFIG = "query_effective_config";
    private static final String REQUEST = "query_request_facts";
    private final AppStateStore store;

    public DiagnosticService(Path stateRoot) {
        // 应用状态仓库负责边界、原子写和文件锁。
        this.store = new AppStateStore(stateRoot);
    }

    public DiagnosticReport diagnose(String sessionId, boolean missingRequestEvidence) {
        // 单次诊断在一个持锁会话内执行两项只读工具。
        try (LockedSession session = store.lock(sessionId)) {
            // 已完成诊断直接返回状态，不重复工具。
            if (session.isCompleted(CONFIG) && session.isCompleted(REQUEST)) {
                return snapshot(session.state(), "already complete", 0, List.of(), true, false);
            }
            // 记录本次 ReAct 循环的两个待执行动作。
            session.markPending(CONFIG, "DIAGNOSING");
            session.markPending(REQUEST, "DIAGNOSING");
            // 运行真实 AgentScope 模型→工具→模型闭环。
            LoopResult loop =
                    runLoop(
                            sessionId,
                            session,
                            List.of(CONFIG, REQUEST),
                            missingRequestEvidence,
                            "diagnostic-agent");
            // 两个工具均返回后登记应用层完成动作。
            session.complete(CONFIG, "DIAGNOSING");
            // 根据模型结论选择最终阶段。
            String phase =
                    loop.finalText().contains("INSUFFICIENT_EVIDENCE")
                            ? "INSUFFICIENT_EVIDENCE"
                            : "COMPLETE";
            // 最后完成请求事实动作并持久化阶段。
            session.complete(REQUEST, phase);
            // 返回带调用链证据的稳定报告。
            return snapshot(
                    session.state(),
                    loop.finalText(),
                    loop.model().calls(),
                    loop.model().toolSchemas(),
                    loop.model().resultIdsMatched(),
                    loop.finalContainsToolUse());
        }
    }

    public DiagnosticReport resume(String sessionId, boolean stopAfterConfig) {
        // 每次命令持有同一会话文件锁，支持独立 Java 进程串行恢复。
        try (LockedSession session = store.lock(sessionId)) {
            // 保存本进程实际执行过的模型调用和 schema。
            List<String> schemas = new ArrayList<>();
            int modelCalls = 0;
            boolean matched = true;
            boolean finalHasToolUse = false;
            String finalText = "no action required";
            // 配置动作尚未完成时，通过独立 ReAct 循环执行。
            if (!session.isCompleted(CONFIG)) {
                session.markPending(CONFIG, "CONFIG_PENDING");
                LoopResult config =
                        runLoop(
                                sessionId,
                                session,
                                List.of(CONFIG),
                                false,
                                "resume-agent");
                session.complete(CONFIG, "REQUEST_PENDING");
                modelCalls += config.model().calls();
                schemas = config.model().toolSchemas();
                matched = config.model().resultIdsMatched();
                finalHasToolUse = config.finalContainsToolUse();
                finalText = config.finalText();
            }
            // 显式演示断点在配置动作完成后返回。
            if (stopAfterConfig) {
                return snapshot(
                        session.state(),
                        finalText,
                        modelCalls,
                        schemas,
                        matched,
                        finalHasToolUse);
            }
            // 已完成配置时只执行剩余请求事实动作。
            if (!session.isCompleted(REQUEST)) {
                session.markPending(REQUEST, "REQUEST_PENDING");
                LoopResult request =
                        runLoop(
                                sessionId,
                                session,
                                List.of(REQUEST),
                                false,
                                "resume-agent");
                session.complete(REQUEST, "COMPLETE");
                modelCalls += request.model().calls();
                schemas = request.model().toolSchemas();
                matched = matched && request.model().resultIdsMatched();
                finalHasToolUse = finalHasToolUse || request.finalContainsToolUse();
                finalText = request.finalText();
            }
            // 返回最终跨进程恢复状态和持久化计数。
            return snapshot(
                    session.state(),
                    finalText,
                    modelCalls,
                    schemas,
                    matched,
                    finalHasToolUse);
        }
    }

    private LoopResult runLoop(
            String sessionId,
            LockedSession session,
            List<String> actions,
            boolean missingRequestEvidence,
            String agentName) {
        // Toolkit 只注册两个公开只读 fixture 工具。
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EvidenceTools(session, missingRequestEvidence));
        // 确定性模型仅能选择协调器给出的动作。
        DeterministicModel model = new DeterministicModel(actions);
        // AgentScope 内部上下文单独保存到 agentscope 子目录。
        JsonFileAgentStateStore agentStateStore =
                new JsonFileAgentStateStore(
                        store.agentStateRoot("teaching-user", sessionId));
        // 使用 try-with-resources 保证 Agent 从关闭管理器解绑。
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name(agentName)
                        .sysPrompt("Use only the registered read-only teaching fixture tools.")
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(2)
                        .stateStore(agentStateStore)
                        .defaultSessionId(sessionId)
                        .build()) {
            // RuntimeContext 明确携带同一用户与会话槽位。
            RuntimeContext context =
                    RuntimeContext.builder()
                            .userId("teaching-user")
                            .sessionId(sessionId)
                            .build();
            // 紧邻调用前复核框架目录和全部固定 leaf/temp 文件。
            store.verifyAgentStateSlot("teaching-user", sessionId);
            Msg answer;
            try {
                // 触发真实 ReActAgent 调用，框架负责 Toolkit 执行和结果回填。
                answer = agent.call(List.of(new UserMessage("diagnose CFG-001")), context).block();
            } finally {
                // 调用返回或失败后立即复核，拒绝接受框架期间出现的链接或非普通文件。
                store.verifyAgentStateSlot("teaching-user", sessionId);
            }
            // block 正常完成必须得到最终消息。
            if (answer == null) {
                throw new IllegalStateException("agent returned no final message");
            }
            // 返回模型、最终文本和停止形态供验证。
            return new LoopResult(
                    model,
                    answer.getTextContent(),
                    answer.hasContentBlocks(ToolUseBlock.class));
        }
    }

    private static DiagnosticReport snapshot(
            AppState state,
            String finalText,
            int modelCalls,
            List<String> schemas,
            boolean matched,
            boolean finalContainsToolUse) {
        // 将不可变应用状态映射为公开命令结果。
        return new DiagnosticReport(
                state.session(),
                state.phase(),
                finalText,
                modelCalls,
                state.callCounts(),
                schemas,
                matched,
                finalContainsToolUse,
                state.revision());
    }

    private record LoopResult(
            DeterministicModel model, String finalText, boolean finalContainsToolUse) {}
}
