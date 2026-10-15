package dev.agentharness.fullstack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 只执行 javac/java/jar 固定入口的 digest-pinned Docker runner。 */
public final class DockerRunner implements AutoCloseable {
    public static final String IMAGE = "mirror.gcr.io/library/eclipse-temurin@sha256:420933bf319e6aff4bec22eff8d2a31c824df5d853432e3dc0f46d6ea8dd5919";
    private static final int STREAM_LIMIT = 1_048_576;
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final Set<String> ENTRYPOINTS = Set.of("javac", "java", "jar");
    private static final List<String> SOURCES = List.of("ConfigHttpServer.java", "FeatureStore.java", "GeneratedAppChecks.java", "VersionPolicy.java", "GeneratedVersionPolicy.java");
    private final Path baseJar;
    private final ThreadPoolExecutor executor;
    private final PreviewReleaseAdapter.CommandRunner commandRunner;
    private final Duration timeout;
    private final int streamLimit;
    private final ProcessLauncher processLauncher;

    public DockerRunner(Path baseJar) {
        this(
                baseJar,
                new PreviewReleaseAdapter.CommandExecutor(Duration.ofSeconds(10), 65_536),
                TIMEOUT,
                STREAM_LIMIT,
                argv -> new ProcessBuilder(argv).start());
    }

    DockerRunner(Path baseJar, PreviewReleaseAdapter.CommandRunner commandRunner) {
        this(
                baseJar,
                commandRunner,
                TIMEOUT,
                STREAM_LIMIT,
                argv -> new ProcessBuilder(argv).start());
    }

    DockerRunner(
            Path baseJar,
            PreviewReleaseAdapter.CommandRunner commandRunner,
            Duration timeout,
            int streamLimit) {
        this(
                baseJar,
                commandRunner,
                timeout,
                streamLimit,
                argv -> new ProcessBuilder(argv).start());
    }

    DockerRunner(
            Path baseJar,
            PreviewReleaseAdapter.CommandRunner commandRunner,
            Duration timeout,
            int streamLimit,
            ProcessLauncher processLauncher) {
        // 基础 shaded JAR 必须是既有未跟随链接的普通文件。
        this.baseJar = trustedFile(baseJar, "base jar");
        // 固定镜像只读检查也走有界执行器，测试可证明缺失时零副作用。
        this.commandRunner = java.util.Objects.requireNonNull(commandRunner, "commandRunner");
        // 每次执行使用同一个受控 deadline，测试可缩短但不能设为零或负数。
        this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("runner timeout invalid");
        }
        // 每条输出流分别计数，测试可缩小上限以稳定触发溢出。
        if (streamLimit <= 0) {
            throw new IllegalArgumentException("runner stream limit invalid");
        }
        this.streamLimit = streamLimit;
        // 生产 launcher 固定使用 ProcessBuilder；测试注入仅验证本机父子进程终止语义。
        this.processLauncher = java.util.Objects.requireNonNull(processLauncher, "processLauncher");
        // 并发固定为 2，等待队列固定为 8，饱和时直接拒绝。
        this.executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
    }

    public int maxConcurrency() { return 2; }
    public int queueCapacity() { return 8; }

    public Future<RunnerResult> submitBuildAndTest(Path source, Path output, boolean expected) {
        // 所有并发任务进入同一有界执行器。
        return executor.submit(() -> buildAndTest(source, output, expected));
    }

    public RunnerResult buildAndTest(Path sourceRoot, Path requestedOutput, boolean expected) {
        // 任何 run 副作用前先确认本地固定 digest 存在且引用精确匹配。
        verifyPinnedImage();
        Path source = trustedDirectory(sourceRoot, "source");
        Path output = createOutput(requestedOutput);
        validateSourceSet(source);
        createWritableDirectory(output.resolve("classes"));
        Path data = createWritableDirectory(output.resolve("data"));
        List<StepResult> steps = new ArrayList<>();
        // 编译只读源码，classes 是唯一可写输出。
        StepResult compile = execute(javacCommand(source, output));
        steps.add(compile);
        if (compile.exitCode() != 0) return combined(compile.exitCode(), steps, "");
        // 打包只读 classes/web 到单一候选 JAR。
        StepResult jar = execute(jarCommand(source, output));
        steps.add(jar);
        if (jar.exitCode() != 0) return combined(jar.exitCode(), steps, "");
        Path candidate = trustedFile(output.resolve("candidate.jar"), "candidate jar");
        String candidateBefore = sha256(candidate);
        String baseBefore = sha256(baseJar);
        // 双 token 与只含 hash 的配置由固定非 root 容器写入专用 data mount。
        StepResult token = execute(tokenCommand(data));
        steps.add(token);
        if (token.exitCode() != 0) return combined(token.exitCode(), steps, candidateBefore);
        // 运行真实 loopback API/页面验收与只读挂载负例。
        StepResult test = execute(testCommand(candidate, data, expected));
        steps.add(test);
        // 容器动作后复核基础 JAR 与候选自身 hash 未变化。
        if (!candidateBefore.equals(sha256(candidate)) || !baseBefore.equals(sha256(baseJar))) {
            throw new IllegalStateException("read-only runner artifact changed");
        }
        return combined(test.exitCode(), steps, candidateBefore);
    }

    public DockerCommand javacCommand(Path sourceRoot, Path outputRoot) {
        Path source = trustedDirectory(sourceRoot, "source");
        Path output = trustedDirectory(outputRoot, "output");
        List<String> args = new ArrayList<>(List.of("--release", "17", "-proc:none", "-cp", "/base/agent-harness-java.jar", "-d", "/out/classes"));
        SOURCES.forEach(name -> args.add("/input/" + name));
        return command("javac", args, List.of(ro(source, "/input"), ro(baseJar, "/base/agent-harness-java.jar"), rw(output, "/out")));
    }

    public DockerCommand jarCommand(Path sourceRoot, Path outputRoot) {
        Path source = trustedDirectory(sourceRoot, "source");
        Path output = trustedDirectory(outputRoot, "output");
        return command("jar", List.of("--create", "--file", "/out/candidate.jar", "--main-class", "fullstack.generated.ConfigHttpServer", "-C", "/out/classes", ".", "-C", "/input", "web"), List.of(ro(source, "/input"), rw(output, "/out")));
    }

    public DockerCommand testCommand(Path candidateJar, Path dataRoot, boolean expected) {
        Path candidate = trustedFile(candidateJar, "candidate jar");
        Path data = trustedDirectory(dataRoot, "data");
        return command(
                "java",
                List.of(
                        "-cp",
                        "/candidate/candidate.jar:/base/agent-harness-java.jar",
                        "fullstack.generated.GeneratedAppChecks",
                        "self-test",
                        "/data/test-smoke.token",
                        "/data/browser-handoff.token",
                        "/data/auth-config.json",
                        Boolean.toString(expected),
                        "/base/agent-harness-java.jar",
                        "/candidate/candidate.jar"),
                List.of(
                        ro(candidate, "/candidate/candidate.jar"),
                        ro(baseJar, "/base/agent-harness-java.jar"),
                        rw(data, "/data")));
    }

    public DockerCommand fixedCommand(String entrypoint, List<String> arguments) {
        // 固定探针只读挂载已验证基础 JAR，不开放任意宿主路径。
        return command(
                entrypoint,
                arguments,
                List.of(ro(baseJar, "/base/agent-harness-java.jar")));
    }

    private DockerCommand tokenCommand(Path data) {
        return command(
                "java",
                List.of(
                        "-cp",
                        "/base/agent-harness-java.jar",
                        "dev.agentharness.fullstack.BootstrapTokenFile",
                        "/data"),
                List.of(ro(baseJar, "/base/agent-harness-java.jar"), rw(data, "/data")));
    }

    private DockerCommand command(String entrypoint, List<String> args, List<String> mounts) {
        if (!ENTRYPOINTS.contains(entrypoint)) throw new IllegalArgumentException("runner entrypoint is not allowlisted");
        // 服务端随机 UUID 不接受模型、CLI 或文件内容，避免名称可预测或跨任务碰撞。
        String containerName =
                "agent-harness-run-" + UUID.randomUUID().toString().replace("-", "");
        // 不使用 --rm；Docker CLI 结束后由 finally 对精确名称执行有界 rm -f 并复核。
        List<String> argv = new ArrayList<>(List.of("docker", "run", "--name", containerName, "--pull=never", "--platform", "linux/arm64", "--network", "none", "--read-only", "--user", "65532:65532", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--cpus", "1", "--memory", "512m", "--pids-limit", "64", "--stop-timeout", "5", "--tmpfs", "/tmp:rw,noexec,nosuid,size=16777216"));
        for (String mount : mounts) { argv.add("--mount"); argv.add(mount); }
        argv.add("--entrypoint"); argv.add(entrypoint); argv.add(IMAGE); argv.addAll(args);
        return new DockerCommand(argv, containerName);
    }

    private StepResult execute(DockerCommand command) {
        Process process = null;
        CompletableFuture<byte[]> out = null;
        CompletableFuture<byte[]> err = null;
        RuntimeException failure = null;
        boolean interrupted = false;
        // CLI、输出读取与正常 join 共用整体执行 deadline。
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            // argv 直接交给 ProcessBuilder，禁止 sh -c。
            process = processLauncher.start(command.argv());
            Process running = process;
            CompletableFuture<Void> readerFailure = new CompletableFuture<>();
            out = CompletableFuture.supplyAsync(() -> readBounded(running.getInputStream(), streamLimit, readerFailure));
            err = CompletableFuture.supplyAsync(() -> readBounded(running.getErrorStream(), streamLimit, readerFailure));
            // 进程退出或任一 reader 失败都会立即唤醒；超时不等待完整 deadline 后再终止。
            CompletableFuture.anyOf(process.onExit(), readerFailure)
                    .get(remaining(deadline), TimeUnit.NANOSECONDS);
            byte[] stdout = out.get(remaining(deadline), TimeUnit.NANOSECONDS);
            byte[] stderr = err.get(remaining(deadline), TimeUnit.NANOSECONDS);
            return new StepResult(
                    process.exitValue(),
                    new String(stdout, StandardCharsets.UTF_8),
                    new String(stderr, StandardCharsets.UTF_8));
        } catch (ExecutionException exception) {
            failure = hasCause(exception, OutputLimitException.class)
                    ? new IllegalStateException("docker runner output limit exceeded")
                    : new IllegalStateException("docker runner stream failed", exception);
            throw failure;
        } catch (TimeoutException exception) {
            failure = new IllegalStateException("docker runner timeout", exception);
            throw failure;
        } catch (IOException exception) {
            failure = new IllegalStateException("docker runner start failed", exception);
            throw failure;
        } catch (InterruptedException exception) {
            // 清除中断位后完成有界清理，退出 finally 时再恢复调用线程中断状态。
            interrupted = true;
            Thread.interrupted();
            failure = new IllegalStateException("docker runner interrupted", exception);
            throw failure;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            RuntimeException postFailure = null;
            // 所有异常路径统一在这里快照并终止完整进程树。
            long cleanupDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            try {
                if (process != null) {
                    // descendants 与 root 都有界终止，避免只杀 Docker CLI 根进程。
                    terminateCli(process, cleanupDeadline);
                }
            } catch (InterruptedException exception) {
                // 不吞中断；先清除标记完成 daemon 精确清理，退出时恢复。
                interrupted = true;
                Thread.interrupted();
                postFailure =
                        new IllegalStateException("docker runner termination interrupted", exception);
            } catch (RuntimeException exception) {
                postFailure = exception;
            } finally {
                try {
                    if (process != null) {
                        // 即使进程树终止失败也关闭三条流并继续精确 daemon 清理。
                        closeQuietly(process.getInputStream());
                        closeQuietly(process.getErrorStream());
                        closeQuietly(process.getOutputStream());
                    }
                    // 关闭流后用同一个清理 deadline 回收已创建的 reader；启动失败时两者为空。
                    postFailure = joinReaders(out, err, cleanupDeadline, postFailure);
                } catch (RuntimeException readerJoinFailure) {
                    // reader join 的二次异常只作为附加失败，不能阻断 daemon 精确清理。
                    postFailure = merge(postFailure, readerJoinFailure);
                } finally {
                    try {
                        // 最外层 finally 始终清理本次精确名称，start IOException 也不能跳过。
                        cleanupContainer(command.containerName());
                    } catch (RuntimeException cleanupFailure) {
                        postFailure = merge(postFailure, cleanupFailure);
                    } finally {
                        if (interrupted) {
                            // daemon 清理完成后恢复调用线程中断状态。
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            if (postFailure != null) {
                if (failure != null) {
                    // 原始 start/执行失败保持主异常，清理或终止失败只作为 suppressed 证据。
                    failure.addSuppressed(postFailure);
                } else {
                    throw postFailure;
                }
            }
        }
    }

    StepResult executeForTest(DockerCommand command) {
        // 同包真实 Docker 回归只调用生产执行路径，不暴露通用命令执行能力。
        return execute(command);
    }

    private void cleanupContainer(String containerName) {
        // rm -f 由独立有界双流执行器执行并等待结束。
        commandRunner.run(List.of("docker", "rm", "-f", containerName));
        // 删除后再精确 inspect；不存在才算清理完成，不能只信 rm 的退出码。
        PreviewReleaseAdapter.CommandResult inspected =
                commandRunner.run(List.of("docker", "container", "inspect", containerName));
        String inspectError =
                new String(inspected.stderr(), StandardCharsets.UTF_8)
                        .toLowerCase(java.util.Locale.ROOT);
        if (inspected.success() || !inspectError.contains("no such container")) {
            throw new IllegalStateException("docker runner cleanup left container");
        }
        // 只有 inspect 明确返回 no-such-container 才确认 daemon 中已经不存在。
    }

    void cleanupContainerForTest(String containerName) {
        // 单元测试验证 rm 成功但 inspect 未明确 not-found 时仍然失败关闭。
        cleanupContainer(containerName);
    }

    private static void terminateCli(Process process, long deadline) throws InterruptedException {
        // ProcessHandle.descendants() 递归快照 root 当前全部后代。
        List<ProcessHandle> descendants = process.descendants().toList();
        // 从叶到根逐个先温和终止、再按需要强制终止并有界等待。
        for (int index = descendants.size() - 1; index >= 0; index--) {
            terminateHandle(descendants.get(index), deadline);
        }
        // 后代确认退出后才终止 root，避免 root 先退导致后代从快照中丢失。
        terminateHandle(process.toHandle(), deadline);
    }

    private static void terminateHandle(ProcessHandle process, long deadline)
            throws InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        long graceful = Math.min(Duration.ofMillis(200).toNanos(), Math.max(1L, deadline - System.nanoTime()));
        try {
            process.onExit().get(graceful, TimeUnit.NANOSECONDS);
            return;
        } catch (ExecutionException exception) {
            throw new IllegalStateException("docker runner process-tree wait failed", exception);
        } catch (TimeoutException forceRequired) {
            process.destroyForcibly();
        }
        awaitExit(process, deadline);
    }

    private static void awaitExit(ProcessHandle process, long deadline)
            throws InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        try {
            process.onExit().get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("docker runner process-tree wait failed", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("docker runner process-tree did not terminate", exception);
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("docker runner deadline exceeded");
        }
        return remaining;
    }

    private static RuntimeException joinReaders(
            CompletableFuture<byte[]> out,
            CompletableFuture<byte[]> err,
            long deadline,
            RuntimeException existing) {
        RuntimeException failure = existing;
        if (out != null) {
            failure = joinReader(out, deadline, failure);
        }
        if (err != null) {
            failure = joinReader(err, deadline, failure);
        }
        return failure;
    }

    private static RuntimeException joinReader(
            CompletableFuture<byte[]> reader, long deadline, RuntimeException existing) {
        RuntimeException failure = existing;
        try {
            reader.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            RuntimeException next =
                    new IllegalStateException("docker runner reader join interrupted", exception);
            failure = merge(failure, next);
        } catch (ExecutionException exception) {
            if (!hasCause(exception, OutputLimitException.class)) {
                failure =
                        merge(
                                failure,
                                new IllegalStateException(
                                        "docker runner stream failed", exception));
            }
        } catch (TimeoutException exception) {
            failure =
                    merge(
                            failure,
                            new IllegalStateException(
                                    "docker runner reader did not terminate", exception));
        }
        return failure;
    }

    private static RuntimeException merge(RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private void verifyPinnedImage() {
        // 使用注入的有界双流执行器执行只读 inspect。
        PreviewReleaseAdapter.CommandResult result =
                commandRunner.run(
                                List.of(
                                        "docker",
                                        "image",
                                        "inspect",
                                        "--format",
                                        "{{json .RepoDigests}}",
                                        IMAGE));
        // 缺失或输出不含完整 digest 时关闭 runner，后续不会创建容器。
        if (!result.success()
                || !new String(result.stdout(), StandardCharsets.UTF_8).contains(IMAGE)) {
            throw new IllegalStateException("pinned docker image unavailable");
        }
    }

    private static byte[] readBounded(
            InputStream input, int limit, CompletableFuture<Void> failureSignal) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0; int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    OutputLimitException overflow = new OutputLimitException();
                    failureSignal.completeExceptionally(overflow);
                    throw overflow;
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            IllegalStateException failure =
                    new IllegalStateException("docker stream read failed", exception);
            failureSignal.completeExceptionally(failure);
            throw failure;
        }
    }

    private void validateSourceSet(Path source) {
        SOURCES.forEach(name -> trustedFile(source.resolve(name), "candidate source"));
        List.of("index.html", "styles.css", "app.js").forEach(name -> trustedFile(source.resolve("web").resolve(name), "candidate web file"));
        try (var paths = Files.walk(source)) { if (paths.anyMatch(Files::isSymbolicLink)) throw new IllegalArgumentException("candidate source links are not allowed"); }
        catch (IOException exception) { throw new IllegalArgumentException("candidate source cannot be inspected", exception); }
    }

    private static Path createOutput(Path requested) {
        try {
            Path normalized = requested.toAbsolutePath().normalize();
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return trustedDirectory(normalized, "output");
            trustedDirectory(normalized.getParent(), "output parent");
            return Files.createDirectory(normalized).toRealPath();
        } catch (IOException exception) { throw new IllegalArgumentException("output cannot be created", exception); }
    }

    private static Path createWritableDirectory(Path path) {
        try {
            Path directory = Files.createDirectory(path);
            try { Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)); }
            catch (UnsupportedOperationException ignored) { }
            return directory.toRealPath();
        } catch (IOException exception) { throw new IllegalStateException("runner output directory failed", exception); }
    }

    private static Path trustedDirectory(Path path, String label) {
        try { Path n = path.toAbsolutePath().normalize(); if (Files.isSymbolicLink(n) || !Files.isDirectory(n, LinkOption.NOFOLLOW_LINKS) || !n.toRealPath().equals(n)) throw new IllegalArgumentException(label + " must be a real directory"); return n; }
        catch (IOException exception) { throw new IllegalArgumentException(label + " cannot be verified", exception); }
    }

    private static Path trustedFile(Path path, String label) {
        try { Path n = path.toAbsolutePath().normalize(); if (Files.isSymbolicLink(n) || !Files.isRegularFile(n, LinkOption.NOFOLLOW_LINKS) || !n.toRealPath().equals(n)) throw new IllegalArgumentException(label + " must be a real file"); return n; }
        catch (IOException exception) { throw new IllegalArgumentException(label + " cannot be verified", exception); }
    }

    private static String ro(Path source, String destination) { return "type=bind,src=" + source + ",dst=" + destination + ",readonly"; }
    private static String rw(Path source, String destination) { return "type=bind,src=" + source + ",dst=" + destination; }

    private static RunnerResult combined(int exit, List<StepResult> steps, String sha) {
        return new RunnerResult(exit, steps.stream().map(StepResult::stdout).reduce("", String::concat), steps.stream().map(StepResult::stderr).reduce("", String::concat), sha, steps.size());
    }

    private static String sha256(Path file) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
        catch (Exception exception) { throw new IllegalStateException("runner hash failed", exception); }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) { for (Throwable current = error; current != null; current = current.getCause()) if (type.isInstance(current)) return true; return false; }
    private static void closeQuietly(AutoCloseable value) { try { value.close(); } catch (Exception ignored) { } }

    @Override public void close() { executor.shutdownNow(); }
    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> argv) throws IOException;
    }
    record StepResult(int exitCode, String stdout, String stderr) {}
    private static final class OutputLimitException extends RuntimeException {}
}
