package dev.agentharness.demo.infra;

import dev.agentharness.demo.domain.DateValidation;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/** 框架工具适配器只处理参数、结果和日志，日期规则留在领域层。 */
public final class DateTool {
    private final DateValidation dates;
    public DateTool(DateValidation dates) {
        // 装配时复用领域服务；构造工具并不会执行校验。
        this.dates = dates;
    }
    @Tool(name = "check_date", description = "校验 yyyy-MM-dd 日期是否存在，年份 0001 到 9999", readOnly = true)
    public String check(@ToolParam(name = "date", description = "完整日期，例如 2024-02-29") String date) {
        System.out.println("[工具执行] check_date 开始");
        try {
            // 先通过领域格式校验，再记录白名单日期，避免日志注入。
            boolean valid = dates.isValid(date);
            String result = "date=" + date + ", valid=" + valid;
            System.out.println("[工具结果] " + result);
            return result;
        } catch (IllegalArgumentException invalid) {
            // 参数错误不输出原始输入，格式合法也不保证模型理解了用户意图。
            return "error=日期必须是 yyyy-MM-dd 格式";
        }
    }
}
