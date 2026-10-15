package dev.agentharness.fullstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 以固定 candidate 与受审 sidecar gateway 启动 loopback preview。 */
public final class PreviewReleaseAdapter implements ReleaseGate.Adapter, AutoCloseable {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int OUTPUT_LIMIT = 1_048_576;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private final Path baseJar;
    private final Path runtimeRoot;
    private final String gatewayImage;
    private final CommandRunner commandExecutor;
    private final FailurePlan failurePlan;
    private volatile boolean gatewayImageReady;
    private final Map<String, RuntimeDeployment> deployments = new ConcurrentHashMap<>();
    private final Map<String, SmokeSession> smokeSessions = new ConcurrentHashMap<>();
    private final List<String> ledger = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger effects = new AtomicInteger();
    private final Set<String> preserved = ConcurrentHashMap.newKeySet();

    public PreviewReleaseAdapter(Path baseJar, Path runtimeRoot) {
        this(
                baseJar,
                runtimeRoot,
                new CommandExecutor(COMMAND_TIMEOUT, OUTPUT_LIMIT),
                FailurePlan.none());
    }

    PreviewReleaseAdapter(Path baseJar, Path runtimeRoot, CommandRunner commandExecutor) {
        this(baseJar, runtimeRoot, commandExecutor, FailurePlan.none());
    }

    PreviewReleaseAdapter(
            Path baseJar,
            Path runtimeRoot,
            CommandRunner commandExecutor,
            FailurePlan failurePlan) {
        // 固定基础 JAR 必须是未跟随链接的普通文件。
        this.baseJar = trustedFile(baseJar, "base jar");
        // runtime 根由调用方预建并独占，适配器拒绝链接别名。
        this.runtimeRoot = trustedDirectory(runtimeRoot, "preview runtime root");
        // Docker 子进程统一经过有界双流执行器。
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
        // 确定性失败计划只供本地回归验证切换事务，生产默认永不触发。
        this.failurePlan = Objects.requireNonNull(failurePlan, "failurePlan");
        // 每个适配器使用独立本地镜像名，关闭时可以精确清理。
        this.gatewayImage =
                "agent-harness-fixed-gateway:"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Override
    public ReleaseGate.DeploymentHandle start(
            Path candidateJar,
            String candidateSha256,
            int previewPort,
            boolean healthFault,
            boolean shadow) {
        Path candidate = trustedFile(candidateJar, "candidate jar");
        // Gate 传入的候选 hash 在容器动作前由适配器独立复算。
        if (!sha256(candidate).equals(candidateSha256)) {
            throw new IllegalArgumentException("candidate hash mismatch");
        }
        if (previewPort < 1_024 || previewPort > 65_535) {
            throw new IllegalArgumentException("preview port invalid");
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String deploymentId = "preview-" + suffix;
        String internalNetwork = "agent-harness-internal-" + suffix;
        String publishNetwork = "agent-harness-publish-" + suffix;
        String candidateContainer = "agent-harness-candidate-" + suffix;
        String gatewayContainer = "agent-harness-gateway-" + suffix;
        int gatewayPort = shadow ? freeLoopbackPort() : previewPort;
        RuntimeDeployment state = null;
        int effectsBefore = effects.get();
        try {
            // 任一 Docker 副作用前先证明固定 digest 已在本机且引用精确匹配。
            verifyPinnedImage();
            // gateway 镜像仅包含固定基础 JAR，构建阶段禁网且不拉取 tag。
            ensureGatewayImage();
            // 每次部署获得独立 control/data 根，避免 token 与业务状态跨候选串用。
            Path root = Files.createDirectory(runtimeRoot.resolve(deploymentId));
            Path control = Files.createDirectory(root.resolve("control"));
            Path data = Files.createDirectory(root.resolve("data"));
            // 非 root candidate 只获得 data mount 的写权限，control 不挂载。
            Files.setPosixFilePermissions(
                    data,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_WRITE,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_WRITE,
                            PosixFilePermission.OTHERS_EXECUTE));
            // 生产 auth material 只把 smoke 明文留在当前适配器内存。
            BootstrapTokenFile.PreviewAuthMaterial auth =
                    BootstrapTokenFile.createPreview(control);
            ReleaseGate.DeploymentHandle handle =
                    new ReleaseGate.DeploymentHandle(
                            deploymentId,
                            candidateSha256,
                            loopbackUrl(gatewayPort),
                            auth.handoffFile().toString(),
                            healthFault);
            state =
                    new RuntimeDeployment(
                            handle,
                            candidate,
                            previewPort,
                            gatewayPort,
                            candidateContainer,
                            gatewayContainer,
                            internalNetwork,
                            publishNetwork,
                            auth.authConfigFile(),
                            data,
                            auth.smokeToken(),
                            root,
                            shadow,
                            false,
                            false);
            // candidate 只连接独立 internal 网络；gateway 再连接独立 publish 网络。
            createNetwork(internalNetwork, true);
            createNetwork(publishNetwork, false);
            // shadow replacement 先以健康候选验证；可信故障只在稳定端口切换时启用。
            startCandidate(state, healthFault && !shadow);
            startGateway(state, gatewayPort);
            deployments.put(deploymentId, state);
            return handle;
        } catch (Exception exception) {
            // 任一步失败按 gateway/candidate/network 逆序清理，不保留半部署。
            CleanupResult cleanup;
            if (state != null) {
                cleanup = cleanup(state);
            } else if (effects.get() > effectsBefore) {
                List<String> residuals = new ArrayList<>();
                collect(residuals, removeContainer(gatewayContainer, "cleanup gateway"));
                collect(residuals, removeContainer(candidateContainer, "cleanup candidate"));
                collect(residuals, removeNetwork(internalNetwork, "cleanup internal network"));
                collect(residuals, removeNetwork(publishNetwork, "cleanup publish network"));
                cleanup = new CleanupResult(residuals.isEmpty(), residuals);
            } else {
                cleanup = new CleanupResult(true, List.of());
            }
            if (!cleanup.complete()) {
                CleanupException cleanupFailure =
                        new CleanupException(
                                "preview start cleanup failed", cleanup.residualResources());
                cleanupFailure.addSuppressed(exception);
                throw cleanupFailure;
            }
            throw new IllegalStateException("preview start failed", exception);
        }
    }

    @Override
    public ReleaseGate.DeploymentHandle activate(
            ReleaseGate.DeploymentHandle candidate,
            ReleaseGate.DeploymentHandle previous) {
        RuntimeDeployment next = require(candidate);
        RuntimeDeployment prior = require(previous);
        if (!next.shadow() || next.requestedPort() != prior.requestedPort()) {
            throw new IllegalStateException("preview activation invalid");
        }
        try {
            // 先停旧稳定 gateway，旧 candidate 与独立 internal 网络保持运行。
            removeGateway(prior);
            // 再移除新候选 shadow gateway，candidate 继续运行。
            removeGateway(next);
            RuntimeDeployment switched = next;
            if (next.handle().healthFault()) {
                // 可信故障开关只在切换稳定端口前重启新 candidate，不暴露给模型或请求。
                removeCandidate(next);
                startCandidate(next, true);
            }
            // 同名新 gateway 固定连接 next candidate 网络并发布稳定 loopback 端口。
            startGateway(switched, next.requestedPort());
            ReleaseGate.DeploymentHandle activeHandle =
                    new ReleaseGate.DeploymentHandle(
                            next.handle().deploymentId(),
                            next.handle().candidateSha256(),
                            loopbackUrl(next.requestedPort()),
                            next.handle().handoffTokenFile(),
                            next.handle().healthFault());
            switched =
                    next.withHandle(activeHandle)
                            .withGatewayPort(next.requestedPort())
                            .withShadow(false);
            deployments.put(switched.handle().deploymentId(), switched);
            return activeHandle;
        } catch (RuntimeException failure) {
            // 失败由 Gate 使用保留的旧 candidate/network 重建稳定 gateway。
            throw new IllegalStateException("preview activation failed", failure);
        }
    }

    @Override
    public void stop(ReleaseGate.DeploymentHandle deployment) {
        RuntimeDeployment state = require(deployment);
        // 已停止实例重复停止不产生新的副作用。
        if (state.stopped()) {
            return;
        }
        CleanupResult cleanup = cleanup(state);
        if (!cleanup.complete()) {
            // 残留时保留未停止状态，后续只能按相同精确名称重试清理。
            throw new CleanupException("preview cleanup failed", cleanup.residualResources());
        }
        deployments.put(state.handle().deploymentId(), state.withStopped(true));
        // 停止后清零尚未消费的 smoke token 内存。
        Arrays.fill(state.smokeToken(), '\0');
        smokeSessions.remove(state.handle().deploymentId());
    }

    @Override
    public boolean health(ReleaseGate.DeploymentHandle deployment) {
        RuntimeDeployment state = require(deployment);
        if (state.stopped()) {
            return false;
        }
        HttpClient client = client();
        // 容器启动存在短暂窗口，最多在固定十秒内轮询 health。
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            try {
                HttpResponse<String> response =
                        client.send(
                                request(URI.create(deployment.loopbackUrl() + "/health"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return response.statusCode() == 200
                        && "UP".equals(JSON.readTree(response.body()).path("status").asText());
            } catch (Exception retryableStartup) {
                // 启动窗口内只重试受控 loopback，不输出目标或响应正文。
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    @Override
    public boolean business(ReleaseGate.DeploymentHandle deployment) {
        RuntimeDeployment state = require(deployment);
        if (state.stopped()) {
            return false;
        }
        try {
            SmokeSession existing = smokeSessions.get(deployment.deploymentId());
            SmokeSession session =
                    existing == null ? loginSmoke(state, deployment) : existing;
            if (session == null) {
                return false;
            }
            // 已建立的服务端 session 可在 gateway 切换或 rollback 后复验，明文 token 不重放。
            return verifyBusiness(deployment, session);
        } catch (Exception exception) {
            return false;
        }
    }

    @Override
    public ReleaseGate.DeploymentAnchor anchor(ReleaseGate.DeploymentHandle deployment) {
        RuntimeDeployment state = require(deployment);
        // anchor 只暴露 handle 与候选 hash，路径和 token 留在适配器内部。
        return new ReleaseGate.DeploymentAnchor(
                state.handle(), state.handle().candidateSha256());
    }

    @Override
    public ReleaseGate.DeploymentHandle restore(ReleaseGate.DeploymentAnchor anchor) {
        RuntimeDeployment prior = require(anchor.deployment());
        if (prior.stopped()) {
            throw new IllegalStateException("rollback candidate was stopped");
        }
        // 恢复动作先幂等清理同名旧 gateway，覆盖切换发生在 create 前的失败点。
        removeGateway(prior);
        // rollback 不动态更换 target，只为仍运行的旧 candidate 在稳定端口重建固定 gateway。
        startGateway(prior, prior.requestedPort());
        ReleaseGate.DeploymentHandle restored =
                new ReleaseGate.DeploymentHandle(
                        prior.handle().deploymentId(),
                        prior.handle().candidateSha256(),
                        loopbackUrl(prior.requestedPort()),
                        prior.handle().handoffTokenFile(),
                        false);
        deployments.put(
                restored.deploymentId(),
                prior.withHandle(restored)
                        .withGatewayPort(prior.requestedPort())
                        .withShadow(false));
        return restored;
    }

    @Override
    public int sideEffectCount() {
        return effects.get();
    }

    public void preserve(ReleaseGate.DeploymentHandle deployment) {
        // 最终真实成功预览可显式保留给入口截图，其他实例由 close 清理。
        require(deployment);
        preserved.add(deployment.deploymentId());
    }

    List<String> sideEffectLedger() {
        // 测试只读取固定资源类型与动作，不包含路径、token 或请求正文。
        synchronized (ledger) {
            return List.copyOf(ledger);
        }
    }

    RuntimeNames runtimeNames(ReleaseGate.DeploymentHandle deployment) {
        RuntimeDeployment state = require(deployment);
        return new RuntimeNames(
                state.candidateContainer(),
                state.gatewayContainer(),
                state.internalNetwork(),
                state.publishNetwork(),
                state.gatewayPort());
    }

    List<RuntimeNames> allRuntimeNames() {
        // 测试可核对停止后的容器和网络确实不存在。
        return deployments.values().stream()
                .map(
                        state ->
                                new RuntimeNames(
                                        state.candidateContainer(),
                                        state.gatewayContainer(),
                                        state.internalNetwork(),
                                        state.publishNetwork(),
                                        state.gatewayPort()))
                .toList();
    }

    private SmokeSession loginSmoke(
            RuntimeDeployment state, ReleaseGate.DeploymentHandle deployment) throws Exception {
        char[] smokeChars = state.smokeToken();
        String smoke = new String(smokeChars);
        try {
            HttpClient client = client();
            URI base = URI.create(deployment.loopbackUrl());
            // smoke token 只进入 POST form body，禁止 URL/header/argv。
            String form = "token=" + URLEncoder.encode(smoke, StandardCharsets.UTF_8);
            HttpResponse<String> login =
                    client.send(
                            request(base.resolve("/login"))
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                    .POST(HttpRequest.BodyPublishers.ofString(form))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (login.statusCode() != 303) {
                return null;
            }
            String cookie =
                    login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            HttpResponse<String> response =
                    client.send(
                            request(base.resolve("/api/session"))
                                    .header("Cookie", cookie)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return null;
            }
            SmokeSession session =
                    new SmokeSession(
                            cookie,
                            JSON.readTree(response.body()).path("csrfToken").asText());
            smokeSessions.put(deployment.deploymentId(), session);
            return session;
        } finally {
            // token 首次尝试后从局部字符串不可变生命周期外移除并清零保存数组。
            Arrays.fill(smokeChars, '\0');
        }
    }

    private boolean verifyBusiness(
            ReleaseGate.DeploymentHandle deployment, SmokeSession session) throws Exception {
        HttpClient client = client();
        URI base = URI.create(deployment.loopbackUrl());
        String previewBody = "{\"clientVersion\":\"2.10\"}";
        HttpResponse<String> preview =
                client.send(
                        request(base.resolve("/api/features/homepage_recommendation/preview"))
                                .header("Content-Type", "application/json")
                                .header("Origin", loopbackUrl(require(deployment).requestedPort()))
                                .header("Cookie", session.cookie())
                                .header("X-CSRF-Token", session.csrf())
                                .POST(HttpRequest.BodyPublishers.ofString(previewBody))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode previewJson = JSON.readTree(preview.body());
        HttpResponse<String> page =
                client.send(
                        request(base.resolve("/index.html")).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return preview.statusCode() == 200
                && previewJson.path("effective").asBoolean()
                && "VERSION_ALLOWED".equals(previewJson.path("reasonCode").asText())
                && page.statusCode() == 200
                && !page.body().contains("innerHTML");
    }

    private void createNetwork(String name, boolean internal) {
        List<String> argv = new ArrayList<>(List.of("docker", "network", "create"));
        if (internal) {
            argv.add("--internal");
        }
        argv.add(name);
        execute(argv, "network create");
        effect(internal ? "CREATE_INTERNAL_NETWORK" : "CREATE_PUBLISH_NETWORK");
    }

    private void startCandidate(RuntimeDeployment state, boolean healthFault) {
        execute(candidateCommand(state, healthFault), "candidate start");
        effect("START_CANDIDATE");
    }

    private void startGateway(RuntimeDeployment state, int port) {
        // 在 create 副作用前执行一次性确定性故障点。
        failurePlan.before(FailPoint.GATEWAY_CREATE);
        execute(gatewayCreateCommand(state, port), "gateway create");
        effect("CREATE_GATEWAY");
        // gateway 先在 publish 网络创建，再连接候选专属 internal 网络。
        failurePlan.before(FailPoint.GATEWAY_CONNECT);
        execute(
                List.of(
                        "docker",
                        "network",
                        "connect",
                        state.internalNetwork(),
                        state.gatewayContainer()),
                "gateway internal connect");
        effect("CONNECT_GATEWAY_INTERNAL");
        failurePlan.before(FailPoint.GATEWAY_START);
        execute(List.of("docker", "start", state.gatewayContainer()), "gateway start");
        // ledger 关联部署与实际 loopback 端口，便于证明 shadow/stable 切换。
        effect("START_GATEWAY:" + state.handle().deploymentId() + ":" + port);
    }

    private List<String> candidateCommand(RuntimeDeployment state, boolean healthFault) {
        List<String> argv =
                new ArrayList<>(
                        List.of(
                                "docker",
                                "run",
                                "--rm",
                                "--pull=never",
                                "-d",
                                "--name",
                                state.candidateContainer(),
                                "--platform",
                                "linux/arm64",
                                "--network",
                                state.internalNetwork(),
                                "--network-alias",
                                "candidate",
                                "--read-only",
                                "--user",
                                "65532:65532",
                                "--cap-drop",
                                "ALL",
                                "--security-opt",
                                "no-new-privileges",
                                "--cpus",
                                "1",
                                "--memory",
                                "512m",
                                "--pids-limit",
                                "64",
                                "--stop-timeout",
                                "5",
                                "--tmpfs",
                                "/tmp:rw,noexec,nosuid,size=16777216"));
        // candidate、基础 JAR 与 hash-only auth config 只读，只有专用 data 可写。
        addMount(argv, state.candidateJar(), "/candidate/candidate.jar", true);
        addMount(argv, baseJar, "/base/agent-harness-java.jar", true);
        addMount(argv, state.authConfig(), "/auth/auth-config.json", true);
        addMount(argv, state.dataRoot(), "/data", false);
        argv.addAll(
                List.of(
                        "--entrypoint",
                        "java",
                        DockerRunner.IMAGE,
                        "-cp",
                        "/candidate/candidate.jar:/base/agent-harness-java.jar",
                        "fullstack.generated.ConfigHttpServer",
                        "--port",
                        "8080",
                        "--origin",
                        loopbackUrl(state.requestedPort()),
                        "--auth-config",
                        "/auth/auth-config.json",
                        "--data-root",
                        "/data",
                        "--health-fail",
                        Boolean.toString(healthFault)));
        return List.copyOf(argv);
    }

    private List<String> gatewayCreateCommand(RuntimeDeployment state, int port) {
        // gateway 无 mount、env、token 或动态 target；镜像只含受审基础 JAR。
        return List.of(
                "docker",
                "create",
                "--rm",
                "--pull=never",
                "--name",
                state.gatewayContainer(),
                "--platform",
                "linux/arm64",
                "--network",
                state.publishNetwork(),
                "--read-only",
                "--user",
                "65532:65532",
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges",
                "--cpus",
                "0.25",
                "--memory",
                "128m",
                "--pids-limit",
                "32",
                "--stop-timeout",
                "5",
                "-p",
                "127.0.0.1:" + port + ":8080",
                gatewayImage);
    }

    private synchronized void ensureGatewayImage() {
        if (gatewayImageReady) {
            return;
        }
        Path context = null;
        try {
            // 受控 context 只含固定基础 JAR 与固定 Dockerfile。
            context = Files.createDirectory(runtimeRoot.resolve("gateway-image-" + UUID.randomUUID()));
            Files.copy(baseJar, context.resolve("base.jar"));
            String dockerfile =
                    "FROM "
                            + DockerRunner.IMAGE
                            + "\nCOPY --chown=65532:65532 base.jar /opt/gateway/base.jar\n"
                            + "USER 65532:65532\n"
                            + "ENTRYPOINT [\"java\",\"-cp\",\"/opt/gateway/base.jar\",\""
                            + LoopbackGateway.class.getName()
                            + "\"]\n";
            Files.writeString(context.resolve("Dockerfile"), dockerfile);
            // 持久证据只记录固定 Dockerfile、上下文文件名与 hash，不保留候选、状态或秘密。
            Path evidence = runtimeRoot.resolve("gateway-image-evidence");
            if (!Files.exists(evidence, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(evidence);
            }
            Files.writeString(evidence.resolve("Dockerfile"), dockerfile);
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(
                            evidence.resolve("build-context-manifest.json").toFile(),
                            Map.of(
                                    "files", List.of("Dockerfile", "base.jar"),
                                    "baseJarSha256", sha256(baseJar),
                                    "dockerfileSha256", sha256(evidence.resolve("Dockerfile")),
                                    "from", DockerRunner.IMAGE,
                                    "network", "none",
                                    "pull", false));
            // --network=none 与 --pull=false 保证构建只使用本地固定 digest 和本地 JAR。
            execute(
                    List.of(
                            "docker",
                            "build",
                            "--network=none",
                            "--pull=false",
                            "--no-cache",
                            "--platform",
                            "linux/arm64",
                            "-t",
                            gatewayImage,
                            context.toString()),
                    "gateway image build");
            effect("BUILD_GATEWAY_IMAGE");
            gatewayImageReady = true;
        } catch (IOException exception) {
            throw new IllegalStateException("gateway image context failed", exception);
        } finally {
            if (context != null) {
                try {
                    Files.deleteIfExists(context.resolve("base.jar"));
                    Files.deleteIfExists(context.resolve("Dockerfile"));
                    Files.deleteIfExists(context);
                } catch (IOException ignored) {
                    // 构建 context 清理失败不改变镜像是否成功的事实。
                }
            }
        }
    }

    private void verifyPinnedImage() {
        // inspect 本身只读；失败时后续 build/network/container 均不会执行。
        CommandResult result =
                commandExecutor.run(
                        List.of(
                                "docker",
                                "image",
                                "inspect",
                                "--format",
                                "{{json .RepoDigests}}",
                                DockerRunner.IMAGE));
        // 输出必须含完整固定 digest，不能用同名 tag 或短 ID 代替。
        if (!result.success()
                || !new String(result.stdout(), StandardCharsets.UTF_8)
                        .contains(DockerRunner.IMAGE)) {
            throw new IllegalStateException("pinned docker image unavailable");
        }
    }

    private static void addMount(
            List<String> argv, Path source, String destination, boolean readOnly) {
        argv.add("--mount");
        argv.add(
                "type=bind,src="
                        + source
                        + ",dst="
                        + destination
                        + (readOnly ? ",readonly" : ""));
    }

    private CleanupResult cleanup(RuntimeDeployment state) {
        // 每项失败仍继续逆序清理，最终汇总本次部署的受控资源 ID。
        List<String> residuals = new ArrayList<>();
        collect(residuals, removeContainer(state.gatewayContainer(), "remove gateway"));
        collect(residuals, removeContainer(state.candidateContainer(), "remove candidate"));
        collect(
                residuals,
                removeNetwork(state.internalNetwork(), "remove internal network"));
        collect(
                residuals,
                removeNetwork(state.publishNetwork(), "remove publish network"));
        return new CleanupResult(residuals.isEmpty(), residuals);
    }

    private void removeGateway(RuntimeDeployment state) {
        String residual = removeContainer(state.gatewayContainer(), "remove gateway");
        if (residual != null) {
            throw new CleanupException("gateway cleanup failed", List.of(residual));
        }
    }

    private void removeCandidate(RuntimeDeployment state) {
        String residual = removeContainer(state.candidateContainer(), "remove candidate");
        if (residual != null) {
            throw new CleanupException("candidate cleanup failed", List.of(residual));
        }
    }

    private String removeContainer(String name, String label) {
        // 删除与复核都只使用 run-owned 精确名称，禁止前缀、label 或全局选择器。
        CommandResult removed = commandExecutor.run(List.of("docker", "rm", "-f", name));
        CommandResult inspected =
                commandExecutor.run(List.of("docker", "container", "inspect", name));
        if (inspected.success() || !notFound(inspected, "no such container")) {
            return "container:" + name;
        }
        if (removed.success()) {
            effect(label.toUpperCase(Locale.ROOT).replace(' ', '_'));
        }
        return null;
    }

    private String removeNetwork(String name, String label) {
        // 网络删除后也必须精确 inspect；未知命令错误不能伪装为不存在。
        CommandResult removed = commandExecutor.run(List.of("docker", "network", "rm", name));
        CommandResult inspected =
                commandExecutor.run(List.of("docker", "network", "inspect", name));
        if (inspected.success() || !notFound(inspected, "not found")) {
            return "network:" + name;
        }
        if (removed.success()) {
            effect(label.toUpperCase(Locale.ROOT).replace(' ', '_'));
        }
        return null;
    }

    private static boolean notFound(CommandResult result, String marker) {
        // 仅 Docker 明确返回资源不存在时接受幂等完成，daemon/timeout/空错误均失败关闭。
        return !result.success()
                && new String(result.stderr(), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT)
                        .contains(marker);
    }

    private static void collect(List<String> residuals, String residual) {
        if (residual != null) {
            residuals.add(residual);
        }
    }

    private void effect(String action) {
        effects.incrementAndGet();
        ledger.add(action);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                // gateway 固定只接受 HTTP/1.x，禁用 JDK cleartext h2c Upgrade 头。
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT);
    }

    private void execute(List<String> argv, String label) {
        if (!executeAllowFailure(argv, label)) {
            throw new IllegalStateException(label + " failed");
        }
    }

    private boolean executeAllowFailure(List<String> argv, String label) {
        // 标签只用于调用点分类，命令输出不会写入日志或异常。
        return commandExecutor.run(argv).success();
    }

    private static byte[] readBounded(InputStream input, int limit, Process process)
            throws IOException {
        try (InputStream stream = input;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    // 任一流超限立即终止整个进程树，避免另一管道继续阻塞。
                    terminate(process);
                    throw new IOException("preview command output limit exceeded");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void terminate(Process process) {
        // 先终止所有后代，再终止根进程，避免固定 Docker CLI 留下孤儿。
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
        try {
            // 终止后有界等待退出，不能把活进程交还调用方。
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable value) {
        try {
            value.close();
        } catch (Exception ignored) {
            // 清理错误不覆盖原始超时或输出超限结论。
        }
    }

    private RuntimeDeployment require(ReleaseGate.DeploymentHandle handle) {
        RuntimeDeployment value = deployments.get(handle.deploymentId());
        if (value == null
                || !value.handle().candidateSha256().equals(handle.candidateSha256())) {
            throw new IllegalStateException("preview deployment not found");
        }
        return value;
    }

    private static int freeLoopbackPort() {
        try (ServerSocket socket =
                new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("shadow port unavailable", exception);
        }
    }

    private static String loopbackUrl(int port) {
        return "http://127.0.0.1:" + port;
    }

    private static Path trustedFile(Path path, String label) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalized)
                    || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                    || !normalized.toRealPath().equals(normalized)) {
                throw new IllegalArgumentException(label + " must be a real file");
            }
            return normalized;
        } catch (IOException exception) {
            throw new IllegalArgumentException(label + " cannot be verified", exception);
        }
    }

    private static Path trustedDirectory(Path path, String label) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                    || !normalized.toRealPath().equals(normalized)) {
                throw new IllegalArgumentException(label + " must be a real directory");
            }
            return normalized;
        } catch (IOException exception) {
            throw new IllegalArgumentException(label + " cannot be verified", exception);
        }
    }

    private static String sha256(Path file) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(Files.readAllBytes(file)));
        } catch (Exception exception) {
            throw new IllegalStateException("candidate hash failed", exception);
        }
    }

    @Override
    public void close() {
        // 除显式截图保留实例外，关闭适配器会清理所有仍运行容器与网络。
        List<String> residuals = new ArrayList<>();
        for (RuntimeDeployment state : List.copyOf(deployments.values())) {
            if (!state.stopped() && !preserved.contains(state.handle().deploymentId())) {
                try {
                    stop(state.handle());
                } catch (CleanupException failure) {
                    // 继续其他部署的精确清理，最后统一显式报告残留。
                    residuals.addAll(failure.residualResources());
                }
            }
        }
        // 所有 gateway 停止后删除本适配器创建的固定本地镜像。
        if (gatewayImageReady
                && executeAllowFailure(
                        List.of("docker", "image", "rm", "-f", gatewayImage),
                        "remove gateway image")) {
            effect("REMOVE_GATEWAY_IMAGE");
            gatewayImageReady = false;
        }
        if (!residuals.isEmpty()) {
            throw new CleanupException("preview close cleanup failed", residuals);
        }
    }

    record CleanupResult(boolean complete, List<String> residualResources) {
        CleanupResult {
            // 清理结果只暴露不可变的受控资源 ID 集合。
            residualResources = List.copyOf(residualResources);
        }
    }

    static final class CleanupException extends IllegalStateException {
        private final List<String> residualResources;

        CleanupException(String message, List<String> residualResources) {
            super(message);
            // 禁止把命令输出、路径或 token 带入发布裁决。
            this.residualResources = List.copyOf(residualResources);
        }

        List<String> residualResources() {
            return residualResources;
        }
    }

    record RuntimeNames(
            String candidateContainer,
            String gatewayContainer,
            String internalNetwork,
            String publishNetwork,
            int gatewayPort) {}

    record CommandResult(boolean success, byte[] stdout, byte[] stderr) {}

    interface CommandRunner {
        CommandResult run(List<String> argv);
    }

    static final class CommandExecutor implements CommandRunner {
        private final Duration timeout;
        private final int outputLimit;

        CommandExecutor(Duration timeout, int outputLimit) {
            // 测试可收紧边界，生产使用固定 120 秒和 1 MiB。
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            this.outputLimit = outputLimit;
        }

        @Override
        public CommandResult run(List<String> argv) {
            Process process = null;
            ExecutorService readers = Executors.newFixedThreadPool(2);
            try {
                // 固定参数数组直接进入 ProcessBuilder，禁止 shell 解释。
                process = new ProcessBuilder(List.copyOf(argv)).start();
                Process running = process;
                // stdout 与 stderr 必须同时排水，避免任一管道填满导致子进程死锁。
                Future<byte[]> stdout =
                        readers.submit(
                                () ->
                                        readBounded(
                                                running.getInputStream(), outputLimit, running));
                Future<byte[]> stderr =
                        readers.submit(
                                () ->
                                        readBounded(
                                                running.getErrorStream(), outputLimit, running));
                // 启动读线程后立即进入整体 deadline，静默进程也能准时终止。
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    terminate(process);
                    return new CommandResult(false, new byte[0], new byte[0]);
                }
                // 进程退出后两条流必须在短时内完成，读取失败按命令失败关闭。
                byte[] out = stdout.get(5, TimeUnit.SECONDS);
                byte[] err = stderr.get(5, TimeUnit.SECONDS);
                return new CommandResult(process.exitValue() == 0, out, err);
            } catch (IOException | ExecutionException | java.util.concurrent.TimeoutException exception) {
                if (process != null) {
                    terminate(process);
                }
                return new CommandResult(false, new byte[0], new byte[0]);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (process != null) {
                    terminate(process);
                }
                return new CommandResult(false, new byte[0], new byte[0]);
            } finally {
                // 所有退出路径都关闭三条流、终止残留进程并回收读取线程。
                if (process != null && process.isAlive()) {
                    terminate(process);
                }
                readers.shutdownNow();
            }
        }
    }

    enum FailPoint {
        GATEWAY_CREATE,
        GATEWAY_CONNECT,
        GATEWAY_START
    }

    static final class FailurePlan {
        private FailPoint point;
        private int remainingMatches;

        private FailurePlan(FailPoint point, int occurrence) {
            this.point = point;
            this.remainingMatches = occurrence;
        }

        static FailurePlan none() {
            return new FailurePlan(null, 0);
        }

        static FailurePlan on(FailPoint point, int occurrence) {
            if (occurrence <= 0) {
                throw new IllegalArgumentException("failure occurrence must be positive");
            }
            return new FailurePlan(Objects.requireNonNull(point, "point"), occurrence);
        }

        synchronized void schedule(FailPoint point, int occurrence) {
            if (occurrence <= 0) {
                throw new IllegalArgumentException("failure occurrence must be positive");
            }
            // 新故障计划覆盖已经消费完的旧计划，供同一真实 adapter 连续验证三个切换点。
            this.point = Objects.requireNonNull(point, "point");
            this.remainingMatches = occurrence;
        }

        synchronized void before(FailPoint candidate) {
            if (candidate != point || remainingMatches <= 0) {
                return;
            }
            remainingMatches--;
            if (remainingMatches == 0) {
                throw new IllegalStateException("injected gateway switch failure");
            }
        }
    }

    private record SmokeSession(String cookie, String csrf) {}

    private record RuntimeDeployment(
            ReleaseGate.DeploymentHandle handle,
            Path candidateJar,
            int requestedPort,
            int gatewayPort,
            String candidateContainer,
            String gatewayContainer,
            String internalNetwork,
            String publishNetwork,
            Path authConfig,
            Path dataRoot,
            char[] smokeToken,
            Path runtimeRoot,
            boolean shadow,
            boolean stopped,
            boolean gatewayRemoved) {
        private RuntimeDeployment withHandle(ReleaseGate.DeploymentHandle value) {
            return new RuntimeDeployment(
                    value,
                    candidateJar,
                    requestedPort,
                    gatewayPort,
                    candidateContainer,
                    gatewayContainer,
                    internalNetwork,
                    publishNetwork,
                    authConfig,
                    dataRoot,
                    smokeToken,
                    runtimeRoot,
                    shadow,
                    stopped,
                    gatewayRemoved);
        }

        private RuntimeDeployment withGatewayPort(int value) {
            return new RuntimeDeployment(
                    handle,
                    candidateJar,
                    requestedPort,
                    value,
                    candidateContainer,
                    gatewayContainer,
                    internalNetwork,
                    publishNetwork,
                    authConfig,
                    dataRoot,
                    smokeToken,
                    runtimeRoot,
                    shadow,
                    stopped,
                    false);
        }

        private RuntimeDeployment withShadow(boolean value) {
            return new RuntimeDeployment(
                    handle,
                    candidateJar,
                    requestedPort,
                    gatewayPort,
                    candidateContainer,
                    gatewayContainer,
                    internalNetwork,
                    publishNetwork,
                    authConfig,
                    dataRoot,
                    smokeToken,
                    runtimeRoot,
                    value,
                    stopped,
                    gatewayRemoved);
        }

        private RuntimeDeployment withStopped(boolean value) {
            return new RuntimeDeployment(
                    handle,
                    candidateJar,
                    requestedPort,
                    gatewayPort,
                    candidateContainer,
                    gatewayContainer,
                    internalNetwork,
                    publishNetwork,
                    authConfig,
                    dataRoot,
                    smokeToken,
                    runtimeRoot,
                    shadow,
                    value,
                    gatewayRemoved);
        }
    }
}
