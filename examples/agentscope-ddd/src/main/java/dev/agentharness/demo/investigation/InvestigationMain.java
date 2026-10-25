package dev.agentharness.demo.investigation;

import dev.agentharness.demo.infra.AgentScopeGateway;
import dev.agentharness.demo.infra.AppConfig;
import dev.agentharness.demo.infra.repair.RepairWorkspace;
import dev.agentharness.demo.domain.RepairProject;
import dev.agentharness.demo.dto.AssistantReply;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 固定教学练习入口；命令行只选择既有模型供应商。 */
public final class InvestigationMain {
    public static void main(String[] args) {
        int code = 1;
        InvestigationFlow.Result result = null;
        String provider = args.length == 0 ? "deepseek" : args[0];
        String model = "UNAVAILABLE";
        try {
            // 仅允许既有供应商，拒绝额外路径、提示词或可执行参数。
            if (args.length > 1 || !List.of("deepseek", "ollama").contains(provider))
                throw new IllegalArgumentException("只接受 deepseek/ollama 供应商参数");
            var config = new AppConfig(Path.of("."), provider, System.getenv());
            model = config.model;
            String task = readConfig("investigation-task.txt");
            String rulePrompt = readConfig("investigation-rule-prompt.txt");
            String implementationPrompt = readConfig("investigation-implementation-prompt.txt");
            String repairPrompt = readConfig("investigation-repair-prompt.txt");
            try (var project = new RepairWorkspace()) {
                // 每次工厂调用新建 Gateway，分别持有 Toolkit、RuntimeContext 和状态存储。
                result = new InvestigationFlow().run(project, task, rulePrompt, implementationPrompt,
                        repairPrompt, (prompt, toolkit) -> new AgentScopeGateway(config, prompt, toolkit));
                code = result.passed() ? 0 : 2;
                System.out.println("[调查汇总] " + (result.summary() == null ? "REJECTED" : result.summary().handoff()));
                System.out.println("[最终任务] " + (result.passed() ? "PASS" : "NOT_COMPLETED"));
            }
        } catch (Exception error) {
            // 供应商异常可能包含凭据或请求正文，控制台只输出本地状态。
            System.err.println("[调查任务] ERROR_OR_TIMEOUT，检查本地证据状态");
        }
        try {
            // 唯一自动证据文件名防止后一次尝试覆盖前一次记录。
            writeEvidence(safeProvider(provider), model, code, result);
        } catch (Exception error) {
            System.err.println("[调查证据] WRITE_FAILED");
            code = 1;
        }
        System.exit(code);
    }

    private static String readConfig(String name) throws Exception {
        // 配置名由程序固定传入；真实路径与大小限制防符号链接越界。
        Path base = Path.of("config").toRealPath();
        Path file = base.resolve(name).toRealPath();
        if (!file.startsWith(base) || Files.size(file) > 32768) throw new IllegalArgumentException("配置路径或大小错误");
        String value = Files.readString(file, StandardCharsets.UTF_8);
        if (value.isBlank()) throw new IllegalArgumentException("配置不能为空");
        return value;
    }

    static String safeProvider(String provider) {
        // 未校验的命令行值不能进入证据路径或文件名。
        return "deepseek".equals(provider) || "ollama".equals(provider) ? provider : "unknown";
    }

    private static void writeEvidence(String provider, String model, int code, InvestigationFlow.Result result) throws Exception {
        // 文件只保留固定任务材料与有界模型说明，不写环境变量或供应商异常正文。
        Path base = Path.of("docs/evidence");
        Files.createDirectories(base);
        String runId = System.currentTimeMillis() + "-" + java.util.UUID.randomUUID();
        String name = "investigation-" + provider + "-" + runId + ".json";
        Path file = base.resolve(name);
        StringBuilder json = new StringBuilder("{\n");
        field(json, "mode", "REAL");
        field(json, "recordKind", result == null ? "attempt" : "final-source-run");
        field(json, "runId", runId);
        field(json, "provider", provider);
        field(json, "model", model);
        field(json, "task", result == null ? "" : result.task());
        if (result != null) {
            field(json, "baseline", check(result.snapshot().baseline()));
            field(json, "ruleCalls", calls(result.ruleCalls()));
            field(json, "ruleReply", reply(result.ruleReply()));
            field(json, "implementationCalls", calls(result.implementationCalls()));
            field(json, "implementationReply", reply(result.implementationReply()));
            field(json, "summary", result.summary() == null ? "REJECTED" : result.summary().handoff());
            field(json, "repairEntered", String.valueOf(result.repairEntered()));
            field(json, "finalCheck", check(result.finalCheck()));
            field(json, "diff", result.diff());
        }
        field(json, "exitCode", String.valueOf(code));
        json.append("  \"sha256\": {\n");
        String[] files = {"src/main/java/dev/agentharness/demo/investigation/InvestigationTools.java",
                "src/main/java/dev/agentharness/demo/investigation/InvestigationFlow.java",
                "src/main/java/dev/agentharness/demo/investigation/InvestigationMain.java",
                "src/test/java/dev/agentharness/demo/investigation/InvestigationFlowTest.java",
                "src/main/java/dev/agentharness/demo/infra/AppConfig.java",
                "src/main/java/dev/agentharness/demo/infra/AgentScopeGateway.java",
                "src/main/java/dev/agentharness/demo/domain/DateValidation.java",
                "src/main/java/dev/agentharness/demo/app/RepairService.java",
                "src/main/java/dev/agentharness/demo/infra/repair/RepairWorkspace.java",
                "src/main/java/dev/agentharness/demo/infra/repair/RepairTools.java",
                "src/main/resources/repair/DateParser.template",
                "src/main/resources/repair/CheckDates.java.txt",
                "config/investigation-task.txt", "config/investigation-rule-prompt.txt",
                "config/investigation-implementation-prompt.txt", "config/investigation-repair-prompt.txt",
                "config/application.properties", "run-investigation.sh", "pom.xml"};
        for (int i = 0; i < files.length; i++) {
            byte[] bytes = Files.readAllBytes(Path.of(files[i]));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            json.append("    ").append(quote(files[i])).append(": ").append(quote(hash))
                    .append(i + 1 == files.length ? "\n" : ",\n");
        }
        json.append("  }\n}\n");
        Files.writeString(file, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("[调查证据] " + file);
    }

    private static String check(RepairProject.Check check) {
        if (check == null) return "UNAVAILABLE";
        return "passed=" + check.passed() + ", exit=" + check.exitCode() + ", output=" + bounded(check.output());
    }
    private static String calls(List<InvestigationTools.Call> calls) {
        var text = new StringBuilder();
        for (var call : calls) text.append(call.tool()).append('(').append(bounded(call.argument()))
                .append(") => ").append(bounded(call.result())).append('\n');
        return text.toString();
    }
    private static String reply(AssistantReply reply) {
        if (reply == null) return "UNAVAILABLE";
        return "reason=" + bounded(reply.reason()) + ", text=" + bounded(reply.text());
    }
    private static String bounded(String value) {
        if (value == null) return "null";
        return value.length() > 16000 ? value.substring(0, 16000) + " [TRUNCATED]" : value;
    }
    private static void field(StringBuilder json, String key, String value) {
        json.append("  ").append(quote(key)).append(": ").append(quote(value)).append(",\n");
    }
    private static String quote(String value) {
        // JSON 转义控制字符与方向控制字符，阻止模型内容伪造证据结构。
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029)
                out.append(String.format("\\u%04X", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
