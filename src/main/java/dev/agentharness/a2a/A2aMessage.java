package dev.agentharness.a2a;

/** A2A 语义子集中的发送方消息与业务标识映射。 */
public record A2aMessage(
        String messageId,
        String contextId,
        String taskId,
        String businessTaskId,
        String text) {}
