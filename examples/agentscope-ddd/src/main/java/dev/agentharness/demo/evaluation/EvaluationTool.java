package dev.agentharness.demo.evaluation;

import dev.agentharness.demo.domain.DateValidation;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.List;

/** 评测专用的唯一工具；逐次保存实际参数和返回事实。 */
public final class EvaluationTool {
    private final DateValidation dates = new DateValidation();
    private final List<EvaluationRunner.ToolCall> calls = new ArrayList<>();

    @Tool(name = "check_date", description = EvaluationRunner.TOOL_DESCRIPTION, readOnly = true)
    public String check(@ToolParam(name = "date", description = EvaluationRunner.DATE_PARAM_DESCRIPTION) String date) {
        // 参数仅允许固定格式进入证据，非法输入保留错误类型而不保存任意模型文本。
        String safeDate = Evidence.bounded(date);
        String result;
        try {
            // 真实结果来自同一个领域日期规则。
            result = "date=" + date + ", valid=" + dates.isValid(date);
        } catch (IllegalArgumentException invalid) {
            result = "error=日期必须是 yyyy-MM-dd 格式";
        }
        calls.add(new EvaluationRunner.ToolCall(safeDate, result));
        return result;
    }

    public List<EvaluationRunner.ToolCall> calls() { return List.copyOf(calls); }
}
