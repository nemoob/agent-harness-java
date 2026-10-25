package dev.agentharness.demo.investigation;

import dev.agentharness.demo.domain.RepairProject;
import dev.agentharness.demo.infra.repair.RepairWorkspace;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.ArrayList;

/** 两份调查快照在调用模型前冻结；工具没有项目、路径或命令句柄。 */
public final class InvestigationTools {
    public static final String RULE_ID = "strict-date-rule";
    public static final String SOURCE_ID = "DateParser.java";
    public static final String RULE = "日期必须严格按 ISO yyyy-MM-dd 校验；2025-02-29、1900-02-29、2025-04-31 必须拒绝，有效日期必须接受";

    public record Call(String tool, String argument, String result) {}
    public record Snapshot(String expression, RepairProject.Check baseline) {
        public Snapshot {
            // 只冻结固定练习的原始表达式和真实失败测试。
            if (!RepairWorkspace.ORIGINAL.equals(expression) || baseline == null || baseline.passed())
                throw new IllegalArgumentException("调查快照不是预期失败基线");
        }
    }

    public static final class Rules {
        private final List<Call> calls = new ArrayList<>();
        @Tool(name = "read_acceptance_rule", description = "只读取固定日期练习的严格日期验收规则", readOnly = true)
        public synchronized String read(@ToolParam(name = "rule", description = "仅 strict-date-rule") String rule) {
            // 拒绝模型提出的其他规则名，失败调用也保留证据。
            String result = RULE_ID.equals(rule) ? RULE : "REJECTED: rule";
            calls.add(new Call("read_acceptance_rule", rule, result));
            return result;
        }
        public synchronized List<Call> calls() { return List.copyOf(calls); }
    }

    public static final class Implementation {
        private final Snapshot snapshot;
        private final List<Call> calls = new ArrayList<>();
        public Implementation(Snapshot snapshot) { this.snapshot = snapshot; }
        @Tool(name = "read_fixed_implementation", description = "只读取已冻结的 DateParser 表达式与基线测试", readOnly = true)
        public synchronized String read(@ToolParam(name = "file", description = "仅 DateParser.java") String file) {
            // 返回预先取得的事实，不在模型工具里读取文件或编译。
            String result = SOURCE_ID.equals(file)
                    ? "expression=" + snapshot.expression() + "\nbaselinePassed=" + snapshot.baseline().passed()
                        + "\nbaselineExit=" + snapshot.baseline().exitCode()
                        + "\nbaselineOutput=" + snapshot.baseline().output()
                    : "REJECTED: file";
            calls.add(new Call("read_fixed_implementation", file, result));
            return result;
        }
        public synchronized List<Call> calls() { return List.copyOf(calls); }
    }
}
