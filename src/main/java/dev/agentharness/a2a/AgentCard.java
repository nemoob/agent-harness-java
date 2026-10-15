package dev.agentharness.a2a;

import java.util.List;
import java.util.Map;

/** 教学服务公开的最小 Agent Card。 */
public record AgentCard(
        String name,
        String protocolVersion,
        Map<String, String> interfaces,
        List<String> capabilities) {}
