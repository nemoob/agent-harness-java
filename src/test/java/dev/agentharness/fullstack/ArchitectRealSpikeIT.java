package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ArchitectRealSpikeIT {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void realArchitectUsesModelToolModelWithinTwoRequests() throws Exception {
        // 默认构建明确跳过真实网络；仅显式受控命令可开启。
        Assumptions.assumeTrue(Boolean.getBoolean("fullstack.real"));
        ModelBudget budget =
                new ModelBudget(2, 4_096, 60_000, Duration.ofMinutes(5), System::currentTimeMillis);
        AnthropicCurlTransport transport = AnthropicCurlTransport.fromTrustedEnvironment(budget);
        Path stateRoot =
                Path.of("target", "fullstack-real-spike", UUID.randomUUID().toString());
        FullStackTaskPacket packet =
                new FullStackTaskPacket(
                        "architect-spike",
                        "fullstack-case",
                        "real-spike",
                        1,
                        FullStackRole.ARCHITECT,
                        "grant-architect-spike",
                        List.of(),
                        "Submit a compact JSON object with exactly one top-level outputs object "
                                + "containing a summary string. Do not include role or invocation. "
                                + "Do not include credentials, endpoints, commands, or host paths.",
                        "{\"outputs\":{\"summary\":\"unused replay fixture\"}}");
        RoleAgentExecutor executor = new RoleAgentExecutor(stateRoot, budget, transport);

        RoleAgentExecutor.RoleExecution execution = executor.execute(packet, ModelMode.REAL);

        assertEquals(FullStackRole.ARCHITECT, execution.artifact().producerRole());
        assertEquals(2, execution.provenance().realModelRequests());
        assertEquals(2, budget.requests());
        assertTrue(budget.outputTokens() > 0);
        assertTrue(execution.provenance().toolResultMatched());
        assertTrue(execution.provenance().agentClosed());
        assertFalse(execution.provenance().promptSha256().isBlank());
        // 证据只保存计数与 hash，不保存 prompt、endpoint、密钥或绝对路径。
        Path evidence = Path.of("target", "fullstack-real-spike-evidence.json");
        Files.writeString(
                evidence,
                JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(
                                Map.of(
                                        "role",
                                        "ARCHITECT",
                                        "requests",
                                        budget.requests(),
                                        "inputTokens",
                                        budget.inputTokens(),
                                        "outputTokens",
                                        budget.outputTokens(),
                                        "promptSha256",
                                        execution.provenance().promptSha256(),
                                        "artifactSha256",
                                        execution.artifact().sha256(),
                                        "toolResultMatched",
                                        true,
                                        "agentClosed",
                                        true)));
    }
}
