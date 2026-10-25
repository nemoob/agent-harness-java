package dev.agentharness.demo.operations;

import dev.agentharness.demo.domain.DateValidation;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.List;

/** 每次请求创建独立工具实例，以构造时校验的标识贯穿工具日志。 */
public final class OperationsDateTool {
    private final String requestId;
    private final List<Attempt> attempts = new ArrayList<>();
    private int callCount;

    private record Attempt(String date, boolean valid) { }

    public OperationsDateTool(String requestId) {
        // 工具只接受服务端已校验的关联标识。
        if (!OperationsMain.validRequestId(requestId)) {
            throw new IllegalArgumentException("请求标识错误");
        }
        this.requestId = requestId;
    }

    @Tool(name = "check_operations_date", description = "校验 yyyy-MM-dd 日期是否真实存在；必须调用后才能回答", readOnly = true)
    public synchronized String check(@ToolParam(name = "date", description = "完整日期，例如 2024-02-29") String date) {
        // 工具调用参数再次经过严格日期规则，日志仅输出安全值。
        callCount++;
        boolean checked = new DateValidation().isValid(date);
        attempts.add(new Attempt(date, checked));
        System.out.println("TOOL requestId=" + requestId + " date=" + date + " valid=" + checked);
        return "date=" + date + ", valid=" + checked;
    }

    public synchronized Boolean singleResultFor(String expectedDate) {
        // 恰好一次调用且输入、规则结果一致，才能给外层业务响应。
        if (callCount != 1 || attempts.size() != 1 || !expectedDate.equals(attempts.get(0).date())) {
            return null;
        }
        boolean expected = new DateValidation().isValid(expectedDate);
        return attempts.get(0).valid() == expected ? expected : null;
    }
}
