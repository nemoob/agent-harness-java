package dev.agentharness.fullstack;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** 在任何容器副作用前绑定 grant、候选 hash 与当前测试证据。 */
public final class ReleaseGate {
    private final CapabilityGrantRegistry grants;
    private final Adapter adapter;

    public ReleaseGate(CapabilityGrantRegistry grants, Adapter adapter) {
        // 注册表与适配器都由受信协调器注入。
        this.grants = Objects.requireNonNull(grants, "grants");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public ReleaseDecision release(ReleaseRequest request) {
        Objects.requireNonNull(request, "request");
        int effectsBefore = adapter.sideEffectCount();
        // 所有确定性门禁在 adapter 调用前完成，拒绝路径副作用必须为零。
        if (!preflight(request)) {
            return blocked(effectsBefore);
        }
        // 替换部署要求既有 anchor 的 handle 与 hash 自洽。
        if (request.rollbackAnchor() != null
                && !request.rollbackAnchor().candidateSha256()
                        .equals(request.rollbackAnchor().deployment().candidateSha256())) {
            return blocked(effectsBefore);
        }
        DeploymentHandle candidate = null;
        boolean switchStarted = false;
        try {
            // 替换候选先启动在 shadow loopback；旧稳定实例保持运行。
            candidate =
                    adapter.start(
                            request.candidateJar(),
                            request.candidateSha256(),
                            request.previewPort(),
                            request.healthFault(),
                            request.rollbackAnchor() != null);
            boolean health = adapter.health(candidate);
            // shadow 或首次部署都必须先通过真实 health 与业务检查。
            boolean business = health && adapter.business(candidate);
            if (health && business && request.rollbackAnchor() == null) {
                return new ReleaseDecision(
                        ReleaseDecision.Status.DEPLOYED,
                        adapter.sideEffectCount() - effectsBefore,
                        true,
                        true,
                        false,
                        true,
                        "",
                        java.util.List.of(),
                        request.candidateSha256(),
                        candidate);
            }
            if (!health || !business) {
                // shadow 失败先清新资源；旧实例从未下线。
                StopResult stopped = safeStop(candidate);
                if (request.rollbackAnchor() != null) {
                    DeploymentHandle prior = request.rollbackAnchor().deployment();
                    boolean priorHealth = adapter.health(prior);
                    boolean priorBusiness = priorHealth && adapter.business(prior);
                    if (priorHealth && priorBusiness && stopped.complete()) {
                        return new ReleaseDecision(
                                ReleaseDecision.Status.ROLLED_BACK,
                                adapter.sideEffectCount() - effectsBefore,
                                true,
                                true,
                                true,
                                true,
                                "",
                                java.util.List.of(),
                                request.rollbackAnchor().candidateSha256(),
                                prior);
                    }
                    return rollbackFailed(
                            effectsBefore,
                            priorHealth,
                            priorBusiness,
                            prior,
                            stopped.complete(),
                            stopped.residualResources());
                }
                if (!stopped.complete()) {
                    return cleanupFailed(effectsBefore, health, business, stopped, candidate);
                }
                return new ReleaseDecision(
                        ReleaseDecision.Status.DEPLOY_FAILED,
                        adapter.sideEffectCount() - effectsBefore,
                        health,
                        business,
                        false,
                        true,
                        "",
                        java.util.List.of(),
                        "",
                        null);
            }
            // shadow 通过后切换稳定端口；adapter 保留旧 candidate/network 作为 rollback anchor。
            switchStarted = true;
            candidate = adapter.activate(candidate, request.rollbackAnchor().deployment());
            boolean activeHealth = adapter.health(candidate);
            boolean activeBusiness = activeHealth && adapter.business(candidate);
            if (activeHealth && activeBusiness) {
                // 新候选稳定通过后才清理旧 candidate/network/gateway 资源。
                StopResult oldStopped = safeStop(request.rollbackAnchor().deployment());
                if (!oldStopped.complete()) {
                    return cleanupFailed(
                            effectsBefore, true, true, oldStopped, candidate);
                }
                return new ReleaseDecision(
                        ReleaseDecision.Status.DEPLOYED,
                        adapter.sideEffectCount() - effectsBefore,
                        true,
                        true,
                        false,
                        true,
                        "",
                        java.util.List.of(),
                        request.candidateSha256(),
                        candidate);
            }
            // 切换后检查失败进入统一补偿事务。
            return recover(request, candidate, effectsBefore, true);
        } catch (RuntimeException failure) {
            if (request.rollbackAnchor() != null) {
                // 替换链任一副作用异常都必须清新并恢复旧稳定实例后再裁决。
                return recover(request, candidate, effectsBefore, switchStarted);
            }
            // 首次部署没有旧 anchor，只能逆序清理新资源并报告首次失败。
            StopResult stopped = safeStop(candidate);
            if (!stopped.complete()) {
                return cleanupFailed(effectsBefore, false, false, stopped, candidate);
            }
            return new ReleaseDecision(
                    ReleaseDecision.Status.DEPLOY_FAILED,
                    adapter.sideEffectCount() - effectsBefore,
                    false,
                    false,
                    false,
                    true,
                    "",
                    java.util.List.of(),
                    "",
                    candidate);
        }
    }

    private ReleaseDecision recover(
            ReleaseRequest request,
            DeploymentHandle candidate,
            int effectsBefore,
            boolean restoreGateway) {
        // 新候选及其两张独立网络先逆序清理，避免恢复后残留双活资源。
        StopResult stopped = safeStop(candidate);
        DeploymentHandle restored;
        try {
            // switch 已开始时旧 gateway 可能已移除，必须在相同稳定端口重建。
            restored =
                    restoreGateway
                            ? adapter.restore(request.rollbackAnchor())
                            : request.rollbackAnchor().deployment();
        } catch (RuntimeException restoreFailure) {
            return rollbackFailed(
                    effectsBefore,
                    false,
                    false,
                    null,
                    stopped.complete(),
                    stopped.residualResources());
        }
        boolean health;
        boolean business;
        try {
            // 只有恢复后的 health 与真实业务检查都通过才能称为已回滚。
            health = adapter.health(restored);
            business = health && adapter.business(restored);
        } catch (RuntimeException verificationFailure) {
            return rollbackFailed(
                    effectsBefore,
                    false,
                    false,
                    restored,
                    stopped.complete(),
                    stopped.residualResources());
        }
        if (!health || !business) {
            return rollbackFailed(
                    effectsBefore,
                    health,
                    business,
                    restored,
                    stopped.complete(),
                    stopped.residualResources());
        }
        if (!stopped.complete()) {
            return rollbackFailed(
                    effectsBefore,
                    true,
                    true,
                    restored,
                    false,
                    stopped.residualResources());
        }
        return new ReleaseDecision(
                ReleaseDecision.Status.ROLLED_BACK,
                adapter.sideEffectCount() - effectsBefore,
                true,
                true,
                true,
                true,
                "",
                java.util.List.of(),
                request.rollbackAnchor().candidateSha256(),
                restored);
    }

    private StopResult safeStop(DeploymentHandle deployment) {
        if (deployment == null) {
            return new StopResult(true, java.util.List.of());
        }
        try {
            // Adapter 内部逐项清理；全部资源确认不存在后 stop 才返回成功。
            adapter.stop(deployment);
            return new StopResult(true, java.util.List.of());
        } catch (PreviewReleaseAdapter.CleanupException cleanupFailure) {
            // 仅传播本次部署的受控资源类型/ID，不携带 Docker 输出或秘密。
            return new StopResult(false, cleanupFailure.residualResources());
        } catch (RuntimeException unknownFailure) {
            // 未提供结构化残留时仍失败关闭，禁止误报部署或回滚成功。
            return new StopResult(false, java.util.List.of("deployment:" + deployment.deploymentId()));
        }
    }

    private ReleaseDecision rollbackFailed(
            int effectsBefore,
            boolean health,
            boolean business,
            DeploymentHandle deployment,
            boolean cleanupComplete,
            java.util.List<String> residualResources) {
        return new ReleaseDecision(
                ReleaseDecision.Status.ROLLBACK_FAILED,
                adapter.sideEffectCount() - effectsBefore,
                health,
                business,
                false,
                cleanupComplete,
                cleanupComplete ? "ROLLBACK_VERIFICATION_FAILED" : "CLEANUP_INCOMPLETE",
                residualResources,
                "",
                deployment);
    }

    private ReleaseDecision cleanupFailed(
            int effectsBefore,
            boolean health,
            boolean business,
            StopResult cleanup,
            DeploymentHandle deployment) {
        return new ReleaseDecision(
                ReleaseDecision.Status.CLEANUP_FAILED,
                adapter.sideEffectCount() - effectsBefore,
                health,
                business,
                false,
                false,
                "CLEANUP_INCOMPLETE",
                cleanup.residualResources(),
                "",
                deployment);
    }

    private boolean preflight(ReleaseRequest request) {
        try {
            // 候选必须是未跟随链接的既有普通文件。
            Path candidate = request.candidateJar().toAbsolutePath().normalize();
            if (Files.isSymbolicLink(candidate)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || !candidate.toRealPath().equals(candidate)) {
                return false;
            }
            // Gate 独立复算候选字节 hash，不能只信调用方字符串。
            String actualHash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(candidate)));
            if (!actualHash.equals(request.candidateSha256())) {
                return false;
            }
            // target 只能是固定 preview，端口限制为非特权 loopback 映射范围。
            if (!"preview".equals(request.target())
                    || request.previewPort() < 1_024
                    || request.previewPort() > 65_535) {
                return false;
            }
            // 当前候选、通过测试的候选与 grant 的 artifact hash 必须完全一致。
            if (!request.testEvidence().passed()
                    || !request.candidateSha256()
                            .equals(request.testEvidence().candidateSha256())
                    || !request.candidateSha256()
                            .equals(request.grantUse().inputArtifactSha())) {
                return false;
            }
            // registry 在当前时刻重新验证 principal/project/workspace/base/role/tool/path/target/hash/参数。
            grants.authorize(request.grantUse());
            return true;
        } catch (Exception rejected) {
            // 授权、路径或输入错误统一在副作用前关闭发布。
            return false;
        }
    }

    private ReleaseDecision blocked(int effectsBefore) {
        // 拒绝必须证明适配器副作用计数没有变化。
        return new ReleaseDecision(
                ReleaseDecision.Status.DEPLOY_BLOCKED,
                adapter.sideEffectCount() - effectsBefore,
                false,
                false,
                false,
                true,
                "",
                java.util.List.of(),
                "",
                null);
    }

    private record StopResult(boolean complete, java.util.List<String> residualResources) {
        private StopResult {
            residualResources = java.util.List.copyOf(residualResources);
        }
    }

    public record ReleaseRequest(
            CapabilityGrantRegistry.GrantUse grantUse,
            Path candidateJar,
            String candidateSha256,
            TestEvidence testEvidence,
            String target,
            int previewPort,
            boolean healthFault,
            DeploymentAnchor rollbackAnchor) {}

    public record TestEvidence(String candidateSha256, boolean passed) {}

    public record DeploymentHandle(
            String deploymentId,
            String candidateSha256,
            String loopbackUrl,
            String handoffTokenFile,
            boolean healthFault) {}

    public record DeploymentAnchor(
            DeploymentHandle deployment, String candidateSha256) {}

    public interface Adapter {
        DeploymentHandle start(
                Path candidateJar,
                String candidateSha256,
                int previewPort,
                boolean healthFault,
                boolean shadow);

        DeploymentHandle activate(
                DeploymentHandle candidate, DeploymentHandle previous);

        void stop(DeploymentHandle deployment);

        boolean health(DeploymentHandle deployment);

        boolean business(DeploymentHandle deployment);

        DeploymentAnchor anchor(DeploymentHandle deployment);

        DeploymentHandle restore(DeploymentAnchor anchor);

        int sideEffectCount();
    }
}
