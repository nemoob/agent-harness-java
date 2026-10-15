package dev.agentharness.a2a;

/** 远端任务返回的最小文本制品。 */
public record A2aArtifact(String artifactId, String businessTaskId, String name, String text) {}
