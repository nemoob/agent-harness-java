package dev.agentharness.demo.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 独立教学命令，不读取供应商凭据，也不发起网络请求。 */
public final class LabMain {
    public static void main(String[] args) {
        try {
            // 明确标识模型来源，避免将规则响应当成真实模型实测。
            System.out.println("[实验] 固定模型响应；离线执行；无 API Key");
            if (args.length == 1 && args[0].equals("context")) {
                context();
            } else if (args.length >= 1 && args.length <= 2 && args[0].equals("loop")) {
                var scenario = args.length == 2 ? args[1] : "normal";
                if (!List.of("normal", "bad-id", "missing-result", "limit").contains(scenario)) {
                    throw new IllegalArgumentException("未知 Loop 场景");
                }
                var fault = scenario.equals("normal") || scenario.equals("limit") ? "none" : scenario;
                var result = MinimalLoop.run(MinimalLoop.fixed(scenario.equals("limit")), 2, fault, System.out::println);
                System.out.println("[结束] reason=" + result.reason() + " modelCalls=" + result.modelCalls()
                        + " toolCalls=" + result.toolCalls());
                // 达到上限是未完成；命令退出码明确区分正常回答。
                if (!result.reason().equals("MODEL_STOP")) System.exit(2);
            } else {
                throw new IllegalArgumentException("用法: ./run-lab.sh loop [normal|bad-id|missing-result|limit] 或 context");
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            // 受控实验只输出明确错误，不伪造完成结果。
            System.err.println("[实验停止] " + failure.getMessage());
            System.exit(2);
        }
    }

    public static ContextSelection.Episode date(String date, String id) {
        // 先执行真实日期规则，再形成完整的一轮历史，结果不是手写常量。
        var call = new Message("call", id, "check_date " + date);
        var result = MinimalLoop.execute(call);
        return new ContextSelection.Episode(date, List.of(Message.of("user", "检查 " + date), call,
                new Message("tool", id, result), Message.of("assistant", result)));
    }

    private static void context() {
        // 固定要求与工具定义始终参与预算；当前问题只要求汇总已有事实。
        var fixed = List.of(Message.of("system", "只依据工具事实，不猜测；保留日期原值。"),
                Message.of("system", "工具定义: check_date(date: yyyy-MM-dd)，只读，返回 date 与 valid。"));
        var current = Message.of("user", "只列出刚才检查过的两个日期及结果，无需重新校验。");
        var history = new ArrayList<ContextSelection.Episode>();
        history.add(date("2025-02-29", "date-1"));
        history.add(new ContextSelection.Episode("unrelated", List.of(Message.of("user", "讲一段无关话题"),
                Message.of("assistant", "与日期汇总无关的旧解释。".repeat(100)))));
        history.add(date("2024-02-29", "date-2"));
        // 保存不可变快照，用实际比较验证选择没有覆盖证据历史。
        var original = List.copyOf(history);
        int budget = 1100;
        System.out.println("[预算] " + budget + " UTF-8 字节，包含全部教学请求字段；不是 token");
        for (int count = 1; count <= history.size(); count++) {
            // 观察不选择时请求如何增长，超过预算的请求不会交给模型。
            int bytes = Message.bytes(ContextSelection.assemble(fixed, history.subList(0, count), current));
            System.out.println("[none] 片段=" + count + " 完整请求=" + bytes + " bytes");
        }
        var full = ContextSelection.assemble(fixed, history, current);
        System.out.println("[none] " + (Message.bytes(full) > budget ? "超预算，拒绝发送" : ContextSelection.summarize(full)));
        // 故意只取历史最后两条：留下工具结果，却删掉对应调用。
        var tail = new ArrayList<>(fixed);
        var last = history.get(history.size() - 1).messages();
        tail.addAll(last.subList(last.size() - 2, last.size()));
        tail.add(current);
        boolean rejected = false;
        try {
            System.out.println("[tail] " + ContextSelection.summarize(tail));
        } catch (IllegalStateException expected) {
            rejected = true;
            System.out.println("[tail] " + expected.getMessage());
        }
        if (!rejected) throw new IllegalStateException("预期的孤立工具结果没有被拦截");
        var selected = ContextSelection.select(fixed, history, current,
                Set.of("2025-02-29", "2024-02-29"), budget);
        // 最终回答真正消费筛选后的请求，且不触发额外工具执行。
        System.out.println("[episodes] 完整请求=" + Message.bytes(selected) + " bytes");
        var answer = ContextSelection.summarize(selected);
        System.out.println("[固定模型回答] " + answer);
        // 验收请求预算、必要材料、原历史和两条事实，不仅打印成功字样。
        if (Message.bytes(selected) > budget || !selected.containsAll(fixed) || !selected.contains(current)
                || !original.equals(history)
                || !answer.equals("date=2025-02-29, valid=false；date=2024-02-29, valid=true")) {
            throw new IllegalStateException("完整片段选择验收失败");
        }
        System.out.println("[验收] 原始历史仍有 " + history.size() + " 个片段；本轮工具执行=0；必要约束保留");
        rejected = false;
        try {
            // 必要材料也装不下时，演示明确拒绝而非无限裁剪。
            ContextSelection.select(fixed, history, current, Set.of(), 1);
        } catch (IllegalStateException expected) {
            rejected = true;
            System.out.println("[极小预算] " + expected.getMessage());
        }
        if (!rejected) throw new IllegalStateException("预期超预算没有被拦截");
    }
}
