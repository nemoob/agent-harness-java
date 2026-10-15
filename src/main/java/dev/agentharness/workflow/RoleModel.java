package dev.agentharness.workflow;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/** 单角色确定性模型，第一轮调用制品工具，第二轮返回该制品。 */
final class RoleModel implements Model {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final String role;
    private final String packetId;
    private final AtomicInteger calls = new AtomicInteger();

    RoleModel(String role, String packetId) {
        // 保存协调器确定的角色。
        this.role = role;
        // 模型只持有协调器已入库的 packetId。
        this.packetId = packetId;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // 记录真实模型 SPI 调用次数。
        int call = calls.incrementAndGet();
        // 第一轮确认 allowlist 后请求制品工具。
        if (call == 1) {
            boolean allowlisted =
                    tools.stream().anyMatch(tool -> "produce_role_artifact".equals(tool.getName()));
            if (!allowlisted) {
                throw new IllegalStateException("role artifact tool is not allowlisted");
            }
            // 由固定 JSON 库生成与 input 一致的原始参数。
            Map<String, Object> input = Map.of("packet_id", packetId);
            // 返回真实 ToolUseBlock，由 Toolkit 调用工具。
            ToolUseBlock use =
                    ToolUseBlock.builder()
                            .id("call-" + role + "-" + packetId)
                            .name("produce_role_artifact")
                            .input(input)
                            .content(toJson(input))
                            .build();
            return Flux.just(ChatResponse.builder().content(List.of(use)).build());
        }
        // 第二轮读取匹配的工具结果。
        String expectedId = "call-" + role + "-" + packetId;
        String artifact =
                messages.stream()
                        .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                        .filter(result -> expectedId.equals(result.getId()))
                        .filter(result -> "produce_role_artifact".equals(result.getName()))
                        .flatMap(result -> result.getOutput().stream())
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("role artifact result missing"));
        // 最终只返回文本制品，正常结束循环。
        return Flux.just(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(artifact).build()))
                        .build());
    }

    @Override
    public String getModelName() {
        // 角色名进入本地模型标识，便于轨迹区分。
        return "deterministic-role-" + role;
    }

    int calls() {
        // 返回真实模型调用次数。
        return calls.get();
    }

    private static String toJson(Object value) {
        try {
            // 使用 Jackson 生成工具原始参数 JSON。
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 固定参数序列化失败时停止角色运行。
            throw new IllegalStateException("cannot serialize role tool input", e);
        }
    }
}
