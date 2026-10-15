package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewCommandExecutorTest {
    @TempDir Path temporary;

    @Test
    void terminatesSilentStdoutStderrAndDualStreamProcesses() throws Exception {
        // 每种失败都使用独立 PID 文件，便于证明执行器返回后没有残留子进程。
        for (String mode : List.of("silent", "stdout", "stderr", "dual", "tree")) {
            Path pidFile = temporary.resolve(mode + ".pid");
            Path childPidFile = temporary.resolve(mode + "-child.pid");
            PreviewReleaseAdapter.CommandExecutor executor =
                    new PreviewReleaseAdapter.CommandExecutor(Duration.ofMillis(400), 4_096);

            PreviewReleaseAdapter.CommandResult result =
                    executor.run(command(mode, pidFile, childPidFile));

            assertFalse(result.success(), mode);
            long pid = Long.parseLong(Files.readString(pidFile));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), mode);
            if ("tree".equals(mode)) {
                long childPid = Long.parseLong(Files.readString(childPidFile));
                assertFalse(
                        ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                        "tree child");
            }
        }
    }

    private static List<String> command(String mode, Path pidFile, Path childPidFile) {
        // 测试子进程也使用固定 java 可执行文件与独立参数，避免 shell 解释。
        List<String> argv = new ArrayList<>();
        argv.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        argv.add("-cp");
        argv.add(System.getProperty("java.class.path"));
        argv.add(CommandFixture.class.getName());
        argv.add(mode);
        argv.add(pidFile.toString());
        argv.add(childPidFile.toString());
        return List.copyOf(argv);
    }

    public static final class CommandFixture {
        private CommandFixture() {}

        public static void main(String[] args) throws Exception {
            // 先落 PID，测试只据此检查生命周期，不读取任何秘密或命令正文。
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            switch (args[0]) {
                case "silent" -> Thread.sleep(30_000);
                case "stdout" -> flood(System.out);
                case "stderr" -> flood(System.err);
                case "dual" -> {
                    // 两条流并行压满，验证执行器确实同时排水并在超限时终止。
                    Thread first = new Thread(() -> flood(System.out));
                    Thread second = new Thread(() -> flood(System.err));
                    first.start();
                    second.start();
                    first.join();
                    second.join();
                }
                case "tree" -> {
                    // 子进程使用相同固定 Java 入口，验证超时会终止整个后代树。
                    Process child =
                            new ProcessBuilder(
                                            Path.of(
                                                            System.getProperty("java.home"),
                                                            "bin",
                                                            "java")
                                                    .toString(),
                                            "-cp",
                                            System.getProperty("java.class.path"),
                                            CommandFixture.class.getName(),
                                            "child",
                                            args[2],
                                            args[2])
                                    .start();
                    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                    while (!Files.exists(Path.of(args[2])) && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                    }
                    child.waitFor();
                }
                case "child" -> Thread.sleep(30_000);
                default -> throw new IllegalArgumentException("unknown fixture mode");
            }
        }

        private static void flood(java.io.PrintStream stream) {
            byte[] bytes = new byte[8_192];
            while (true) {
                // 固定零字节只用于填满管道，不把测试输入或环境带入输出。
                stream.write(bytes, 0, bytes.length);
                stream.flush();
            }
        }
    }
}
