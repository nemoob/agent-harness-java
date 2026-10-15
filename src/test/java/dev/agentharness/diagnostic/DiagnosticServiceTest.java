package dev.agentharness.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiagnosticServiceTest {

    @Test
    void completesModelToolModelJourneyWithTwoReadOnlyFixtures() {
        Path root = uniqueRoot("diagnose-complete");
        DiagnosticReport report = new DiagnosticService(root).diagnose("diag-complete", false);

        assertEquals("COMPLETE", report.status());
        assertEquals(2, report.modelCalls());
        assertEquals(
                List.of("query_effective_config", "query_request_facts"),
                report.toolSchemas().stream().sorted().toList());
        assertEquals(Map.of("query_effective_config", 1, "query_request_facts", 1), report.callCounts());
        assertTrue(report.toolResultIdsMatched());
        assertFalse(report.finalContainsToolUse());
        assertTrue(report.finalText().contains("CFG-001"));
    }

    @Test
    void returnsInsufficientEvidenceWhenRequestFixtureIsAbsent() {
        Path root = uniqueRoot("diagnose-missing");
        DiagnosticReport report = new DiagnosticService(root).diagnose("diag-missing", true);

        assertEquals("INSUFFICIENT_EVIDENCE", report.status());
        assertEquals(1, report.callCounts().get("query_request_facts"));
        assertTrue(report.finalText().contains("INSUFFICIENT_EVIDENCE"));
    }

    @Test
    void resumeSkipsCompletedToolAction() {
        Path root = uniqueRoot("resume");
        DiagnosticService firstProcess = new DiagnosticService(root);
        DiagnosticReport paused = firstProcess.resume("resume-one", true);

        assertEquals("REQUEST_PENDING", paused.status());
        assertEquals(Map.of("query_effective_config", 1), paused.callCounts());

        DiagnosticService secondProcess = new DiagnosticService(root);
        DiagnosticReport completed = secondProcess.resume("resume-one", false);

        assertEquals("COMPLETE", completed.status());
        assertEquals(Map.of("query_effective_config", 1, "query_request_facts", 1), completed.callCounts());
        assertTrue(completed.finalText().contains("\"status\":\"COMPLETE\""));
    }

    private Path uniqueRoot(String prefix) {
        return Path.of("target", "test-state", prefix + "-" + UUID.randomUUID());
    }
}
