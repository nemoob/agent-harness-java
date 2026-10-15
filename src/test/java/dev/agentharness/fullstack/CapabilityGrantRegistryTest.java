package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityGrantRegistryTest {
    @Test
    void revalidatesEveryBoundDimensionAndRevocation() throws Exception {
        Path workspace = workspace();
        CapabilityGrantRegistry registry = new CapabilityGrantRegistry();
        CapabilityGrant grant =
                registry.issue(
                        new CapabilityGrantRegistry.GrantSpec(
                                "principal-a",
                                "project-a",
                                workspace,
                                "base-1",
                                FullStackRole.FRONTEND,
                                Set.of("apply_workspace"),
                                Set.of("web/index.html", "web/styles.css", "web/app.js"),
                                "candidate",
                                "artifact-sha-a",
                                Map.of("manifestSha", "manifest-a"),
                                Instant.now().plusSeconds(60)));
        CapabilityGrantRegistry.GrantUse valid =
                new CapabilityGrantRegistry.GrantUse(
                        grant.reference(),
                        "principal-a",
                        "project-a",
                        workspace,
                        "base-1",
                        FullStackRole.FRONTEND,
                        "apply_workspace",
                        "web/index.html",
                        "candidate",
                        "artifact-sha-a",
                        Map.of("manifestSha", "manifest-a"),
                        Instant.now());

        assertDoesNotThrow(() -> registry.authorize(valid));
        assertThrows(IllegalStateException.class, () -> registry.authorize(withRef(valid, "forged")));
        assertThrows(IllegalStateException.class, () -> registry.authorize(withPrincipal(valid, "other")));
        assertThrows(IllegalStateException.class, () -> registry.authorize(withProject(valid, "other")));
        assertThrows(
                IllegalStateException.class,
                () -> registry.authorize(withWorkspace(valid, workspace.resolve("other"))));
        assertThrows(IllegalStateException.class, () -> registry.authorize(withBase(valid, "base-2")));
        assertThrows(
                IllegalStateException.class,
                () -> registry.authorize(withRole(valid, FullStackRole.BACKEND)));
        assertThrows(IllegalStateException.class, () -> registry.authorize(withTool(valid, "docker")));
        assertThrows(
                IllegalStateException.class,
                () -> registry.authorize(withPath(valid, "web/other.js")));
        assertThrows(IllegalStateException.class, () -> registry.authorize(withTarget(valid, "prod")));
        assertThrows(IllegalStateException.class, () -> registry.authorize(withArtifact(valid, "changed")));
        assertThrows(
                IllegalStateException.class,
                () -> registry.authorize(withArguments(valid, Map.of("manifestSha", "changed"))));

        registry.revoke(grant.reference());
        assertThrows(IllegalStateException.class, () -> registry.authorize(valid));
    }

    @Test
    void rejectsExpiredGrantAndWorkspaceSymlink() throws Exception {
        Path workspace = workspace();
        CapabilityGrantRegistry registry = new CapabilityGrantRegistry();
        CapabilityGrant grant =
                registry.issue(
                        new CapabilityGrantRegistry.GrantSpec(
                                "principal-a",
                                "project-a",
                                workspace,
                                "base-1",
                                FullStackRole.TEST,
                                Set.of("run_tests"),
                                Set.of("candidate.jar"),
                                "test",
                                "sha-a",
                                Map.of(),
                                Instant.parse("2026-10-15T00:00:01Z")));
        CapabilityGrantRegistry.GrantUse expired =
                new CapabilityGrantRegistry.GrantUse(
                        grant.reference(),
                        "principal-a",
                        "project-a",
                        workspace,
                        "base-1",
                        FullStackRole.TEST,
                        "run_tests",
                        "candidate.jar",
                        "test",
                        "sha-a",
                        Map.of(),
                        Instant.parse("2026-10-15T00:00:02Z"));
        assertThrows(IllegalStateException.class, () -> registry.authorize(expired));

        Path link = workspace.getParent().resolve("workspace-link-" + UUID.randomUUID());
        Files.createSymbolicLink(link, workspace.getFileName());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        registry.issue(
                                new CapabilityGrantRegistry.GrantSpec(
                                        "p",
                                        "x",
                                        link,
                                        "b",
                                        FullStackRole.TEST,
                                        Set.of("run_tests"),
                                        Set.of("candidate.jar"),
                                        "test",
                                        "sha",
                                        Map.of(),
                                        Instant.now().plusSeconds(60))));
    }

    private static Path workspace() throws Exception {
        Path path = Path.of("target", "grant-test", UUID.randomUUID().toString());
        Files.createDirectories(path);
        return path.toRealPath();
    }

    private static CapabilityGrantRegistry.GrantUse withRef(CapabilityGrantRegistry.GrantUse u, String v) { return new CapabilityGrantRegistry.GrantUse(v,u.principal(),u.project(),u.workspace(),u.baseRevision(),u.role(),u.tool(),u.path(),u.target(),u.inputArtifactSha(),u.arguments(),u.now()); }
    private static CapabilityGrantRegistry.GrantUse withPrincipal(CapabilityGrantRegistry.GrantUse u, String v) { return new CapabilityGrantRegistry.GrantUse(u.grantRef(),v,u.project(),u.workspace(),u.baseRevision(),u.role(),u.tool(),u.path(),u.target(),u.inputArtifactSha(),u.arguments(),u.now()); }
    private static CapabilityGrantRegistry.GrantUse withProject(CapabilityGrantRegistry.GrantUse u, String v) { return new CapabilityGrantRegistry.GrantUse(u.grantRef(),u.principal(),v,u.workspace(),u.baseRevision(),u.role(),u.tool(),u.path(),u.target(),u.inputArtifactSha(),u.arguments(),u.now()); }
    private static CapabilityGrantRegistry.GrantUse withWorkspace(CapabilityGrantRegistry.GrantUse u, Path v) { return new CapabilityGrantRegistry.GrantUse(u.grantRef(),u.principal(),u.project(),v,u.baseRevision(),u.role(),u.tool(),u.path(),u.target(),u.inputArtifactSha(),u.arguments(),u.now()); }
    private static CapabilityGrantRegistry.GrantUse withBase(CapabilityGrantRegistry.GrantUse u, String v) { return new CapabilityGrantRegistry.GrantUse(u.grantRef(),u.principal(),u.project(),u.workspace(),v,u.role(),u.tool(),u.path(),u.target(),u.inputArtifactSha(),u.arguments(),u.now()); }
    private static CapabilityGrantRegistry.GrantUse withRole(CapabilityGrantRegistry.GrantUse u, FullStackRole v) { return new CapabilityGrantRegistry.GrantUse(u.grantRef(),u.principal(),u.project(),u.workspace(),u.baseRevision(),v,u.tool(),u.path(),u.target(),u.inputArtifactSha(),u.arguments(),u.now()); }
    private static CapabilityGrantRegistry.GrantUse withTool(CapabilityGrantRegistry.GrantUse u, String v) { return new CapabilityGrantRegistry.GrantUse(u.grantRef(),u.principal(),u.project(),u.workspace(),u.baseRevision(),u.role(),v,u.path(),u.target(),u.inputArtifactSha(),u.arguments(),u.now()); }
    private static CapabilityGrantRegistry.GrantUse withPath(CapabilityGrantRegistry.GrantUse u, String v) { return new CapabilityGrantRegistry.GrantUse(u.grantRef(),u.principal(),u.project(),u.workspace(),u.baseRevision(),u.role(),u.tool(),v,u.target(),u.inputArtifactSha(),u.arguments(),u.now()); }
    private static CapabilityGrantRegistry.GrantUse withTarget(CapabilityGrantRegistry.GrantUse u, String v) { return new CapabilityGrantRegistry.GrantUse(u.grantRef(),u.principal(),u.project(),u.workspace(),u.baseRevision(),u.role(),u.tool(),u.path(),v,u.inputArtifactSha(),u.arguments(),u.now()); }
    private static CapabilityGrantRegistry.GrantUse withArtifact(CapabilityGrantRegistry.GrantUse u, String v) { return new CapabilityGrantRegistry.GrantUse(u.grantRef(),u.principal(),u.project(),u.workspace(),u.baseRevision(),u.role(),u.tool(),u.path(),u.target(),v,u.arguments(),u.now()); }
    private static CapabilityGrantRegistry.GrantUse withArguments(CapabilityGrantRegistry.GrantUse u, Map<String,String> v) { return new CapabilityGrantRegistry.GrantUse(u.grantRef(),u.principal(),u.project(),u.workspace(),u.baseRevision(),u.role(),u.tool(),u.path(),u.target(),u.inputArtifactSha(),v,u.now()); }
}
