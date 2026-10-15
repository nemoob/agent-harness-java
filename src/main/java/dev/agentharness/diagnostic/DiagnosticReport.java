package dev.agentharness.diagnostic;

import java.util.List;
import java.util.Map;

/** 诊断或恢复命令的可核验结果。 */
public record DiagnosticReport(
        String session,
        String status,
        String finalText,
        int modelCalls,
        Map<String, Integer> callCounts,
        List<String> toolSchemas,
        boolean toolResultIdsMatched,
        boolean finalContainsToolUse,
        long revision) {}
