package dev.agentharness.demo.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** 确定性的日期规则，不依赖模型、工具注解或网络。 */
public final class DateValidation {
    public boolean isValid(String date) {
        // 格式不明时拒绝猜测；让调用者区分参数错误和日期不存在。
        if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("日期必须是 yyyy-MM-dd 格式");
        }
        try {
            // ISO 严格解析不会把 2 月 29 日自动改成月底，额外排除公元 0 年。
            return LocalDate.parse(date).getYear() >= 1;
        } catch (DateTimeParseException invalid) {
            // 格式正确但日期不存在属于业务否定结果。
            return false;
        }
    }
}
