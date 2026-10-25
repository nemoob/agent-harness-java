package dev.agentharness.demo.domain;

import dev.agentharness.demo.dto.AssistantReply;

/** 应用依赖这个端口，不直接依赖具体模型或 AgentScope。 */
public interface AssistantGateway extends AutoCloseable {
    AssistantReply ask(String question);
    @Override void close();
}
