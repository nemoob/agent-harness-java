package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DockerRunnerCommandTest {
    @Test
    void buildCommandUsesPinnedImageFixedEntrypointAndSecurityFlags() throws Exception {
        Path source = directory("source");
        Path output = directory("output");
        Path baseJar = file("base.jar");
        DockerRunner runner = new DockerRunner(baseJar);

        DockerCommand command = runner.javacCommand(source, output);
        List<String> argv = command.argv();

        assertEquals("docker", argv.get(0));
        assertEquals("--name", argv.get(2));
        assertEquals(command.containerName(), argv.get(3));
        assertTrue(command.containerName().matches("agent-harness-run-[0-9a-f]{32}"));
        assertFalse(argv.contains("--rm"));
        assertTrue(argv.contains(DockerRunner.IMAGE));
        assertTrue(argv.contains("--pull=never"));
        assertTrue(argv.contains("none"));
        assertTrue(argv.contains("--read-only"));
        assertTrue(argv.contains("--cap-drop"));
        assertTrue(argv.contains("ALL"));
        assertTrue(argv.contains("no-new-privileges"));
        assertTrue(argv.contains("1"));
        assertTrue(argv.contains("512m"));
        assertTrue(argv.contains("64"));
        assertTrue(argv.contains("65532:65532"));
        assertTrue(argv.contains("javac"));
        assertTrue(argv.contains("--release"));
        assertTrue(argv.contains("17"));
        assertTrue(argv.contains("-proc:none"));
        assertTrue(argv.stream().anyMatch(value -> value.contains("dst=/input") && value.contains("readonly")));
        assertTrue(argv.stream().anyMatch(value -> value.contains("dst=/base") && value.contains("readonly")));
        assertTrue(argv.stream().anyMatch(value -> value.contains("dst=/out") && !value.contains("readonly")));
        assertFalse(argv.contains("sh"));
        assertFalse(argv.contains("-c"));
        assertFalse(argv.stream().anyMatch(value -> value.contains("docker.sock")));
        assertFalse(argv.stream().anyMatch(value -> value.contains("ANTHROPIC")));
        DockerCommand second = runner.javacCommand(source, output);
        assertNotEquals(command.containerName(), second.containerName());
    }

    @Test
    void testCommandMountsCandidateAndBaselineReadOnlyWithDedicatedDataOnly() throws Exception {
        Path candidate = file("candidate.jar");
        Path data = directory("data");
        Path baseJar = file("base-test.jar");
        DockerRunner runner = new DockerRunner(baseJar);

        DockerCommand command = runner.testCommand(candidate, data, true);

        assertTrue(command.argv().contains("java"));
        assertTrue(command.argv().stream().anyMatch(value -> value.contains("dst=/candidate") && value.contains("readonly")));
        assertTrue(command.argv().stream().anyMatch(value -> value.contains("dst=/base") && value.contains("readonly")));
        assertTrue(command.argv().stream().anyMatch(value -> value.contains("dst=/data") && !value.contains("readonly")));
        assertFalse(command.argv().stream().anyMatch(value -> value.contains("/input")));
        assertFalse(command.argv().stream().anyMatch(value -> value.contains("ANTHROPIC")));
    }

    @Test
    void rejectsUnexpectedEntrypointAndKeepsQueueBounded() throws Exception {
        DockerRunner runner = new DockerRunner(file("base-queue.jar"));
        assertThrows(IllegalArgumentException.class, () -> runner.fixedCommand("bash", List.of()));
        assertEquals(2, runner.maxConcurrency());
        assertEquals(8, runner.queueCapacity());
    }

    @Test
    void missingPinnedImageStopsBeforeOutputOrContainerSideEffects() throws Exception {
        Path base = file("base-missing-image.jar");
        Path output =
                Path.of(
                        "target",
                        "docker-command-test",
                        UUID.randomUUID() + "-missing-output");
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        PreviewReleaseAdapter.CommandRunner missing =
                argv -> {
                    calls.incrementAndGet();
                    assertEquals(java.util.List.of("docker", "image", "inspect"), argv.subList(0, 3));
                    return new PreviewReleaseAdapter.CommandResult(
                            false, new byte[0], new byte[0]);
                };
        try (DockerRunner runner = new DockerRunner(base, missing)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> runner.buildAndTest(Path.of("unused"), output, true));
        }
        assertEquals(1, calls.get());
        assertFalse(Files.exists(output));
    }

    @Test
    void cleanupRequiresExplicitInspectNotFoundEvenWhenRemoveReportsSuccess() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        PreviewReleaseAdapter.CommandRunner uncertain =
                argv -> {
                    int call = calls.getAndIncrement();
                    if (call == 0) {
                        assertEquals(java.util.List.of("docker", "rm", "-f"), argv.subList(0, 3));
                        return new PreviewReleaseAdapter.CommandResult(
                                true, new byte[0], new byte[0]);
                    }
                    assertEquals(
                            java.util.List.of("docker", "container", "inspect"),
                            argv.subList(0, 3));
                    return new PreviewReleaseAdapter.CommandResult(
                            false,
                            new byte[0],
                            "daemon unavailable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                };
        try (DockerRunner runner =
                new DockerRunner(file("base-cleanup-inspect.jar"), uncertain)) {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            runner.cleanupContainerForTest(
                                    "agent-harness-run-" + "a".repeat(32)));
        }
        assertEquals(2, calls.get());
    }

    @Test
    void startIOExceptionKeepsOriginalCauseAndStillRunsExactCleanup() throws Exception {
        List<List<String>> calls = new ArrayList<>();
        PreviewReleaseAdapter.CommandRunner cleanup =
                argv -> {
                    calls.add(List.copyOf(argv));
                    if (argv.get(1).equals("rm")) {
                        return new PreviewReleaseAdapter.CommandResult(
                                true, new byte[0], new byte[0]);
                    }
                    return new PreviewReleaseAdapter.CommandResult(
                            false,
                            new byte[0],
                            "No such container".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                };
        try (DockerRunner runner =
                new DockerRunner(
                        file("base-start-failure.jar"),
                        cleanup,
                        Duration.ofSeconds(1),
                        1_024,
                        ignored -> {
                            throw new IOException("controlled-start-failure");
                        })) {
            DockerCommand command = runner.fixedCommand("java", List.of("-version"));
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> runner.executeForTest(command));
            assertEquals("docker runner start failed", failure.getMessage());
            assertTrue(failure.getCause() instanceof IOException);
            assertEquals("controlled-start-failure", failure.getCause().getMessage());
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(
                    List.of("docker", "rm", "-f", command.containerName()), calls.get(0));
            assertEquals(
                    List.of("docker", "container", "inspect", command.containerName()),
                    calls.get(1));
        }
        assertEquals(2, calls.size());
    }

    @Test
    void unknownInspectIsSuppressedUnderOriginalStartIOException() throws Exception {
        List<List<String>> calls = new ArrayList<>();
        PreviewReleaseAdapter.CommandRunner uncertainCleanup =
                argv -> {
                    calls.add(List.copyOf(argv));
                    if (argv.get(1).equals("rm")) {
                        return new PreviewReleaseAdapter.CommandResult(
                                true, new byte[0], new byte[0]);
                    }
                    return new PreviewReleaseAdapter.CommandResult(
                            false,
                            new byte[0],
                            "daemon unavailable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                };
        try (DockerRunner runner =
                new DockerRunner(
                        file("base-start-cleanup-failure.jar"),
                        uncertainCleanup,
                        Duration.ofSeconds(1),
                        1_024,
                        ignored -> {
                            throw new IOException("controlled-start-failure");
                        })) {
            DockerCommand command = runner.fixedCommand("java", List.of("-version"));
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> runner.executeForTest(command));
            assertEquals("docker runner start failed", failure.getMessage());
            assertTrue(failure.getCause() instanceof IOException);
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(
                    "docker runner cleanup left container",
                    failure.getSuppressed()[0].getMessage());
            assertEquals(
                    List.of("docker", "rm", "-f", command.containerName()), calls.get(0));
            assertEquals(
                    List.of("docker", "container", "inspect", command.containerName()),
                    calls.get(1));
        }
        assertEquals(2, calls.size());
    }

    @Test
    void outputOverflowTerminatesParentAndChildBeforeExactContainerCleanup() throws Exception {
        for (String stream : List.of("stdout", "stderr")) {
            Path pidFile = directory("tree-overflow").resolve(stream + ".pids");
            try (DockerRunner runner = processTreeRunner(Duration.ofSeconds(5), 256, stream, pidFile)) {
                assertThrows(
                        IllegalStateException.class,
                        () -> runner.executeForTest(runner.fixedCommand("java", List.of("-version"))));
            }
            assertProcessTreeStopped(pidFile);
        }
    }

    @Test
    void timeoutTerminatesParentAndChildBeforeExactContainerCleanup() throws Exception {
        Path pidFile = directory("tree-timeout").resolve("timeout.pids");
        try (DockerRunner runner = processTreeRunner(Duration.ofMillis(250), 1_024, "sleep", pidFile)) {
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> runner.executeForTest(runner.fixedCommand("java", List.of("-version"))));
            assertTrue(failure.getMessage().contains("timeout"));
        }
        assertProcessTreeStopped(pidFile);
    }

    @Test
    void interruptionTerminatesParentAndChildAndRestoresInterruptFlag() throws Exception {
        Path pidFile = directory("tree-interrupt").resolve("interrupt.pids");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        try (DockerRunner runner = processTreeRunner(Duration.ofSeconds(10), 1_024, "sleep", pidFile)) {
            Thread caller =
                    new Thread(
                            () -> {
                                try {
                                    runner.executeForTest(
                                            runner.fixedCommand("java", List.of("-version")));
                                } catch (Throwable failure) {
                                    observed.set(failure);
                                    interrupted.set(Thread.currentThread().isInterrupted());
                                }
                            },
                            "docker-runner-interrupt-test");
            caller.start();
            waitForPidFile(pidFile);
            caller.interrupt();
            caller.join(Duration.ofSeconds(10).toMillis());
            assertFalse(caller.isAlive());
        }
        assertTrue(observed.get() instanceof IllegalStateException);
        assertTrue(observed.get().getMessage().contains("interrupted"));
        assertTrue(interrupted.get());
        assertProcessTreeStopped(pidFile);
    }

    private static DockerRunner processTreeRunner(
            Duration timeout, int streamLimit, String mode, Path pidFile) throws Exception {
        Path javaExecutable =
                Path.of(System.getProperty("java.home"), "bin", "java")
                        .toAbsolutePath()
                        .normalize();
        String classpath =
                Path.of("target", "test-classes").toAbsolutePath()
                        + File.pathSeparator
                        + Path.of("target", "classes").toAbsolutePath();
        DockerRunner.ProcessLauncher launcher =
                ignored ->
                        new ProcessBuilder(
                                        javaExecutable.toString(),
                                        "-cp",
                                        classpath,
                                        ProcessTreeParent.class.getName(),
                                        mode,
                                        pidFile.toString())
                                .start();
        PreviewReleaseAdapter.CommandRunner cleanup =
                argv -> {
                    if (argv.subList(0, 3).equals(List.of("docker", "rm", "-f"))) {
                        return new PreviewReleaseAdapter.CommandResult(
                                true, new byte[0], new byte[0]);
                    }
                    assertEquals(
                            List.of("docker", "container", "inspect"), argv.subList(0, 3));
                    return new PreviewReleaseAdapter.CommandResult(
                            false,
                            new byte[0],
                            "No such container".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                };
        return new DockerRunner(
                file("base-tree.jar"), cleanup, timeout, streamLimit, launcher);
    }

    private static void waitForPidFile(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.isRegularFile(pidFile) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.isRegularFile(pidFile), "parent process did not publish PIDs");
    }

    private static void assertProcessTreeStopped(Path pidFile) throws Exception {
        waitForPidFile(pidFile);
        for (String value : Files.readString(pidFile).trim().split(",")) {
            long pid = Long.parseLong(value);
            assertFalse(
                    ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "process still alive: " + pid);
        }
    }

    public static final class ProcessTreeParent {
        private ProcessTreeParent() {}

        public static void main(String[] args) throws Exception {
            String classpath = System.getProperty("java.class.path");
            Process child =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    classpath,
                                    ProcessTreeChild.class.getName())
                            .start();
            Files.writeString(
                    Path.of(args[1]), ProcessHandle.current().pid() + "," + child.pid());
            if (args[0].equals("stdout")) {
                while (true) {
                    System.out.print("x".repeat(4_096));
                    System.out.flush();
                }
            }
            if (args[0].equals("stderr")) {
                while (true) {
                    System.err.print("x".repeat(4_096));
                    System.err.flush();
                }
            }
            Thread.sleep(Duration.ofMinutes(5).toMillis());
        }
    }

    public static final class ProcessTreeChild {
        private ProcessTreeChild() {}

        public static void main(String[] args) throws Exception {
            Thread.sleep(Duration.ofMinutes(5).toMillis());
        }
    }

    private static Path directory(String name) throws Exception {
        Path path = Path.of("target", "docker-command-test", UUID.randomUUID() + "-" + name);
        Files.createDirectories(path);
        return path.toRealPath();
    }

    private static Path file(String name) throws Exception {
        Path parent = directory("files");
        Path file = parent.resolve(name);
        Files.write(file, new byte[] {0});
        return file.toRealPath();
    }
}
