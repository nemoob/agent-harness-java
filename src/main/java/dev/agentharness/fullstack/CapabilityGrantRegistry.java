package dev.agentharness.fullstack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 服务端能力注册表；任务包只携带不可推导权限的随机引用。 */
public final class CapabilityGrantRegistry {
    private final Map<String, GrantState> grants = new ConcurrentHashMap<>();

    public CapabilityGrant issue(GrantSpec spec) {
        Objects.requireNonNull(spec, "spec");
        // 工作区签发时固化真实路径，拒绝链接与非目录。
        Path workspace = trustedWorkspace(spec.workspaceRoot());
        // 工具、路径和截止时间必须非空，避免宽泛空授权语义。
        if (spec.tools().isEmpty() || spec.paths().isEmpty() || spec.expiresAt() == null) {
            throw new IllegalArgumentException("grant allowlists and expiry are required");
        }
        // 随机引用不编码任何权限字段，防止客户端猜测或篡改能力。
        String reference = "grant-" + UUID.randomUUID();
        CapabilityGrant grant =
                new CapabilityGrant(
                        reference,
                        required(spec.principal(), "principal"),
                        required(spec.project(), "project"),
                        workspace,
                        required(spec.baseRevision(), "baseRevision"),
                        Objects.requireNonNull(spec.role(), "role"),
                        spec.tools(),
                        spec.paths(),
                        required(spec.target(), "target"),
                        required(spec.inputArtifactSha(), "inputArtifactSha"),
                        spec.arguments(),
                        spec.expiresAt());
        // putIfAbsent 保证随机碰撞时不会覆盖已有授权。
        if (grants.putIfAbsent(reference, new GrantState(grant)) != null) {
            throw new IllegalStateException("grant reference collision");
        }
        return grant;
    }

    public CapabilityGrant authorize(GrantUse use) {
        Objects.requireNonNull(use, "use");
        // 每次使用都从注册表重新加载服务端事实。
        GrantState state = grants.get(use.grantRef());
        if (state == null) {
            throw new IllegalStateException("capability grant not found");
        }
        CapabilityGrant grant = state.grant;
        // 撤销状态在每次副作用前即时生效。
        if (state.revoked) {
            throw new IllegalStateException("capability grant revoked");
        }
        // 到期时刻及之后一律拒绝。
        if (use.now() == null || !use.now().isBefore(grant.expiresAt())) {
            throw new IllegalStateException("capability grant expired");
        }
        // principal/project/workspace/base/role/target/artifact 均需逐项完全匹配。
        if (!grant.principal().equals(use.principal())
                || !grant.project().equals(use.project())
                || !grant.workspaceRoot().equals(normalized(use.workspace()))
                || !grant.baseRevision().equals(use.baseRevision())
                || grant.role() != use.role()
                || !grant.target().equals(use.target())
                || !grant.inputArtifactSha().equals(use.inputArtifactSha())) {
            throw new IllegalStateException("capability grant scope mismatch");
        }
        // 工具和相对路径只能命中服务端签发的精确 allowlist。
        if (!grant.tools().contains(use.tool()) || !grant.paths().contains(use.path())) {
            throw new IllegalStateException("capability grant allowlist mismatch");
        }
        // 关键参数用不可变映射精确绑定，防止授权后替换 candidate/hash。
        if (!grant.arguments().equals(Map.copyOf(use.arguments()))) {
            throw new IllegalStateException("capability grant argument mismatch");
        }
        // 返回服务端授权快照供紧邻的副作用使用。
        return grant;
    }

    public void revoke(String reference) {
        // 撤销未知引用同样稳定失败，避免误报已撤销。
        GrantState state = grants.get(reference);
        if (state == null) {
            throw new IllegalStateException("capability grant not found");
        }
        state.revoked = true;
    }

    private static Path trustedWorkspace(Path path) {
        if (path == null || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("workspace must be a real directory");
        }
        try {
            Path normalized = normalized(path);
            // NOFOLLOW 先确认对象为目录，再比较真实路径排除任一级链接。
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("workspace must be a real directory");
            }
            Path real = normalized.toRealPath();
            if (!real.equals(normalized)) {
                throw new IllegalArgumentException("workspace links are not allowed");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspace cannot be verified", exception);
        }
    }

    private static Path normalized(Path path) {
        return Objects.requireNonNull(path, "workspace").toAbsolutePath().normalize();
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    public record GrantSpec(
            String principal,
            String project,
            Path workspaceRoot,
            String baseRevision,
            FullStackRole role,
            Set<String> tools,
            Set<String> paths,
            String target,
            String inputArtifactSha,
            Map<String, String> arguments,
            Instant expiresAt) {
        public GrantSpec {
            tools = Set.copyOf(tools);
            paths = Set.copyOf(paths);
            arguments = Map.copyOf(arguments);
        }
    }

    public record GrantUse(
            String grantRef,
            String principal,
            String project,
            Path workspace,
            String baseRevision,
            FullStackRole role,
            String tool,
            String path,
            String target,
            String inputArtifactSha,
            Map<String, String> arguments,
            Instant now) {
        public GrantUse {
            arguments = Map.copyOf(arguments);
        }
    }

    private static final class GrantState {
        private final CapabilityGrant grant;
        private volatile boolean revoked;

        private GrantState(CapabilityGrant grant) {
            this.grant = grant;
        }
    }
}
