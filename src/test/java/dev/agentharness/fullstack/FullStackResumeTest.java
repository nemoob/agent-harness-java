package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class FullStackResumeTest {
    @Test
    void existingRunRecoversArchitectAndMovesFrontendToFreshAttempt() throws Exception {
        Path stateRoot =
                Files.createDirectories(
                        Path.of("target", "fullstack-resume-test", UUID.randomUUID().toString()));
        Path requirement =
                Files.writeString(
                        stateRoot.resolve("requirement.txt"),
                        "Build the fixed local homepage recommendation preview.");
        FullStackRequest request =
                new FullStackRequest(
                        ModelMode.REAL, requirement, stateRoot, "resume-shape", 18181);
        FirstAttemptTransport firstTransport = new FirstAttemptTransport();
        FullStackService first =
                new FullStackService(
                        Path.of("target", "agent-harness-java.jar"),
                        ModelBudget.fullRunWithPriorRequests(7),
                        firstTransport);

        assertThrows(IllegalStateException.class, () -> first.run(request));
        assertEquals(2, firstTransport.calls(FullStackRole.ARCHITECT));
        assertEquals(1, firstTransport.calls(FullStackRole.FRONTEND));
        assertTrue(!firstTransport.frontendPrompt.contains("FAKE_TIMELINE_SENTINEL"));
        assertTrue(firstTransport.frontendPrompt.contains("GET /api/session"));
        assertTrue(firstTransport.frontendPrompt.contains("cookie plus X-CSRF-Token"));
        assertTrue(firstTransport.frontendPrompt.contains("REVISION_CONFLICT"));
        assertTrue(firstTransport.frontendPrompt.contains("responsive"));
        assertTrue(firstTransport.frontendPrompt.contains("accessibility"));

        FrontendOnlyFailureTransport secondTransport =
                new FrontendOnlyFailureTransport();
        FullStackService resumed =
                new FullStackService(
                        Path.of("target", "agent-harness-java.jar"),
                        ModelBudget.fullRunWithPriorRequests(10),
                        secondTransport);

        assertThrows(IllegalStateException.class, () -> resumed.run(request));
        assertEquals(0, secondTransport.architectCalls);
        assertEquals(1, secondTransport.frontendCalls);
        assertEquals(
                3,
                Files.list(stateRoot.resolve("agentscope/teaching-user")).count());

        Path runRoot = Files.list(stateRoot.resolve("fullstack-run")).findFirst().orElseThrow();
        JsonNode checkpoint =
                JsonMapper.builder()
                        .build()
                        .readTree(runRoot.resolve("checkpoint.json").toFile());
        assertEquals("resume-shape", checkpoint.path("session").asText());
        assertEquals("REAL", checkpoint.path("modelMode").asText());
        assertEquals("fixed-scaffold-v1", checkpoint.path("baseRevision").asText());
        assertEquals(2, checkpoint.path("nextInvocation").asInt());
        assertTrue(checkpoint.path("provenances").get(0).path("recovered").asBoolean());
        assertTrue(checkpoint.path("requirementSha256").asText().matches("[a-f0-9]{64}"));
        String checkpointText = Files.readString(runRoot.resolve("checkpoint.json"));
        assertTrue(!checkpointText.contains("CONTEXT_JSON_DATA"));
    }

    private static final class FirstAttemptTransport implements ModelTransport {
        private final Map<FullStackRole, Integer> calls = new ConcurrentHashMap<>();
        private String frontendPrompt = "";

        int calls(FullStackRole role) {
            return calls.getOrDefault(role, 0);
        }

        @Override
        public Reply complete(RolePrompt prompt) {
            int call = calls.merge(prompt.role(), 1, Integer::sum);
            if (prompt.role() == FullStackRole.FRONTEND) {
                frontendPrompt = prompt.userPrompt();
                throw new IllegalStateException("UNKNOWN_RESULT");
            }
            if (prompt.role() != FullStackRole.ARCHITECT) {
                throw new AssertionError("unexpected role before frontend failure");
            }
            if (call == 1) {
                String payload =
                        "{\"outputs\":{\"summary\":\"fixed architecture\","
                                + "\"plan\":[\"FAKE_TIMELINE_SENTINEL\"]}}";
                String input;
                try {
                    input =
                            JsonMapper.builder()
                                    .build()
                                    .writeValueAsString(Map.of("payload", payload));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return new Reply(
                        "preparing",
                        "tool-architect-resume",
                        "submit_role_output",
                        input,
                        new ModelUsage(10, 2));
            }
            return new Reply(
                    "accepted", null, null, null, new ModelUsage(10, 1));
        }

        @Override
        public String modelName() {
            return "trusted-model";
        }
    }

    private static final class FrontendOnlyFailureTransport implements ModelTransport {
        private int architectCalls;
        private int frontendCalls;

        @Override
        public Reply complete(RolePrompt prompt) {
            if (prompt.role() == FullStackRole.ARCHITECT) {
                architectCalls++;
                throw new AssertionError("architect must be recovered");
            }
            if (prompt.role() == FullStackRole.FRONTEND) {
                frontendCalls++;
                throw new IllegalStateException("UNKNOWN_RESULT");
            }
            throw new AssertionError("unexpected role");
        }

        @Override
        public String modelName() {
            return "trusted-model";
        }
    }
}
