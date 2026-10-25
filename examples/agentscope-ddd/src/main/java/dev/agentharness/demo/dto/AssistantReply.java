package dev.agentharness.demo.dto;

/** 文本与框架结束原因分别传递；非空文本不等于业务成功。 */
public record AssistantReply(String text, String reason) {}
