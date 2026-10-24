package dev.agentharness.demo;

import dev.agentharness.demo.domain.RepairProject;
import dev.agentharness.demo.infra.repair.RepairWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 通过测试专用入口验证私有进程 runner；生产工具没有任意命令参数。 */
class RepairProcessTest {
    @TempDir Path directory;
    public static class Runner {
        public static void main(String[] args) throws Exception {
            // 测试子进程只做受控等待/输出，并留下 PID 供父进程确认回收。
            Path pid = Path.of(args[1]), pending = pid.resolveSibling("pid.pending");
            Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
            // 完整 PID 发布后父线程才会中断，避免在空文件刚创建时杀死子进程。
            Files.move(pending, pid, StandardCopyOption.ATOMIC_MOVE);
            // macOS/JVM 可能自行注入这两个平台变量，其他父进程变量都不应出现。
            boolean filtered = System.getenv().keySet().stream().allMatch(key -> key.equals("__CF_USER_TEXT_ENCODING") || key.matches("JAVA_MAIN_CLASS_[0-9]+"));
            System.out.println("ENV_FILTER_OK=" + filtered);
            if (args[0].equals("sleep")) Thread.sleep(30000);
            if (args[0].equals("spam")) System.out.print("x".repeat(100000));
        }
    }
    private RepairProject.Check run(RepairWorkspace project, String mode) throws Exception {
        // 反射仅在测试中使用，外部模型不能选择 runner 或 argv。
        var method = RepairWorkspace.class.getDeclaredMethod("process", Path.class, List.class);
        method.setAccessible(true);
        String classes = Path.of(RepairProcessTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx32m", "-cp", classes,
                Runner.class.getName(), mode, directory.resolve("pid").toString());
        return (RepairProject.Check) method.invoke(project, directory, command);
    }
    private void assertStopped() throws Exception {
        long pid = Long.parseLong(Files.readString(directory.resolve("pid")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void parentEnvironmentIsNotInherited() throws Exception {
        try (var project = new RepairWorkspace()) {
            var result = run(project,"normal");
            assertTrue(result.passed());
            assertTrue(result.output().contains("ENV_FILTER_OK=true"), result.output());
            assertStopped();
        }
    }
    @Test void oversizedOutputIsRejectedAndChildStops() throws Exception {
        try (var project = new RepairWorkspace()) {
            var result = run(project,"spam");
            assertFalse(result.passed());
            assertTrue(result.output().length() <= 32769);
            assertStopped();
        }
    }
    @Test void deadlineKillsAndReapsChild() throws Exception {
        try (var project = new RepairWorkspace()) {
            var result = run(project,"sleep");
            assertFalse(result.passed());
            assertTrue(result.output().contains("TIMEOUT"));
            assertStopped();
        }
    }
    @Test void interruptionKillsChildAndPreservesSignal() throws Exception {
        try (var project = new RepairWorkspace()) {
            var failure = new AtomicReference<Throwable>();
            var preserved = new java.util.concurrent.atomic.AtomicBoolean();
            var caller = new Thread(() -> {
                try { run(project,"sleep"); }
                catch (Throwable error) { failure.set(error); preserved.set(Thread.currentThread().isInterrupted()); }
            });
            caller.start();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Files.exists(directory.resolve("pid")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(directory.resolve("pid")));
            caller.interrupt();
            caller.join(10000);
            assertFalse(caller.isAlive());
            assertNotNull(failure.get());
            assertTrue(preserved.get());
            assertStopped();
        }
    }
}
