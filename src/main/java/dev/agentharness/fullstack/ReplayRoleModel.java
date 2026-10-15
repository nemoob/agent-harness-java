package dev.agentharness.fullstack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/** 回放模式仍真实经过 AgentScope 工具闭环，但不会发起网络请求。 */
public final class ReplayRoleModel implements RoleLoopModel {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final FullStackTaskPacket packet;
    private final AtomicInteger calls = new AtomicInteger();
    private List<String> schemas = List.of();
    private boolean matched;

    public ReplayRoleModel(FullStackTaskPacket packet) {
        // 回放内容随协调器任务包冻结。
        this.packet = packet;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // 保存 AgentScope 真实传入的工具 allowlist。
        schemas = tools.stream().map(ToolSchema::getName).sorted().toList();
        int call = calls.incrementAndGet();
        // 第一轮只请求唯一允许的 submit_role_output 工具。
        if (call == 1) {
            if (!schemas.equals(List.of("submit_role_output"))) {
                throw new IllegalStateException("unexpected role tool allowlist");
            }
            Map<String, Object> input = Map.of("payload", packet.replayOutputJson());
            ToolUseBlock use =
                    ToolUseBlock.builder()
                            .id(callId())
                            .name("submit_role_output")
                            .input(input)
                            .content(toJson(input))
                            .build();
            return Flux.just(ChatResponse.builder().content(List.of(use)).build());
        }
        // 第二轮必须消费框架按相同 id/name 回填的真实 ToolResultBlock。
        String result = matchingToolResult(messages);
        matched = true;
        return Flux.just(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(result).build()))
                        .build());
    }

    @Override
    public String getModelName() {
        // 模型名明确标注回放，禁止证据误标为真实模型。
        return "fullstack-replay-" + packet.role().name().toLowerCase();
    }

    @Override
    public int calls() {
        return calls.get();
    }

    @Override
    public List<String> toolSchemas() {
        return schemas;
    }

    @Override
    public boolean toolResultMatched() {
        return matched;
    }

    private String matchingToolResult(List<Msg> messages) {
        // 只接受本角色当前调用的 id/name 组合，排除历史上下文结果。
        return messages.stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .filter(result -> callId().equals(result.getId()))
                .filter(result -> "submit_role_output".equals(result.getName()))
                .flatMap(result -> result.getOutput().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("matching tool result missing"));
    }

    private String callId() {
        // 工具调用 ID 同时绑定 packet 和角色调用序号。
        return "call-" + packet.packetId() + "-" + packet.invocation();
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot encode replay tool request", exception);
        }
    }
}
