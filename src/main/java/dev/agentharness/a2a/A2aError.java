package dev.agentharness.a2a;

/** 稳定错误码与无敏感信息的错误说明。 */
public record A2aError(String code, String message) {}
