package dev.agentharness.demo.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import dev.agentharness.demo.infra.AppConfig;
import java.lang.reflect.Method;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.model.ChatUsage;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationRunnerTest {
    private final EvaluationRunner runner = new EvaluationRunner();

    @Test void actualManifestSurvivesWriteRead(@TempDir Path directory) throws Exception {
        Path root = Path.of(".").toRealPath();
        AppConfig config = new AppConfig(root, "deepseek", Map.of("DEEPSEEK_API_KEY", "offline-placeholder"));
        Method builder = EvaluationMain.class.getDeclaredMethod("manifest", Path.class, AppConfig.class, List.class);
        builder.setAccessible(true);
        Map<String, Object> frozen = (Map<String, Object>) builder.invoke(null, root, config, runner.plans(config.model));
        Path file = directory.resolve("manifest.json");
        Evidence.write(file, frozen);
        Map<String, Object> refreshed = (Map<String, Object>) builder.invoke(null, root, config, runner.plans(config.model));
        assertEquals(frozen.get("run_id"), Evidence.readFrozen(file, refreshed).get("run_id"));
    }

    @Test void frozenManifestRejectsSourceDrift(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("manifest.json");
        Map<String, Object> frozen = Map.of("run_id", "original", "frozenAt", "start", "sourceSha", "same");
        Evidence.write(file, frozen);
        assertEquals("original", Evidence.readFrozen(file,
                Map.of("run_id", "new", "frozenAt", "later", "sourceSha", "same")).get("run_id"));
        assertThrows(IllegalStateException.class, () -> Evidence.readFrozen(file,
                Map.of("run_id", "new", "frozenAt", "later", "sourceSha", "changed")));
    }

    @Test void freezesThirtyInterleavedTrialsAndKeepsAllFailures() {
        List<EvaluationRunner.Plan> plans = runner.plans("deepseek-flash");
        assertEquals(30, plans.size());
        assertEquals(EvaluationRunner.userTask(plans.get(0).date()), EvaluationRunner.userTask(plans.get(1).date()));
        assertFalse(EvaluationRunner.userTask(plans.get(0).date()).contains("valid=true"));
        assertEquals("01-A-r1", plans.get(0).trialId());
        assertEquals("01-B-r1", plans.get(1).trialId());
        assertEquals("01-B-r2", plans.get(2).trialId());
        assertEquals("01-A-r2", plans.get(3).trialId());
        assertEquals("05-B-r3", plans.get(29).trialId());
        Set<String> sessions = new HashSet<>();
        List<String> executionOrder = new ArrayList<>();
        Map<String, Object> run = runner.run(plans, plan -> {
            executionOrder.add(plan.trialId());
            String session = "fresh-" + plan.trialId();
            assertTrue(sessions.add(session));
            String answer = "date=" + plan.date() + ", valid=" + plan.expected();
            // 首次失败后仍执行后续试次；每个受控回答保留独立标识。
            if (plan.order() == 1) answer = "date=" + plan.date() + ", valid=" + !plan.expected();
            return new EvaluationRunner.Observation(answer, "MODEL_STOP", 2,
                    List.of(new EvaluationRunner.ToolCall(plan.date(), "date=" + plan.date() + ", valid=" + plan.expected())),
                    session, "store-" + plan.trialId());
        });
        assertEquals(plans.stream().map(EvaluationRunner.Plan::trialId).toList(), executionOrder);
        assertEquals(30, run.get("executedTrials"));
        assertEquals(List.of(), run.get("unexecutedTrialIds"));
        List<Map<String, Object>> trials = (List<Map<String, Object>>) run.get("trials");
        assertEquals("FAIL", trials.get(0).get("verdict"));
        assertEquals("PASS", trials.get(1).get("verdict"));
        assertTrue(trials.stream().allMatch(row -> row.get("usageStatus").equals("unknown") && row.get("costStatus").equals("unknown")));
        Map<String, Object> summary = (Map<String, Object>) run.get("summary");
        assertEquals(30, summary.get("trials"));
        assertEquals(29, summary.get("passes"));
        assertEquals(60, summary.get("modelCalls"));
        assertEquals(30, summary.get("toolCalls"));
    }

    @Test void rejectsMissingWrongAndRepeatedToolsAndWrongStop() {
        EvaluationRunner.Plan plan = runner.plans("deepseek-flash").get(0);
        String correct = "date=" + plan.date() + ", valid=" + plan.expected();
        assertFail(plan, new EvaluationRunner.Observation(correct, "MODEL_STOP", 1, List.of(), "s1", "t1"), "exactlyOneMatchingTool");
        assertFail(plan, new EvaluationRunner.Observation(correct, "MODEL_STOP", 1,
                List.of(new EvaluationRunner.ToolCall("2025-02-29", correct)), "s2", "t2"), "exactlyOneMatchingTool");
        assertFail(plan, new EvaluationRunner.Observation(correct, "MODEL_STOP", 1,
                List.of(new EvaluationRunner.ToolCall(plan.date(), "error=tool failed")), "s3", "t3"), "toolResultMatchesExpected");
        assertFail(plan, new EvaluationRunner.Observation(correct, "MODEL_STOP", 1,
                List.of(new EvaluationRunner.ToolCall(plan.date(), correct), new EvaluationRunner.ToolCall(plan.date(), correct)), "s4", "t4"), "exactlyOneMatchingTool");
        assertFail(plan, new EvaluationRunner.Observation(correct, "MODEL_STOP", 1,
                List.of(new EvaluationRunner.ToolCall(plan.date(), correct)), "s4a", "t4a",
                List.of(new EvaluationRunner.ToolAttempt("UNKNOWN_TOOL", plan.date())),
                EvaluationRunner.Usage.unknown("offline_fixture_absent")), "exactlyOneMatchingTool");
        assertFail(plan, new EvaluationRunner.Observation(correct, "MAX_ITERATIONS", 1,
                List.of(new EvaluationRunner.ToolCall(plan.date(), correct)), "s5", "t5"), "modelStop");
        assertFail(plan, new EvaluationRunner.Observation("答案：" + correct, "MODEL_STOP", 1,
                List.of(new EvaluationRunner.ToolCall(plan.date(), correct)), "s6a", "t6a"), "canonicalAnswer");
        assertFail(plan, new EvaluationRunner.Observation(correct + " " + correct, "MODEL_STOP", 1,
                List.of(new EvaluationRunner.ToolCall(plan.date(), correct)), "s6", "t6"), "canonicalAnswer");
    }

    @Test void maxIterationsFailsOneTrialButKeepsThirtyScheduledResults() {
        List<EvaluationRunner.Plan> plans = runner.plans("deepseek-flash");
        Map<String, Object> run = runner.run(plans, plan -> {
            String answer = "date=" + plan.date() + ", valid=" + plan.expected();
            String reason = plan.order() == 1 ? "MAX_ITERATIONS" : "MODEL_STOP";
            return new EvaluationRunner.Observation(answer, reason, 2,
                    List.of(new EvaluationRunner.ToolCall(plan.date(), answer)),
                    "session-" + plan.trialId(), "store-" + plan.trialId());
        });
        assertEquals(30, run.get("executedTrials"));
        assertEquals("completed", run.get("stopCondition"));
        assertEquals(1, ((Map<?, ?>) run.get("summary")).get("fails"));
    }

    @Test void collectsAvailableUsageAndExplainsMissingFields() {
        EvaluationTrace known = new EvaluationTrace();
        known.onModelCall(null, null, null, ignored -> Flux.just(new ModelCallEndEvent("reply", new ChatUsage(12, 4, 3, 2, 1, 5, 0.5))))
                .collectList().block();
        assertEquals(1, known.calls());
        assertEquals("known", known.usage().status());
        assertEquals(16, known.usage().totalTokens());
        assertEquals(3, known.usage().cachedTokens());
        assertEquals(2, known.usage().cacheCreationTokens());
        assertEquals(1, known.usage().reasoningTokens());
        assertEquals(5, known.usage().toolUsePromptTokens());
        assertEquals(0.5, known.usage().timeSeconds());
        EvaluationTrace missing = new EvaluationTrace();
        missing.onModelCall(null, null, null, ignored -> Flux.just(new ModelCallEndEvent("reply", null)))
                .collectList().block();
        assertEquals("unknown", missing.usage().status());
        assertEquals("response_field_absent", missing.usage().reason());
    }

    @Test void providerFailureStopsWithoutRerunAndMarksRemainingUnexecuted() {
        List<EvaluationRunner.Plan> plans = runner.plans("deepseek-flash");
        Map<String, Object> result = runner.run(plans, plan -> { throw new IllegalStateException("do not reveal provider body"); });
        assertEquals(1, result.get("executedTrials"));
        assertEquals(29, ((List<?>) result.get("unexecutedTrialIds")).size());
        Map<String, Object> first = ((List<Map<String, Object>>) result.get("trials")).get(0);
        assertEquals("IllegalStateException", first.get("errorType"));
        assertFalse(first.toString().contains("do not reveal"));
        assertEquals("unknown", first.get("usageStatus"));
    }

    private void assertFail(EvaluationRunner.Plan plan, EvaluationRunner.Observation observation, String reason) {
        Map<String, Object> result = runner.run(List.of(plan), ignored -> observation);
        Map<String, Object> row = ((List<Map<String, Object>>) result.get("trials")).get(0);
        assertEquals("FAIL", row.get("verdict"));
        assertTrue(((List<?>) row.get("failureReasons")).contains(reason));
    }
}
