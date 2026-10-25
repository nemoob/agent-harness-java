package dev.agentharness.demo.investigation;

import dev.agentharness.demo.app.RepairService;
import dev.agentharness.demo.domain.AssistantGateway;
import dev.agentharness.demo.domain.RepairProject;
import dev.agentharness.demo.dto.AssistantReply;
import dev.agentharness.demo.infra.repair.RepairTools;
import dev.agentharness.demo.infra.repair.RepairWorkspace;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.ArrayList;

/** 程序先验收调查工具事实，才创建有修改能力的修复 Agent。 */
public final class InvestigationFlow {
    @FunctionalInterface public interface GatewayFactory {
        AssistantGateway create(String prompt, Toolkit toolkit);
    }
    public record Summary(String expected, String actual, boolean baselineFail, boolean conflict) {
        public String handoff() {
            // 交接只包含程序生成的固定字段，绝不拼接模型回答。
            return "expected=" + expected + "\nactual=" + actual
                    + "\nbaseline=FAIL\nconflict=" + conflict
                    + "\nallowedFile=DateParser.java\nallowedBefore=" + RepairWorkspace.ORIGINAL;
        }
    }
    public record Result(String task, InvestigationTools.Snapshot snapshot,
            List<InvestigationTools.Call> ruleCalls, AssistantReply ruleReply,
            List<InvestigationTools.Call> implementationCalls, AssistantReply implementationReply,
            Summary summary, boolean repairEntered, boolean passed, RepairProject.Check finalCheck, String diff) {}

    public Result run(RepairWorkspace project, String task, String rulePrompt, String implementationPrompt,
            String repairPrompt, GatewayFactory factory) {
        // 先执行可信基线，冻结唯一源码表达式；工具不接触活项目。
        RepairProject.Check baseline = project.test();
        String source = project.read(InvestigationTools.SOURCE_ID);
        if (baseline.passed() || baseline.exitCode() == 0 || !source.contains(RepairWorkspace.ORIGINAL)
                || !baseline.output().contains("FAIL 2025-02-29")
                || !baseline.output().contains("FAIL 1900-02-29")
                || !baseline.output().contains("FAIL 2025-04-31"))
            throw new IllegalStateException("固定练习基线不符合预期");
        var snapshot = new InvestigationTools.Snapshot(RepairWorkspace.ORIGINAL, baseline);

        // 两套 Toolkit 分别只注册自身只读工具，Gateway 每次新建独立 Agent 与会话。
        var rules = new InvestigationTools.Rules();
        var ruleToolkit = new Toolkit();
        ruleToolkit.registerTool(rules);
        AssistantReply ruleReply = ask(factory, rulePrompt, ruleToolkit,
                "请调用 read_acceptance_rule，参数 rule=strict-date-rule。任务：" + task);
        var implementation = new InvestigationTools.Implementation(snapshot);
        var implementationToolkit = new Toolkit();
        implementationToolkit.registerTool(implementation);
        AssistantReply implementationReply = ask(factory, implementationPrompt, implementationToolkit,
                "请调用 read_fixed_implementation，参数 file=DateParser.java。任务：" + task);

        // 工具参数、结果和两次正常停止同时吻合，才把事实转为确定性冲突结论。
        List<InvestigationTools.Call> ruleCalls = rules.calls();
        List<InvestigationTools.Call> implementationCalls = implementation.calls();
        boolean accepted = valid(ruleReply) && valid(implementationReply)
                && ruleCalls.equals(List.of(new InvestigationTools.Call("read_acceptance_rule",
                        InvestigationTools.RULE_ID, InvestigationTools.RULE)))
                && implementationCalls.equals(List.of(new InvestigationTools.Call("read_fixed_implementation",
                        InvestigationTools.SOURCE_ID, expectedImplementation(snapshot))));
        if (!accepted) return new Result(task, snapshot, ruleCalls, ruleReply, implementationCalls,
                implementationReply, null, false, false, null, project.diff());
        var summary = new Summary("STRICT", "SMART", true, true);

        // 只有冲突成立才装配 RepairTools；现有服务继续负责最终编译测试与 diff 验收。
        var recording = new RecordingProject(project);
        var repairToolkit = new Toolkit();
        repairToolkit.registerTool(new RepairTools(recording));
        boolean passed;
        try (var repair = factory.create(repairPrompt, repairToolkit)) {
            passed = new RepairService().run(repair, recording, summary.handoff());
        }
        return new Result(task, snapshot, ruleCalls, ruleReply, implementationCalls,
                implementationReply, summary, true, passed, recording.lastCheck(), project.diff());
    }

    private static AssistantReply ask(GatewayFactory factory, String prompt, Toolkit toolkit, String question) {
        // 关闭当前 Agent 后再装配下一位，避免会话或工具历史相互复用。
        try (var gateway = factory.create(prompt, toolkit)) { return gateway.ask(question); }
    }
    private static boolean valid(AssistantReply reply) {
        // 模型文本不能替代工具证据，但空答复或异常停止不能作为完成。
        return reply != null && reply.text() != null && !reply.text().isBlank()
                && "MODEL_STOP".equals(reply.reason());
    }
    private static String expectedImplementation(InvestigationTools.Snapshot snapshot) {
        // 和工具返回格式逐字比较，拒绝伪造或错配的事实。
        return "expression=" + snapshot.expression() + "\nbaselinePassed=false\nbaselineExit="
                + snapshot.baseline().exitCode() + "\nbaselineOutput=" + snapshot.baseline().output();
    }
    private static final class RecordingProject implements RepairProject {
        private final RepairProject delegate;
        private final List<Check> checks = new ArrayList<>();
        private RecordingProject(RepairProject delegate) { this.delegate = delegate; }
        @Override public String read(String file) { return delegate.read(file); }
        @Override public String replace(String file, String before, String after) { return delegate.replace(file, before, after); }
        @Override public Check test() {
            // 保留服务与工具的真实运行次序；最终一项由 RepairService 执行。
            Check check = delegate.test();
            checks.add(check);
            return check;
        }
        @Override public String diff() { return delegate.diff(); }
        private Check lastCheck() { return checks.isEmpty() ? null : checks.get(checks.size() - 1); }
    }
}
