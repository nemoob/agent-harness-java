package dev.agentharness.demo.lab;

import dev.agentharness.demo.domain.DateValidation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 固定模型响应驱动的最小串行 Loop，故障可重复且不需要 API Key。 */
public final class MinimalLoop {
    public record Outcome(String reason, int modelCalls, int toolCalls, String answer) {}
    @FunctionalInterface
    public interface Model { Message respond(List<Message> messages); }

    public static Model fixed(boolean repeat) {
        // 返回规则响应；下一步必须读取实际回填的工具结果。
        return messages -> {
            var last = messages.get(messages.size() - 1);
            if (last.role().equals("tool") && !repeat) {
                return Message.of("assistant", "工具核验结果：" + last.text());
            }
            // 连续提出新的工具调用，可受控触发模型请求次数上限。
            long number = messages.stream().filter(m -> m.role().equals("call")).count() + 1;
            return new Message("call", "date-" + number, "check_date 2025-02-29");
        };
    }

    public static String execute(Message call) {
        // 工具名先走白名单，绝不把模型文本当成反射目标或命令执行。
        var fields = call.text().split(" ", -1);
        if (!call.role().equals("call") || fields.length != 2 || !fields[0].equals("check_date")) {
            throw new IllegalArgumentException("工具名或参数结构不受支持");
        }
        // 复用实际业务规则，同时执行格式校验；本实验只授权此只读工具。
        boolean valid = new DateValidation().isValid(fields[1]);
        return "date=" + fields[1] + ", valid=" + valid;
    }

    public static Outcome run(Model model, int maxCalls, String fault, Consumer<String> log) {
        // 这里限制的是本实验模型请求次数，与 AgentScope maxIters 的语义分别说明。
        if (maxCalls < 1 || !List.of("none", "bad-id", "missing-result").contains(fault)) {
            throw new IllegalArgumentException("实验配置无效");
        }
        var messages = new ArrayList<Message>();
        messages.add(Message.of("system", "使用 check_date 核验日期，工具结果回填后回答。"));
        messages.add(Message.of("user", "请检查 2025-02-29。"));
        log.accept("[1 准备消息] system + user；只允许 check_date");
        int toolCalls = 0;
        for (int round = 1; round <= maxCalls; round++) {
            // 每次模型请求前检验关联，故障不能悄悄传入下一轮。
            Message.validate(messages, false);
            log.accept("[2 请求模型 #" + round + "] messages=" + messages.size());
            var response = model.respond(List.copyOf(messages));
            if (response.role().equals("assistant")) {
                // 最终回答到达时立即停止，不再执行额外一次请求。
                if (response.text().isBlank()) throw new IllegalStateException("模型返回空回答");
                log.accept("[6 停止] MODEL_STOP；" + response.text());
                return new Outcome("MODEL_STOP", round, toolCalls, response.text());
            }
            if (!response.role().equals("call")) throw new IllegalStateException("模型响应类型无效");
            log.accept("[3 识别调用] id=" + response.callId() + " " + response.text());
            messages.add(response);
            // 添加调用后先检验 ID；重复 ID 不允许进入业务执行。
            Message.validate(messages, true);
            var result = execute(response);
            toolCalls++;
            log.accept("[4 校验并执行] " + result);
            if (!fault.equals("missing-result")) {
                // 仅教学故障模式改坏 ID；正常模式原样回填模型提供的关联 ID。
                var id = fault.equals("bad-id") ? "wrong-id" : response.callId();
                messages.add(new Message("tool", id, result));
                log.accept("[5 结果回填] id=" + id);
            } else {
                log.accept("[5 故障注入] 故意跳过结果回填");
            }
            // 包括最后一轮在内，都先验收结果关联，再决定是否继续。
            Message.validate(messages, false);
            log.accept("[6 判断] " + (round < maxCalls ? "继续" : "MAX_MODEL_CALLS"));
        }
        return new Outcome("MAX_MODEL_CALLS", maxCalls, toolCalls, "");
    }
}
