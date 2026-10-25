package dev.agentharness.demo.evaluation;

import dev.agentharness.demo.domain.DateValidation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 固定五题、两版提示词与三次重复；所有试次从新状态执行。 */
public final class EvaluationRunner {
    public static final String PROMPT_A = "忠实执行用户任务，使用提供的工具取得事实，并按用户要求作答。";
    public static final String PROMPT_B = "必须且只调用一次 check_date 核验用户给出的日期，然后只输出 date=yyyy-MM-dd, valid=true/false，不要添加其他文字。";
    public static final String USER_TASK_TEMPLATE = "请调用 check_date 检查 %s，只输出 date=%s, valid=<true|false>。";
    public static final String TOOL_DESCRIPTION = "校验 yyyy-MM-dd 日期是否存在，年份 0001 到 9999";
    public static final String DATE_PARAM_DESCRIPTION = "完整日期，例如 2024-02-29";
    public static final String TOOL_SCHEMA = "name=check_date; description=" + TOOL_DESCRIPTION
            + "; readOnly=true; parameter=date:string; parameterDescription=" + DATE_PARAM_DESCRIPTION
            + "; result=date=yyyy-MM-dd, valid=true|false";
    public static final int MAX_ATTEMPTS = 1;
    private static final List<String> DATES = List.of("2024-02-29", "2025-02-29", "1900-02-29", "2000-02-29", "2025-04-31");
    private final DateValidation validation = new DateValidation();

    /** 假模型和真实模型共用一次性试次契约。 */
    public interface Executor {
        Observation execute(Plan plan) throws Exception;
    }

    public record Plan(String trialId, String date, boolean expected, String variant, int repeat, int order,
                       String model, String prompt, String promptHash, String toolSchemaHash) {}
    public record ToolCall(String date, String result) {}
    public record ToolAttempt(String name, String date) {}
    public record Usage(String status, String reason, Integer inputTokens, Integer outputTokens,
                        Integer cachedTokens, Integer cacheCreationTokens, Integer reasoningTokens,
                        Integer toolUsePromptTokens, Integer totalTokens, Double timeSeconds, String source) {
        public static Usage unknown(String reason) {
            return new Usage("unknown", reason, null, null, null, null, null, null, null, null,
                    "agentscope_2_0_4_ModelCallEndEvent.getUsage");
        }
    }
    public static final class TrialFailure extends RuntimeException {
        public final int modelCalls;
        public final List<ToolCall> toolCalls;
        public final List<ToolAttempt> toolAttempts;
        public final String sessionId, stateStoreId;
        public final Usage usage;
        public TrialFailure(int modelCalls, List<ToolCall> toolCalls, List<ToolAttempt> toolAttempts,
                            String sessionId, String stateStoreId, Usage usage) {
            super("provider or setup failure");
            this.modelCalls = modelCalls;
            this.toolCalls = toolCalls;
            this.toolAttempts = toolAttempts;
            this.sessionId = sessionId;
            this.stateStoreId = stateStoreId;
            this.usage = usage;
        }
    }
    public record Observation(String answer, String stopReason, int modelCalls, List<ToolCall> toolCalls,
                              String sessionId, String stateStoreId, List<ToolAttempt> toolAttempts, Usage usage) {
        public Observation(String answer, String stopReason, int modelCalls, List<ToolCall> toolCalls,
                           String sessionId, String stateStoreId) {
            this(answer, stopReason, modelCalls, toolCalls, sessionId, stateStoreId,
                    toolCalls.stream().map(call -> new ToolAttempt("check_date", call.date())).toList(),
                    Usage.unknown("offline_fixture_absent"));
        }
    }

    public static String userTask(String date) { return String.format(USER_TASK_TEMPLATE, date, date); }

    // 固定题目与交错顺序在运行前生成，奇数重复先 A，偶数重复先 B。
    public List<Plan> plans(String model) {
        List<Plan> plans = new ArrayList<>();
        for (String date : DATES) {
            boolean expected = validation.isValid(date);
            for (int repeat = 1; repeat <= 3; repeat++) {
                for (String variant : repeat % 2 == 1 ? List.of("A", "B") : List.of("B", "A")) {
                    String prompt = variant.equals("A") ? PROMPT_A : PROMPT_B;
                    plans.add(new Plan(String.format("%02d-%s-r%d", DATES.indexOf(date) + 1, variant, repeat),
                            date, expected, variant, repeat, plans.size() + 1, model, prompt,
                            Evidence.sha256(prompt), Evidence.sha256(TOOL_SCHEMA)));
                }
            }
        }
        return List.copyOf(plans);
    }

    // 一次失败只影响当前试次；执行器异常表明供应商或装配不可用，后续试次停止。
    public Map<String, Object> run(List<Plan> plans, Executor executor) {
        List<Map<String, Object>> trials = new ArrayList<>();
        String stopped = "completed";
        for (Plan plan : plans) {
            Instant start = Instant.now();
            long startedNanos = System.nanoTime();
            try {
                Observation observation = executor.execute(plan);
                trials.add(score(plan, observation, start, startedNanos));
            } catch (Exception error) {
                Map<String, Object> failed = base(plan, start, startedNanos);
                failed.put("stopReason", "PROVIDER_OR_SETUP_ERROR");
                failed.put("errorType", error instanceof TrialFailure ? "TrialFailure" : error.getClass().getSimpleName());
                failed.put("modelCalls", error instanceof TrialFailure failure ? failure.modelCalls : null);
                failed.put("toolCalls", error instanceof TrialFailure failure ? failure.toolCalls : List.of());
                failed.put("toolAttempts", error instanceof TrialFailure failure ? failure.toolAttempts : List.of());
                if (error instanceof TrialFailure failure) {
                    failed.put("sessionId", failure.sessionId);
                    failed.put("stateStoreId", failure.stateStoreId);
                }
                Usage failureUsage = error instanceof TrialFailure failure ? failure.usage : Usage.unknown("execution_failed_before_usage_capture");
                failed.put("usageStatus", failureUsage.status());
                failed.put("usageReason", failureUsage.reason());
                failed.put("usage", failureUsage);
                failed.put("answer", null);
                failed.put("semanticStatus", "requires_review");
                failed.put("checks", Map.of("modelStop", false, "exactlyOneMatchingTool", false,
                        "toolResultMatchesExpected", false, "canonicalAnswer", false));
                failed.put("verdict", "FAIL");
                failed.put("failureReasons", List.of("PROVIDER_OR_SETUP_ERROR"));
                trials.add(failed);
                stopped = "provider_or_setup_error";
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plannedTrials", plans.size());
        result.put("executedTrials", trials.size());
        result.put("unexecutedTrialIds", plans.subList(trials.size(), plans.size()).stream().map(Plan::trialId).toList());
        result.put("stopCondition", stopped);
        result.put("trials", trials);
        result.put("summary", summary(trials));
        return result;
    }

    // 硬判完整结束、唯一正确工具调用、工具事实和唯一规范答案，不用退出码代替质量。
    static Map<String, Object> score(Plan plan, Observation observation, Instant start, long startedNanos) {
        Map<String, Object> row = base(plan, start, startedNanos);
        List<ToolCall> calls = observation.toolCalls() == null ? List.of() : observation.toolCalls();
        boolean modelStop = "MODEL_STOP".equals(observation.stopReason());
        boolean oneTool = observation.toolAttempts().size() == 1 && calls.size() == 1
                && "check_date".equals(observation.toolAttempts().get(0).name())
                && plan.date().equals(observation.toolAttempts().get(0).date())
                && plan.date().equals(calls.get(0).date());
        boolean toolResult = oneTool && ("date=" + plan.date() + ", valid=" + plan.expected()).equals(calls.get(0).result());
        String bounded = Evidence.bounded(observation.answer());
        String expectedAnswer = "date=" + plan.date() + ", valid=" + plan.expected();
        boolean canonical = bounded != null && bounded.trim().equals(expectedAnswer);
        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("modelStop", modelStop);
        checks.put("exactlyOneMatchingTool", oneTool);
        checks.put("toolResultMatchesExpected", toolResult);
        checks.put("canonicalAnswer", canonical);
        List<String> failures = new ArrayList<>();
        checks.forEach((key, passed) -> { if (!passed) failures.add(key); });
        row.put("sessionId", observation.sessionId());
        row.put("stateStoreId", observation.stateStoreId());
        row.put("modelCalls", observation.modelCalls());
        row.put("toolAttempts", observation.toolAttempts());
        row.put("toolCalls", calls);
        row.put("usageStatus", observation.usage().status());
        row.put("usageReason", observation.usage().reason());
        row.put("usage", observation.usage());
        row.put("answer", bounded);
        row.put("semanticStatus", canonical ? "canonical_only" : "requires_review");
        row.put("stopReason", observation.stopReason());
        row.put("checks", checks);
        row.put("verdict", failures.isEmpty() ? "PASS" : "FAIL");
        row.put("failureReasons", failures);
        return row;
    }

    private static Map<String, Object> base(Plan plan, Instant start, long startedNanos) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("trialId", plan.trialId());
        row.put("order", plan.order());
        row.put("date", plan.date());
        row.put("question", userTask(plan.date()));
        row.put("expected", plan.expected());
        row.put("variant", plan.variant());
        row.put("repeat", plan.repeat());
        row.put("model", plan.model());
        row.put("promptHash", plan.promptHash());
        row.put("toolSchemaHash", plan.toolSchemaHash());
        row.put("maxAttempts", MAX_ATTEMPTS);
        row.put("startedAt", start.toString());
        row.put("endedAt", Instant.now().toString());
        row.put("elapsedMs", (System.nanoTime() - startedNanos) / 1_000_000L);
        row.put("usageStatus", "unknown");
        row.put("usageReason", "not_captured_yet");
        row.put("costStatus", "unknown");
        row.put("costReason", "price_snapshot_not_verified");
        return row;
    }

    private static Map<String, Object> summary(List<Map<String, Object>> trials) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Integer> byVariant = new LinkedHashMap<>();
        Map<String, Integer> byCase = new LinkedHashMap<>();
        Map<String, Integer> byFailure = new LinkedHashMap<>();
        int modelCalls = 0;
        int toolCalls = 0;
        int passes = 0;
        for (Map<String, Object> row : trials) {
            String variant = String.valueOf(row.get("variant"));
            String date = String.valueOf(row.get("date"));
            byVariant.merge(variant + ":" + row.get("verdict"), 1, Integer::sum);
            byCase.merge(date + ":" + row.get("verdict"), 1, Integer::sum);
            for (String failure : (List<String>) row.get("failureReasons")) byFailure.merge(failure, 1, Integer::sum);
            if (row.get("modelCalls") instanceof Number number) modelCalls += number.intValue();
            toolCalls += ((List<?>) row.get("toolAttempts")).size();
            if ("PASS".equals(row.get("verdict"))) passes++;
        }
        result.put("trials", trials.size());
        result.put("passes", passes);
        result.put("fails", trials.size() - passes);
        result.put("modelCalls", modelCalls);
        result.put("toolCalls", toolCalls);
        result.put("byVariant", byVariant);
        result.put("byCase", byCase);
        result.put("byFailureReason", byFailure);
        return result;
    }
}
