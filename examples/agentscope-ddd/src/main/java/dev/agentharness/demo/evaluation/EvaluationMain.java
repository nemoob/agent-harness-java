package dev.agentharness.demo.evaluation;

import dev.agentharness.demo.infra.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/** 只接受 DeepSeek 固定 30 试次；证据写到固定的公开路径。 */
public final class EvaluationMain {
    private EvaluationMain() {}

    public static void main(String[] args) {
        try {
            if (args.length < 1 || args.length > 2 || !"deepseek".equals(args[0])
                    || args.length == 2 && !"prepare".equals(args[1]))
                throw new IllegalArgumentException("仅支持 deepseek [prepare]");
            boolean prepareOnly = args.length == 2;
            Path root = Path.of(".").toRealPath();
            AppConfig config = new AppConfig(root, "deepseek", System.getenv());
            EvaluationRunner runner = new EvaluationRunner();
            List<EvaluationRunner.Plan> plans = runner.plans(config.model);
            Path evidenceDir = root.resolve("docs/evidence");
            Files.createDirectories(evidenceDir);
            Map<String, Object> manifest = manifest(root, config, plans);
            Path manifestFile = evidenceDir.resolve("evaluation-manifest.json");
            Path finalFile = evidenceDir.resolve("evaluation-results.json");
            // 准备阶段只冻结清单；正式阶段核对原清单，杜绝调参和覆盖结果。
            if (Files.exists(finalFile)) throw new IllegalStateException("正式评测证据已存在");
            if (prepareOnly) {
                if (Files.exists(manifestFile)) throw new IllegalStateException("冻结清单已存在");
                Evidence.write(manifestFile, manifest);
                System.out.println("evaluation manifest frozen: docs/evidence/evaluation-manifest.json");
                return;
            }
            if (!Files.exists(manifestFile)) throw new IllegalStateException("请先运行 prepare 冻结清单");
            manifest = Evidence.readFrozen(manifestFile, manifest);
            Map<String, Object> run = runner.run(plans, new LiveExecutor(config));
            Map<String, Object> finalResult = new LinkedHashMap<>();
            finalResult.put("run_id", manifest.get("run_id"));
            finalResult.put("recordKind", "final-source-run");
            finalResult.put("manifest", manifest);
            finalResult.putAll(run);
            Evidence.write(finalFile, finalResult);
            System.out.println("evaluation complete: " + run.get("executedTrials") + "/" + run.get("plannedTrials")
                    + ", stop=" + run.get("stopCondition") + ", evidence=docs/evidence/evaluation-results.json");
            if (!"completed".equals(run.get("stopCondition"))) System.exit(2);
        } catch (Exception error) {
            // 凭据、供应商响应和异常正文一律不进入终端或证据。
            System.err.println("evaluation setup failed: " + error.getClass().getSimpleName());
            System.exit(2);
        }
    }

    private static Map<String, Object> manifest(Path root, AppConfig config, List<EvaluationRunner.Plan> plans) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", UUID.randomUUID().toString());
        data.put("recordKind", "manifest");
        data.put("frozenAt", Instant.now().toString());
        data.put("provider", "deepseek");
        data.put("model", config.model);
        data.put("cases", List.of("2024-02-29:true", "2025-02-29:false", "1900-02-29:false",
                "2000-02-29:true", "2025-04-31:false"));
        data.put("userTaskTemplate", EvaluationRunner.USER_TASK_TEMPLATE);
        data.put("promptA", EvaluationRunner.PROMPT_A);
        data.put("promptB", EvaluationRunner.PROMPT_B);
        data.put("promptAHash", Evidence.sha256(EvaluationRunner.PROMPT_A));
        data.put("promptBHash", Evidence.sha256(EvaluationRunner.PROMPT_B));
        data.put("toolSchema", EvaluationRunner.TOOL_SCHEMA);
        data.put("toolSchemaHash", Evidence.sha256(EvaluationRunner.TOOL_SCHEMA));
        data.put("trialOrder", plans);
        data.put("hardRules", List.of("MODEL_STOP", "exactly one matching check_date", "tool result equals DateValidation",
                "entire trimmed answer exactly equals date=<date>, valid=<expected>"));
        data.put("semanticReviewBoundary", "规范字段外的任何文字均判失败并标 requires_review；自然语言含义需人工复核");
        data.put("maxAttemptsPerModelCall", EvaluationRunner.MAX_ATTEMPTS);
        data.put("maxIterationsPerTrial", config.maxIterations);
        data.put("requestTimeoutSeconds", Math.toIntExact(config.requestTimeout.toSeconds()));
        data.put("turnTimeoutSeconds", Math.toIntExact(config.turnTimeout.toSeconds()));
        data.put("maxTokensPerModelCall", config.maxTokens);
        data.put("stopConditions", List.of("30 trials completed including semantic/tool/max-iterations failures", "provider or setup/request-timeout failure"));
        data.put("stateSetup", "new Agent, Toolkit, RuntimeContext, InMemoryAgentStateStore per trial; no shared workspace tool");
        Map<String, String> hashes = new TreeMap<>();
        Path sourceRoot = root.resolve("src/main/java/dev/agentharness/demo/evaluation");
        try (Stream<Path> files = Files.list(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList())
                hashes.put(root.relativize(file).toString(), Evidence.sha256(file));
        }
        Path testRoot = root.resolve("src/test/java/dev/agentharness/demo/evaluation");
        try (Stream<Path> files = Files.list(testRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList())
                hashes.put(root.relativize(file).toString(), Evidence.sha256(file));
        }
        for (String name : List.of("src/main/java/dev/agentharness/demo/infra/AppConfig.java",
                "src/main/java/dev/agentharness/demo/domain/DateValidation.java",
                "config/application.properties", "pom.xml", "run-evaluation.sh"))
            hashes.put(name, Evidence.sha256(root.resolve(name)));
        data.put("sourceConfigScriptSha256", hashes);
        data.put("usagePolicy", "aggregate AgentScope 2.0.4 ModelCallEndEvent.getUsage per trial; unknown with reason if event or field absent; no zero substitution");
        data.put("costPolicy", "unknown without trusted usage and price snapshot");
        return data;
    }
}
