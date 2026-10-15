package dev.agentharness.state;

/** 教学证据只保存公开 fixture 标识与脱敏摘要。 */
public record Evidence(String action, String evidenceId, String summary) {}
