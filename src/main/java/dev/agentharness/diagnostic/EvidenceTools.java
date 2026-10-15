package dev.agentharness.diagnostic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.agentharness.state.LockedSession;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/** 公开教学用只读 fixture 工具白名单。 */
public final class EvidenceTools {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final LockedSession session;
    private final boolean missingRequestEvidence;

    public EvidenceTools(LockedSession session, boolean missingRequestEvidence) {
        // 保存持有文件锁的会话，使工具结果与调用计数同一事务落盘。
        this.session = session;
        // 保存教学场景开关，用于覆盖证据缺失分支。
        this.missingRequestEvidence = missingRequestEvidence;
    }

    @Tool(
            name = "query_effective_config",
            description = "Read the effective configuration from a local public fixture.",
            readOnly = true,
            concurrencySafe = false)
    public String queryEffectiveConfig(
            @ToolParam(name = "business_id", description = "Teaching business identifier")
                    String businessId) {
        // fixture 只接受公开教学任务，避免被当作任意查询接口。
        requireBusinessId(businessId);
        // 构造稳定证据，说明生效配置来自本地 fixture。
        FixtureEvidence evidence =
                new FixtureEvidence(
                        "ev-config-001",
                        "fixture:effective-config",
                        true,
                        businessId,
                        "feature.enabled=true");
        // 在返回工具结果前记录证据和调用次数。
        session.recordEvidence(
                "query_effective_config", evidence.evidenceId(), evidence.fact());
        // 使用固定 JSON 库序列化结构化工具输出。
        return toJson(evidence);
    }

    @Tool(
            name = "query_request_facts",
            description = "Read request facts from a local public fixture.",
            readOnly = true,
            concurrencySafe = false)
    public String queryRequestFacts(
            @ToolParam(name = "business_id", description = "Teaching business identifier")
                    String businessId) {
        // fixture 只接受公开教学任务，避免任意标识进入状态路径或日志。
        requireBusinessId(businessId);
        // 缺证据场景返回 present=false，而不是补造事实。
        FixtureEvidence evidence =
                missingRequestEvidence
                        ? new FixtureEvidence(
                                "ev-request-absent-001",
                                "fixture:request-facts",
                                false,
                                businessId,
                                "request evidence absent")
                        : new FixtureEvidence(
                                "ev-request-001",
                                "fixture:request-facts",
                                true,
                                businessId,
                                "request.channel=desktop");
        // 即使事实缺失，也记录已查询到的缺失证据和一次调用。
        session.recordEvidence("query_request_facts", evidence.evidenceId(), evidence.fact());
        // 使用 Jackson 返回固定类型 JSON。
        return toJson(evidence);
    }

    private static void requireBusinessId(String businessId) {
        // 当前公开示例只允许一个固定业务任务。
        if (!"CFG-001".equals(businessId)) {
            throw new IllegalArgumentException("unsupported teaching business id");
        }
    }

    private static String toJson(FixtureEvidence evidence) {
        try {
            // 序列化固定 record，避免手工拼接 JSON。
            return JSON.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            // 固定 record 序列化失败属于程序错误，立即中止当前工具。
            throw new IllegalStateException("cannot serialize fixture evidence", e);
        }
    }
}
