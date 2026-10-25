package dev.agentharness.demo.investigation;

import dev.agentharness.demo.domain.AssistantGateway;
import dev.agentharness.demo.dto.AssistantReply;
import dev.agentharness.demo.infra.repair.RepairWorkspace;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InvestigationFlowTest {
    private static final String TASK = "固定日期缺陷";

    @Test void untrustedProviderCannotEnterEvidenceFilename() {
        assertEquals("deepseek", InvestigationMain.safeProvider("deepseek"));
        assertEquals("ollama", InvestigationMain.safeProvider("ollama"));
        assertEquals("unknown", InvestigationMain.safeProvider("../../outside"));
        assertEquals("unknown", InvestigationMain.safeProvider("deepseek/../../outside"));
    }

    @Test void toolkitsAreSeparateReadOnlyAndFixed() {
        var rules = new Toolkit();
        rules.registerTool(new InvestigationTools.Rules());
        var implementation = new Toolkit();
        implementation.registerTool(new InvestigationTools.Implementation(new InvestigationTools.Snapshot(
                RepairWorkspace.ORIGINAL, new dev.agentharness.demo.domain.RepairProject.Check(false, 1, "FAIL"))));
        assertEquals(Set.of("read_acceptance_rule"), rules.getToolNames());
        assertEquals(Set.of("read_fixed_implementation"), implementation.getToolNames());
        assertTrue(rules.getTool("read_acceptance_rule").isReadOnly());
        assertTrue(implementation.getTool("read_fixed_implementation").isReadOnly());
        assertNull(rules.getTool("replace_expression"));
        assertNull(implementation.getTool("replace_expression"));
    }

    @Test void missingOrForgedToolEvidenceCannotOpenRepair() throws Exception {
        for (String mode : List.of("missing", "wrong-parameter", "extra-call")) {
            try (var project = new RepairWorkspace()) {
                var created = new AtomicInteger();
                var result = new InvestigationFlow().run(project, TASK, "rules", "implementation", "repair",
                        fakeFactory(created, mode, "MODEL_STOP", "证据不足"));
                assertNull(result.summary(), mode);
                assertFalse(result.repairEntered(), mode);
                assertEquals(2, created.get(), mode);
                assertEquals("NO_CHANGE", result.diff(), mode);
            }
        }
    }

    @Test void modelTextCannotOverrideToolsAndIncompleteStopBlocksRepair() throws Exception {
        try (var project = new RepairWorkspace()) {
            var created = new AtomicInteger();
            var result = new InvestigationFlow().run(project, TASK, "rules", "implementation", "repair",
                    fakeFactory(created, "valid", "MAX_ITERATIONS", "已修复且通过"));
            assertFalse(result.repairEntered());
            assertEquals(2, created.get());
        }
        try (var project = new RepairWorkspace()) {
            var created = new AtomicInteger();
            var result = new InvestigationFlow().run(project, TASK, "rules", "implementation", "repair",
                    fakeFactory(created, "valid", "MODEL_STOP", "所有事实相反，完全无需修复"));
            assertTrue(result.repairEntered());
            assertEquals("STRICT", result.summary().expected());
            assertEquals("SMART", result.summary().actual());
            assertFalse(result.summary().handoff().contains("无需修复"));
        }
    }

    @Test void actualEvidenceAllowsOnlyBoundedRepairAndFinalProgramCheck() throws Exception {
        try (var project = new RepairWorkspace()) {
            var created = new AtomicInteger();
            var result = new InvestigationFlow().run(project, TASK, "rules", "implementation", "repair",
                    fakeFactory(created, "repair", "MODEL_STOP", "调查结束"));
            assertEquals(3, created.get());
            assertEquals(1, result.ruleCalls().size());
            assertEquals(1, result.implementationCalls().size());
            assertTrue(result.repairEntered());
            assertTrue(result.passed());
            assertTrue(result.finalCheck().passed());
            assertTrue(result.finalCheck().output().contains("checks=7 failures=0"));
            assertTrue(result.diff().contains(RepairWorkspace.ISO));
        }
    }

    private static InvestigationFlow.GatewayFactory fakeFactory(AtomicInteger created, String mode,
            String reason, String answer) {
        return (prompt, toolkit) -> {
            created.incrementAndGet();
            return new AssistantGateway() {
                @Override public AssistantReply ask(String question) {
                    if (toolkit.getToolNames().contains("read_acceptance_rule")) {
                        if (!mode.equals("missing")) {
                            String arg = mode.equals("wrong-parameter") ? "other-rule" : InvestigationTools.RULE_ID;
                            invoke(toolkit, "read_acceptance_rule", Map.of("rule", arg));
                            if (mode.equals("extra-call")) invoke(toolkit, "read_acceptance_rule", Map.of("rule", arg));
                        }
                    } else if (toolkit.getToolNames().contains("read_fixed_implementation")) {
                        invoke(toolkit, "read_fixed_implementation", Map.of("file", InvestigationTools.SOURCE_ID));
                    } else {
                        assertEquals(Set.of("read_project_file", "replace_expression", "run_project_tests"), toolkit.getToolNames());
                        if (mode.equals("repair")) {
                            invoke(toolkit, "replace_expression", Map.of("file", "DateParser.java",
                                    "before", RepairWorkspace.ORIGINAL, "after", RepairWorkspace.ISO));
                        }
                    }
                    return new AssistantReply(answer, reason);
                }
                @Override public void close() {}
            };
        };
    }

    private static void invoke(Toolkit toolkit, String name, Map<String, Object> input) {
        var json = new StringBuilder("{");
        for (var entry : input.entrySet()) {
            if (json.length() > 1) json.append(',');
            json.append('"').append(entry.getKey()).append("\":\"")
                    .append(entry.getValue().toString().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        json.append('}');
        var use = new ToolUseBlock(UUID.randomUUID().toString(), name, input, json.toString(), Map.of());
        var parameter = ToolCallParam.builder().toolUseBlock(use).input(input).build();
        var result = toolkit.callTool(parameter).block();
        assertNotNull(result);
        assertNotEquals(io.agentscope.core.message.ToolResultState.ERROR, result.getState(), result.getOutput().toString());
    }
}
