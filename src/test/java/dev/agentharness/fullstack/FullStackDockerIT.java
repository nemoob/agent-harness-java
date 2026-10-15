package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

class FullStackDockerIT {
    @Test
    void realContainerProvesDefectThenRepairAndReadOnlyIsolation() throws Exception {
        Path root = Path.of("target", "fullstack-docker-it", UUID.randomUUID().toString());
        Path source = Files.createDirectories(root.resolve("source"));
        Path output = Files.createDirectories(root.resolve("output"));
        copyScaffold(source);
        writeFrontend(source);
        Files.writeString(source.resolve("GeneratedVersionPolicy.java"), defectivePolicy());
        Path baseJar = Path.of("target", "agent-harness-java.jar").toAbsolutePath().normalize();
        DockerRunner runner = new DockerRunner(baseJar);

        RunnerResult defect = runner.buildAndTest(source, output.resolve("defect"), false);
        String sourceHashBeforeRepair = sha(source.resolve("ConfigHttpServer.java"));
        String baseHashBeforeRepair = sha(baseJar);

        Files.writeString(source.resolve("GeneratedVersionPolicy.java"), repairedPolicy());
        RunnerResult repaired = runner.buildAndTest(source, output.resolve("repair"), true);

        assertEquals(0, defect.exitCode(), defect.stderr() + defect.stdout());
        assertEquals(0, repaired.exitCode(), repaired.stderr() + repaired.stdout());
        assertNotEquals(defect.candidateSha256(), repaired.candidateSha256());
        assertEquals(sourceHashBeforeRepair, sha(source.resolve("ConfigHttpServer.java")));
        assertEquals(baseHashBeforeRepair, sha(baseJar));
        assertTrue(defect.stdout().contains("SELF_TEST_PASS:false"));
        assertTrue(repaired.stdout().contains("SELF_TEST_PASS:true"));
        assertTrue(repaired.stdout().contains("READ_ONLY_BASELINE_PASS"));
        assertTrue(repaired.stdout().contains("READ_ONLY_CANDIDATE_PASS"));
        assertTrue(repaired.stdout().contains("PERSISTENCE_RESTART_PASS"));
        assertTrue(repaired.stdout().contains("DUAL_TOKEN_RESTART_PASS"));
        assertTrue(repaired.stdout().contains("STORE_FAILURES_REJECTED"));
    }

    @Test
    void runnerAlwaysRemovesOnlyItsExactContainerAcrossAllExitPaths() throws Exception {
        Path root =
                Files.createDirectories(
                        Path.of(
                                "target",
                                "fullstack-docker-it",
                                UUID.randomUUID() + "-runner-cleanup"));
        Path probeJar = probeJar(root.resolve("probe.jar"));
        String unrelated = "agent-harness-unrelated-" + UUID.randomUUID().toString().replace("-", "");
        String unrelatedId = startUnrelated(unrelated);
        PreviewReleaseAdapter.CommandRunner bounded =
                new PreviewReleaseAdapter.CommandExecutor(
                        java.time.Duration.ofSeconds(10), 65_536);
        try {
            // 正常退出也必须由精确名称清理，而不是依赖 --rm 或前缀扫描。
            try (DockerRunner runner =
                    new DockerRunner(
                            probeJar, bounded, java.time.Duration.ofSeconds(5), 1_048_576)) {
                DockerCommand normal = probe(runner, "normal");
                assertEquals(0, runner.executeForTest(normal).exitCode());
                assertContainerGone(normal.containerName());
                assertUnrelated(unrelated, unrelatedId);

                // Docker CLI 非零退出仍必须删除已创建的精确容器。
                DockerCommand cliFailure =
                        runner.fixedCommand("java", java.util.List.of("-not-a-real-option"));
                assertNotEquals(0, runner.executeForTest(cliFailure).exitCode());
                assertContainerGone(cliFailure.containerName());
                assertUnrelated(unrelated, unrelatedId);
            }

            // 整体 deadline 到期后先终止 CLI，再 rm -f 精确名称并复核不存在。
            try (DockerRunner runner =
                    new DockerRunner(
                            probeJar, bounded, java.time.Duration.ofSeconds(1), 1_048_576)) {
                DockerCommand timeout = probe(runner, "loop");
                assertThrows(IllegalStateException.class, () -> runner.executeForTest(timeout));
                assertContainerGone(timeout.containerName());
                assertUnrelated(unrelated, unrelatedId);
            }

            // stdout、stderr 与双流溢出分别走同一精确清理路径。
            for (String mode : java.util.List.of("stdout", "stderr", "dual")) {
                try (DockerRunner runner =
                        new DockerRunner(
                                probeJar, bounded, java.time.Duration.ofSeconds(5), 128)) {
                    DockerCommand overflow = probe(runner, mode);
                    assertThrows(
                            IllegalStateException.class,
                            () -> runner.executeForTest(overflow),
                            mode);
                    assertContainerGone(overflow.containerName());
                    assertUnrelated(unrelated, unrelatedId);
                }
            }

            // 调用线程中断后清理仍要完成，并把中断异常返回调用方。
            try (DockerRunner runner =
                    new DockerRunner(
                            probeJar, bounded, java.time.Duration.ofSeconds(30), 1_048_576)) {
                DockerCommand interrupted = probe(runner, "loop");
                AtomicReference<Throwable> observed = new AtomicReference<>();
                Thread thread =
                        new Thread(
                                () -> {
                                    try {
                                        runner.executeForTest(interrupted);
                                    } catch (Throwable failure) {
                                        observed.set(failure);
                                    }
                                });
                thread.start();
                waitForContainer(interrupted.containerName());
                thread.interrupt();
                thread.join(TimeUnit.SECONDS.toMillis(15));
                assertFalse(thread.isAlive());
                assertTrue(observed.get() instanceof IllegalStateException);
                assertContainerGone(interrupted.containerName());
                assertUnrelated(unrelated, unrelatedId);
            }
            assertEquals(
                    java.util.List.of(),
                    dockerLines("ps", "-a", "--filter", "name=^/agent-harness-run-", "--format", "{{.Names}}"));
        } finally {
            // 测试只清理由自己保存的精确 fixture 名称。
            docker("rm", "-f", unrelated);
        }
    }

    private static DockerCommand probe(DockerRunner runner, String mode) {
        return runner.fixedCommand(
                "java",
                java.util.List.of(
                        "-cp",
                        "/base/agent-harness-java.jar",
                        ProbeMain.class.getName(),
                        mode));
    }

    private static Path probeJar(Path target) throws Exception {
        // 将已编译测试探针单类封装为只读 JAR，不在宿主机执行候选代码。
        String resource = "/" + ProbeMain.class.getName().replace('.', '/') + ".class";
        try (InputStream input = FullStackDockerIT.class.getResourceAsStream(resource);
                JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
            assertTrue(input != null);
            jar.putNextEntry(new JarEntry(resource.substring(1)));
            input.transferTo(jar);
            jar.closeEntry();
        }
        return target.toRealPath();
    }

    private static String startUnrelated(String name) throws Exception {
        String output =
                docker(
                        "run",
                        "-d",
                        "--name",
                        name,
                        "--pull=never",
                        "--platform",
                        "linux/arm64",
                        "--network",
                        "none",
                        "--read-only",
                        "--cap-drop",
                        "ALL",
                        "--security-opt",
                        "no-new-privileges",
                        "--entrypoint",
                        "/bin/sleep",
                        DockerRunner.IMAGE,
                        "180");
        return output.strip();
    }

    private static void assertUnrelated(String name, String id) throws Exception {
        assertEquals(id, docker("inspect", "--format", "{{.Id}}", name).strip());
        assertEquals("true", docker("inspect", "--format", "{{.State.Running}}", name).strip());
    }

    private static void assertContainerGone(String name) throws Exception {
        Process process = new ProcessBuilder("docker", "container", "inspect", name).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertNotEquals(0, process.exitValue(), name);
    }

    private static void waitForContainer(String name) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (!dockerLines("ps", "-a", "--filter", "name=^/" + name + "$", "--format", "{{.Names}}").isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("runner container did not start");
    }

    private static String docker(String... arguments) throws Exception {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add("docker");
        argv.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(argv).start();
        byte[] output = process.getInputStream().readAllBytes();
        byte[] error = process.getErrorStream().readAllBytes();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(error, StandardCharsets.UTF_8));
        return new String(output, StandardCharsets.UTF_8);
    }

    private static java.util.List<String> dockerLines(String... arguments) throws Exception {
        String output = docker(arguments);
        return output.lines().filter(value -> !value.isBlank()).toList();
    }

    public static final class ProbeMain {
        private ProbeMain() {}

        public static void main(String[] arguments) throws Exception {
            String mode = arguments[0];
            if ("normal".equals(mode)) {
                System.out.print("ok");
                return;
            }
            if ("loop".equals(mode)) {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
                return;
            }
            if ("stdout".equals(mode)) {
                System.out.print("x".repeat(8_192));
                return;
            }
            if ("stderr".equals(mode)) {
                System.err.print("x".repeat(8_192));
                return;
            }
            if ("dual".equals(mode)) {
                Thread out = new Thread(() -> System.out.print("x".repeat(8_192)));
                Thread err = new Thread(() -> System.err.print("x".repeat(8_192)));
                out.start();
                err.start();
                out.join();
                err.join();
            }
        }
    }

    private static void copyScaffold(Path target) throws Exception {
        for (String name :
                new String[] {
                    "ConfigHttpServer.java",
                    "FeatureStore.java",
                    "GeneratedAppChecks.java",
                    "VersionPolicy.java"
                }) {
            try (InputStream input =
                    FullStackDockerIT.class.getResourceAsStream("/fullstack/scaffold/" + name)) {
                Files.copy(input, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void writeFrontend(Path source) throws Exception {
        Path web = Files.createDirectories(source.resolve("web"));
        Files.writeString(web.resolve("index.html"), "<!doctype html><main id=app>Version preview</main>");
        Files.writeString(web.resolve("styles.css"), "body{font-family:sans-serif}");
        Files.writeString(web.resolve("app.js"), "document.getElementById('app').textContent='Ready';");
    }

    private static String defectivePolicy() {
        return "package fullstack.generated;\n"
                + "public final class GeneratedVersionPolicy implements VersionPolicy {\n"
                + "  public boolean isAtLeast(String clientVersion, String minVersion) {\n"
                + "    return clientVersion.compareTo(minVersion) >= 0;\n"
                + "  }\n"
                + "}\n";
    }

    private static String repairedPolicy() {
        return "package fullstack.generated;\n"
                + "public final class GeneratedVersionPolicy implements VersionPolicy {\n"
                + "  public boolean isAtLeast(String clientVersion, String minVersion) {\n"
                + "    int[] left=parse(clientVersion), right=parse(minVersion);\n"
                + "    int size=Math.max(left.length,right.length);\n"
                + "    for(int i=0;i<size;i++){int l=i<left.length?left[i]:0; int r=i<right.length?right[i]:0; if(l!=r)return l>r;}\n"
                + "    return true;\n"
                + "  }\n"
                + "  private static int[] parse(String value){String[] parts=value.split(\"\\\\.\");int[] out=new int[parts.length];for(int i=0;i<parts.length;i++)out[i]=Integer.parseInt(parts[i]);return out;}\n"
                + "}\n";
    }

    private static String sha(Path path) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(path)));
    }
}
