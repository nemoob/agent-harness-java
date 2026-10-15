package dev.agentharness.a2a;

/** 超时后仅查询任务的协调结果。 */
public record Reconciliation(String status, boolean sendAttempted, A2aTask task) {}
