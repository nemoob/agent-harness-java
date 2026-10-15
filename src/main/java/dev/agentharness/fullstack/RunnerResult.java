package dev.agentharness.fullstack;

/** Docker runner 的有界输出与候选证据。 */
public record RunnerResult(int exitCode, String stdout, String stderr, String candidateSha256, int sideEffectCount) {}
