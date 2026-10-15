package dev.agentharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AgentScopeSpikeTest {

    @Test
    void executesRealToolLoopAndReturnsFinalText() {
        AtomicInteger toolCalls = new AtomicInteger();
        SpikeTools tools = new SpikeTools(toolCalls);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        SpikeModel model = new SpikeModel();

        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("spike")
                        .sysPrompt("Use the allowlisted evidence tool exactly once.")
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(2)
                        .build()) {
            Msg answer =
                    agent.call(
                                    List.of(new UserMessage("diagnose CFG-001")),
                                    RuntimeContext.builder()
                                            .userId("teaching-user")
                                            .sessionId("spike-session")
                                            .build())
                            .block();

            assertEquals(2, model.calls.get());
            assertEquals(1, toolCalls.get(), model.toolDiagnostics);
            assertEquals(List.of("read_fixture"), model.toolNames);
            assertTrue(model.matchedToolResult);
            assertEquals("evidence accepted", answer.getTextContent());
            assertFalse(answer.hasContentBlocks(ToolUseBlock.class));
        }
    }

    static final class SpikeModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();
        private List<String> toolNames = List.of();
        private List<ToolResultBlock> toolResults = List.of();
        private String toolDiagnostics = "";
        private boolean matchedToolResult;

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int call = calls.incrementAndGet();
            toolNames = tools.stream().map(ToolSchema::getName).sorted().toList();
            if (call == 1) {
                ToolUseBlock request =
                        ToolUseBlock.builder()
                                .id("call-1")
                                .name("read_fixture")
                                .input(Map.of("business_id", "CFG-001"))
                                .content("{\"business_id\":\"CFG-001\"}")
                                .build();
                return Flux.just(ChatResponse.builder().content(List.of(request)).build());
            }
            toolResults =
                    messages.stream()
                            .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                            .toList();
            toolDiagnostics =
                    toolResults.stream()
                            .map(
                                    result ->
                                            result.getState()
                                                    + ":"
                                                    + result.getOutput().stream()
                                                            .filter(TextBlock.class::isInstance)
                                                            .map(TextBlock.class::cast)
                                                            .map(TextBlock::getText)
                                                            .toList())
                            .toList()
                            .toString();
            matchedToolResult =
                    toolResults.stream()
                            .anyMatch(
                                    result ->
                                            "call-1".equals(result.getId())
                                                    && "read_fixture".equals(result.getName()));
            TextBlock answer = TextBlock.builder().text("evidence accepted").build();
            return Flux.just(ChatResponse.builder().content(List.of(answer)).build());
        }

        @Override
        public String getModelName() {
            return "deterministic-spike";
        }
    }

    public static final class SpikeTools {
        private final AtomicInteger calls;

        SpikeTools(AtomicInteger calls) {
            this.calls = calls;
        }

        @Tool(
                name = "read_fixture",
                description = "Read one public teaching fixture.",
                readOnly = true)
        public String readFixture(
                @ToolParam(name = "business_id", description = "Teaching business identifier")
                        String businessId) {
            calls.incrementAndGet();
            return "evidence:fixture-001;business:" + businessId;
        }
    }
}
