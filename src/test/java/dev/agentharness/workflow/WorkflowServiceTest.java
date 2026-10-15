package dev.agentharness.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentharness.state.AppStateStore;
import dev.agentharness.state.LockedSession;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import io.agentscope.core.tool.Tool;
import org.junit.jupiter.api.Test;

class WorkflowServiceTest {

    @Test
    void artifactToolDeclaresItsStateWrite() throws NoSuchMethodException {
        Tool annotation =
                RoleArtifactTool.class
                        .getMethod("produce", String.class)
                        .getAnnotation(Tool.class);

        assertTrue(annotation != null && !annotation.readOnly());
    }

    @Test
    void completeWorkflowCarriesVerifiedLineageAndPassesEvaluatedFixture() {
        WorkflowReport report =
                new WorkflowService(uniqueRoot("workflow-pass"))
                        .run("workflow-pass", WorkflowScenario.PASS);

        assertEquals("COMPLETE", report.status());
        assertEquals(List.of("diagnosis", "requirements", "development", "testing"), report.route());
        assertEquals(4, report.artifacts().size());
        assertTrue(report.artifacts().stream().allMatch(artifact -> artifact.modelCalls() == 2));
        assertTrue(report.artifacts().stream().allMatch(artifact -> artifact.toolCalls() == 1));
        RoleArtifact requirement = report.artifacts().get(1);
        RoleArtifact development = report.artifacts().get(2);
        RoleArtifact testing = report.artifacts().get(3);
        assertEquals(List.of(requirement.artifactId()), development.predecessorArtifactIds());
        assertEquals(
                List.of(requirement.artifactId(), development.artifactId()),
                testing.predecessorArtifactIds());
        assertTrue(testing.evidenceIds().containsAll(development.evidenceIds()));
        assertEquals("PASS", testing.status());
        assertTrue(testing.payload().contains("expected=enabled;actual=enabled"));
    }

    @Test
    void missingOrWrongLineageIdsAreRejectedByRoleTool() {
        Path root = uniqueRoot("workflow-invalid-lineage");
        WorkflowStore workflowStore = new WorkflowStore();
        TaskPacket invalid =
                new TaskPacket(
                        "packet-invalid",
                        "CFG-001",
                        "agent-harness-java",
                        "invalid-lineage",
                        0,
                        "requirements",
                        "development",
                        List.of("missing-artifact"),
                        List.of("ev-missing"),
                        List.of(),
                        List.of("create-teaching-change", "fixture-change:enabled"),
                        "FROZEN");
        workflowStore.putPacket(invalid);
        AppStateStore appStateStore = new AppStateStore(root);

        try (LockedSession session = appStateStore.lock("invalid-lineage-development")) {
            RoleArtifactTool tool =
                    new RoleArtifactTool(
                            workflowStore, session, "role-1-development", "development");
            assertThrows(IllegalArgumentException.class, () -> tool.produce("missing-packet"));
            assertThrows(IllegalStateException.class, () -> tool.produce("packet-invalid"));
        }
    }

    @Test
    void pendingRequirementStopsBeforeDevelopmentAndTesting() {
        WorkflowReport report =
                new WorkflowService(uniqueRoot("workflow-pending"))
                        .run("workflow-pending", WorkflowScenario.REQUIREMENT_PENDING);

        assertEquals("PENDING_REQUIREMENT", report.status());
        assertEquals(List.of("diagnosis", "requirements"), report.route());
        assertEquals(2, report.artifacts().size());
        assertEquals("PENDING_REQUIREMENT", report.artifacts().get(1).status());
        assertTrue(report.artifacts().get(1).payload().contains("unknown-owner"));
    }

    @Test
    void incorrectTeachingChangeFailsRealFixtureAndReturnsToDevelopment() {
        WorkflowReport report =
                new WorkflowService(uniqueRoot("workflow-fail"))
                        .run("workflow-fail", WorkflowScenario.INCORRECT_CHANGE);

        assertEquals("RETURNED_TO_DEVELOPMENT", report.status());
        assertEquals(
                List.of("diagnosis", "requirements", "development", "testing", "development"),
                report.route());
        RoleArtifact testing = report.artifacts().get(3);
        RoleArtifact returned = report.artifacts().get(4);
        assertEquals("FAIL", testing.status());
        assertEquals("development", testing.returnTarget());
        assertTrue(testing.payload().contains("case=effective-config-matches-frozen-requirement"));
        assertTrue(testing.payload().contains("expected=enabled;actual=disabled"));
        assertEquals("test-failure-analysis", returned.kind());
        assertTrue(returned.predecessorArtifactIds().contains(testing.artifactId()));
        assertTrue(returned.evidenceIds().containsAll(testing.evidenceIds()));
    }

    private Path uniqueRoot(String prefix) {
        return Path.of("target", "test-state", prefix + "-" + UUID.randomUUID());
    }
}
