package dev.agentharness.demo.lab;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

/** 教学消息，不是 AgentScope 或模型供应商的线上报文。 */
public record Message(String role, String callId, String text) {
    public Message {
        // 固定角色与非空字段使实验中的无效消息立即暴露。
        if (!List.of("system", "user", "assistant", "call", "tool").contains(role)
                || callId == null || text == null) {
            throw new IllegalArgumentException("消息字段无效");
        }
        // 只有调用和结果携带关联 ID，避免普通回答伪装成工具事件。
        if ((role.equals("call") || role.equals("tool")) == callId.isBlank()) {
            throw new IllegalArgumentException("调用 ID 与消息角色不符");
        }
    }

    public static Message of(String role, String text) {
        // 普通消息不带工具调用身份。
        return new Message(role, "", text);
    }

    public static void validate(List<Message> messages, boolean allowPending) {
        // 该最小实验只允许串行工具调用；并行协议不在这里模拟。
        String pending = null;
        var ids = new HashSet<String>();
        for (var message : messages) {
            // 未完成调用只能紧接它自己的结果，不能插入下一轮问题或回答。
            if (pending != null && !message.role().equals("tool")) {
                throw new IllegalStateException("缺少结果回填: " + pending);
            }
            if (message.role().equals("call")) {
                // 同一请求历史中的调用 ID 必须唯一。
                if (!ids.add(message.callId())) throw new IllegalStateException("重复调用 ID");
                pending = message.callId();
            } else if (message.role().equals("tool")) {
                // 结果没有对应调用或者 ID 被改坏，都不能继续请求模型。
                if (!message.callId().equals(pending)) {
                    throw new IllegalStateException("工具结果 ID 不匹配: " + message.callId());
                }
                pending = null;
            }
        }
        // 选择器可保留末尾未完成调用，模型请求前则必须先补齐结果。
        if (pending != null && !allowPending) throw new IllegalStateException("缺少结果回填: " + pending);
    }

    public static int bytes(List<Message> messages) {
        // 统计本实验完整线格式的 UTF-8 字节，包括角色、ID、正文和字段长度。
        var wire = new StringBuilder();
        for (var message : messages) {
            for (var field : List.of(message.role(), message.callId(), message.text())) {
                wire.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field);
            }
            wire.append('\n');
        }
        // 这是可重复的字节预算，不是 token 估算，更不是供应商实际请求大小。
        return wire.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
