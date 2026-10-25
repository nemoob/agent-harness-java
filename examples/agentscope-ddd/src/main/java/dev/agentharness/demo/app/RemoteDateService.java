package dev.agentharness.demo.app;

import dev.agentharness.demo.domain.AssistantGateway;
import java.util.Objects;
import java.util.function.Supplier;

/** 每次交接创建独立 Agent；不把网络字段或上次历史传入模型配置。 */
public final class RemoteDateService {
    private final Supplier<AssistantGateway> factory;
    public RemoteDateService(Supplier<AssistantGateway> factory) {
        this.factory = Objects.requireNonNull(factory);
    }
    public String answer(String date) {
        // 应用入口再次验证交接格式；合法格式但日期不存在仍可返回正常业务结果。
        if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("date");
        try (var agent = factory.get()) {
            // 任务由应用明确说明，网络输入仍只提供经过校验的日期。
            var reply = agent.ask("请调用 check_date 检查 " + date + "，返回工具给出的日期及 valid 结果。");
            // 达到循环上限或空答复不能伪装成正常完成的远端回答。
            if (reply == null || !"MODEL_STOP".equals(reply.reason()) || reply.text() == null || reply.text().isBlank()) throw new IllegalStateException("incomplete");
            System.out.println("[A2A Agent] reason=MODEL_STOP");
            return reply.text();
        }
    }
}
