package dev.agentharness.demo.app;

import dev.agentharness.demo.domain.AssistantGateway;
import dev.agentharness.demo.dto.AssistantReply;

/** 本例只组织单轮输入；模型与工具循环交给 Gateway 实现。 */
public final class ChatService {
    private final AssistantGateway gateway;
    public ChatService(AssistantGateway gateway) {
        // 由启动装配注入端口，测试可以替换为受控实现。
        this.gateway = gateway;
    }
    public AssistantReply ask(String question) {
        // 在模型接入前限制单次输入，避免无界提交；这不是 token 预算。
        if (question == null || question.isBlank() || question.length() > 8000) {
            throw new IllegalArgumentException("问题须为 1 到 8000 个字符");
        }
        return gateway.ask(question);
    }
}
