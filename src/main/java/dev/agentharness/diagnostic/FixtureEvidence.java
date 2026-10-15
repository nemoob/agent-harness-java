package dev.agentharness.diagnostic;

/** 本地只读 fixture 的结构化输出。 */
public record FixtureEvidence(
        String evidenceId, String source, boolean present, String businessId, String fact) {}
