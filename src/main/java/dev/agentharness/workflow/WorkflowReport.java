package dev.agentharness.workflow;

import java.util.List;

/** 四角色门槛交接的结果。 */
public record WorkflowReport(
        String session, String status, List<String> route, List<RoleArtifact> artifacts) {}
