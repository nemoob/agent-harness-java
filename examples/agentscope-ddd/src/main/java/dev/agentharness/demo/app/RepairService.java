package dev.agentharness.demo.app;

import dev.agentharness.demo.domain.*;

/** 程序掌握验收权；模型负责调查和提出受限修改。 */
public final class RepairService {
    private static String visible(String text) {
        // 模型文本只占一条带固定前缀的日志；转义换行、终端控制和方向控制字符。
        if (text == null) return "null";
        var out = new StringBuilder();
        text.codePoints().limit(16000).forEach(c -> {
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || c == 0x2028 || c == 0x2029) out.append(String.format("\\u%04X", c));
            else out.appendCodePoint(c);
        });
        if (text.codePointCount(0, text.length()) > 16000) out.append(" [TRUNCATED]");
        return out.toString();
    }
    public boolean run(AssistantGateway assistant, RepairProject project, String task) {
        // 先复现固定练习的失败，避免对一个原本通过的项目宣称修复。
        var baseline = project.test();
        System.out.println("[基线] " + (baseline.passed() ? "PASS" : "FAIL"));
        System.out.println(baseline.output());
        if (baseline.passed()) throw new IllegalStateException("练习基线应失败");
        boolean returned = false;
        try {
            // 模型获得任务和真实失败输出，源码仍由工具读取。
            var reply = assistant.ask(task + "\n初始测试输出：\n" + baseline.output());
            System.out.println("[模型说明，非验收] " + visible(reply.text()));
            System.out.println("[模型结束] " + visible(reply.reason()));
            // 达到循环上限等结束原因不能伪装成正常完成。
            returned = "MODEL_STOP".equals(reply.reason());
        } catch (RuntimeException failure) {
            // 供应商异常正文可能含敏感信息，仅记录本地状态。
            System.out.println("[模型结束] ERROR_OR_TIMEOUT");
        }
        // 不采信模型声称通过；重新编译当前源码并执行可信测试。
        var verification = project.test();
        String diff = project.diff();
        System.out.println("[最终复验] " + (verification.passed() ? "PASS" : "FAIL"));
        System.out.println(verification.output());
        System.out.println("[修改差异]\n" + diff);
        boolean success = returned && verification.passed() && !diff.equals("NO_CHANGE");
        System.out.println("[任务] " + (success ? "PASS" : "NOT_COMPLETED"));
        return success;
    }
}
