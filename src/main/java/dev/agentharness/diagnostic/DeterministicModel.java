package dev.agentharness.diagnostic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/** 仅按固定 fixture 和已见 ToolResultBlock 决策的本地模型。 */
public final class DeterministicModel implements Model {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final List<String> requestedActions;
    private final AtomicInteger calls = new AtomicInteger();
    private List<String> toolSchemas = List.of();
    private boolean resultIdsMatched;

    public DeterministicModel(List<String> requestedActions) {
        // 复制协调器给出的固定动作列表，阻止运行中修改。
        this.requestedActions = List.copyOf(requestedActions);
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // 记录实际进入模型 SPI 的调用次数。
        int call = calls.incrementAndGet();
        // 保存框架生成的工具 schema 名称供验证。
        toolSchemas = tools.stream().map(ToolSchema::getName).sorted().toList();
        // 第一轮只生成白名单工具调用。
        if (call == 1) {
            return Flux.just(toolRequest());
        }
        // 第二轮只读取框架回填的 ToolResultBlock。
        return Flux.just(finalAnswer(messages));
    }

    @Override
    public String getModelName() {
        // 返回稳定模型名，明确这是本地确定性实现。
        return "deterministic-fixture-model";
    }

    public int calls() {
        // 暴露模型调用次数用于教学证据。
        return calls.get();
    }

    public List<String> toolSchemas() {
        // 返回框架实际传入的工具 schema 名称。
        return toolSchemas;
    }

    public boolean resultIdsMatched() {
        // 返回第二轮是否看到了所有匹配 id/name 的工具结果。
        return resultIdsMatched;
    }

    private ChatResponse toolRequest() {
        // 按固定动作顺序创建工具调用，便于审阅追踪。
        List<io.agentscope.core.message.ContentBlock> blocks = new ArrayList<>();
        // 每个动作都必须出现在 Toolkit 生成的 schema 中。
        requestedActions.forEach(
                action -> {
                    if (!toolSchemas.contains(action)) {
                        throw new IllegalStateException("tool is not allowlisted: " + action);
                    }
                    // 使用固定教学参数形成输入 Map。
                    Map<String, Object> input = Map.of("business_id", "CFG-001");
                    // v2.0.4 参数校验读取 content，因此由 Jackson生成同一 JSON。
                    String content = toJson(input);
                    // 为每个动作生成稳定、可配对的 call id。
                    blocks.add(
                            ToolUseBlock.builder()
                                    .id("call-" + action)
                                    .name(action)
                                    .input(input)
                                    .content(content)
                                    .build());
                });
        // 返回真实 ToolUseBlock 响应交给 ReActAgent 执行。
        return ChatResponse.builder().content(blocks).build();
    }

    private ChatResponse finalAnswer(List<Msg> messages) {
        // 从框架第二轮输入提取全部 ToolResultBlock。
        List<ToolResultBlock> results =
                messages.stream()
                        .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();
        // 要求每个请求都能按 id 与 name 对上工具结果。
        resultIdsMatched =
                requestedActions.stream()
                        .allMatch(
                                action ->
                                        results.stream()
                                                .anyMatch(
                                                        result ->
                                                                ("call-" + action)
                                                                                .equals(
                                                                                        result
                                                                                                .getId())
                                                                        && action.equals(
                                                                                result.getName())));
        // 只收集本轮请求 action 的匹配结果，排除恢复上下文中的历史 ToolResult。
        List<FixtureEvidence> evidence =
                requestedActions.stream()
                        .map(
                                action ->
                                        results.stream()
                                                .filter(
                                                        result ->
                                                                ("call-" + action)
                                                                                .equals(result.getId())
                                                                        && action.equals(result.getName()))
                                                .flatMap(result -> result.getOutput().stream())
                                                .filter(TextBlock.class::isInstance)
                                                .map(TextBlock.class::cast)
                                                .map(TextBlock::getText)
                                                .map(DeterministicModel::parseEvidence)
                                                .findFirst()
                                                .orElse(
                                                        new FixtureEvidence(
                                                                "missing-tool-result",
                                                                "agent:tool-result",
                                                                false,
                                                                "CFG-001",
                                                                "matching tool result absent")))
                        .toList();
        // 任何错误、缺失或 present=false 都不能给出完整诊断。
        boolean sufficient =
                resultIdsMatched
                        && evidence.size() == requestedActions.size()
                        && evidence.stream().allMatch(FixtureEvidence::present);
        // 用确定性状态表达最终结论。
        String status = sufficient ? "COMPLETE" : "INSUFFICIENT_EVIDENCE";
        // 输出字段固定，便于 CLI 和测试读取。
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("status", status);
        answer.put("businessTaskId", "CFG-001");
        answer.put("evidence", evidence);
        // 最终响应只有 TextBlock，明确结束 ReAct 循环。
        TextBlock text = TextBlock.builder().text(toJson(answer)).build();
        // 返回真实最终模型响应，不再包含 ToolUseBlock。
        return ChatResponse.builder().content(List.of(text)).build();
    }

    private static FixtureEvidence parseEvidence(String text) {
        try {
            // AgentScope 把 String 工具返回值编码为 JSON 字符串时先安全解包一层。
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(text);
            // 文本节点内容仍必须是固定 FixtureEvidence JSON。
            String evidenceJson = node.isTextual() ? node.asText() : node.toString();
            // 仅反序列化固定 record，不启用多态类型。
            return JSON.readValue(evidenceJson, FixtureEvidence.class);
        } catch (JsonProcessingException e) {
            // 工具错误文本转换为明确的缺失证据记录。
            return new FixtureEvidence(
                    "invalid-tool-result",
                    "agent:tool-result",
                    false,
                    "CFG-001",
                    "unparseable tool result");
        }
    }

    private static String toJson(Object value) {
        try {
            // 由固定 JSON 库生成内容，避免手工拼接。
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 固定数据结构序列化失败时停止模型响应。
            throw new IllegalStateException("cannot serialize deterministic response", e);
        }
    }
}
