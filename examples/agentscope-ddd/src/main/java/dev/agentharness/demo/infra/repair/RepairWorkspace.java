package dev.agentharness.demo.infra.repair;

import dev.agentharness.demo.domain.RepairProject;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 固定练习工程。严格模板限制代码能力；不是通用 Java 沙箱。 */
public final class RepairWorkspace implements RepairProject, AutoCloseable {
    public static final String ORIGINAL = "LocalDate.parse(date, DateTimeFormatter.ofPattern(\"yyyy-MM-dd\"))";
    public static final String STILL_SMART = "LocalDate.parse(date, DateTimeFormatter.ofPattern(\"uuuu-MM-dd\"))";
    public static final String ISO = "LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)";
    public static final String STRICT = "LocalDate.parse(date, DateTimeFormatter.ofPattern(\"uuuu-MM-dd\").withResolverStyle(ResolverStyle.STRICT))";
    // 完整表达式白名单，不用正则去判断任意 Java 代码是否安全。
    private static final Set<String> EXPRESSIONS = Set.of(ORIGINAL, STILL_SMART, ISO, STRICT);
    private final Path root;
    private final String template, checks;
    private int testRuns;

    public RepairWorkspace() throws IOException {
        // 运行目录必须支持 POSIX 私有权限；当前示例仅支持 macOS/Linux。
        root = Files.createTempDirectory("harness-repair-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        template = resource("DateParser.template");
        checks = resource("CheckDates.java.txt");
        // 自带源码资源使用唯一占位标记，用户不能指定其他项目或测试资源。
        if (template.indexOf("@@EXPRESSION@@") != template.lastIndexOf("@@EXPRESSION@@") || !template.contains("@@EXPRESSION@@")) {
            throw new IllegalStateException("模板标记错误");
        }
        create(root.resolve("DateParser.java"), source(ORIGINAL));
        create(root.resolve("CheckDates.java"), checks);
    }
    private static String resource(String name) throws IOException {
        // 只读取应用随包提供的固定资源，不接收模型路径。
        try (var in = RepairWorkspace.class.getResourceAsStream("/repair/" + name)) {
            if (in == null) throw new IOException("缺少练习资源");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private String source(String expression) { return template.replace("@@EXPRESSION@@", expression); }
    private static void create(Path path, String contents) throws IOException {
        // CREATE_NEW 防止覆盖；NOFOLLOW_LINKS 拒绝把新文件重定向到其他位置。
        Files.writeString(path, contents, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
    private Path allowed(String file) throws IOException {
        // 固定相对文件名白名单：绝对路径、点路径及测试文件替换均不能扩权。
        if (!Set.of("DateParser.java", "CheckDates.java").contains(file)) throw new IllegalArgumentException("REJECTED: 文件不在白名单");
        Path path = root.resolve(file);
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("REJECTED: 文件或目录类型异常");
        }
        return path;
    }
    private String boundedRead(Path path) throws IOException {
        // 在已打开的句柄上限制读取大小，并拒绝符号链接。
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = in.readNBytes(32769);
            if (data.length > 32768) throw new IllegalArgumentException("REJECTED: 文件过大");
            return new String(data, StandardCharsets.UTF_8);
        }
    }
    private String expression() throws IOException {
        String current = boundedRead(allowed("DateParser.java"));
        // 验证整个源码，不允许在合法表达式外插入 import、静态块或其他语句。
        for (String value : EXPRESSIONS) if (current.equals(source(value))) return value;
        throw new IllegalArgumentException("REJECTED: 源码已偏离可信模板");
    }
    @Override public synchronized String read(String file) {
        try {
            Path path = allowed(file);
            if (file.equals("DateParser.java")) expression();
            else if (!boundedRead(path).equals(checks)) throw new IllegalArgumentException("REJECTED: 测试内容被修改");
            return boundedRead(path);
        } catch (IOException error) { throw new IllegalStateException("读取练习失败", error); }
    }
    @Override public synchronized String replace(String file, String before, String after) {
        try {
            // 测试代码只读；只接受完整的允许表达式，且前置版本必须匹配。
            if (!"DateParser.java".equals(file) || after == null || !EXPRESSIONS.contains(after) || after.equals(before)) throw new IllegalArgumentException("REJECTED: 只允许指定日期表达式");
            if (!expression().equals(before)) throw new IllegalArgumentException("REJECTED: 旧表达式不匹配");
            // 同目录原子替换完整模板，失败时不留下半份源码。
            Path pending = Files.createTempFile(root, "patch-", ".tmp");
            try {
                Files.writeString(pending, source(after), StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                Files.move(pending, allowed(file), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(pending); }
            if (!expression().equals(after)) throw new IllegalStateException("修改后回读失败");
            return "UPDATED DateParser.java";
        } catch (IOException error) { throw new IllegalStateException("修改练习失败", error); }
    }
    @Override public synchronized Check test() {
        Path snapshot = null;
        try {
            // 基线、模型工具与最终复验共享明确的测试次数预算。
            if (++testRuns > 8) throw new IllegalStateException("测试次数上限为 8");
            String current = source(expression());
            if (!boundedRead(allowed("CheckDates.java")).equals(checks)) throw new IllegalArgumentException("REJECTED: 测试内容被修改");
            // 只编译已核验字符串的新快照，不执行工作区内已有 class 或任意构建脚本。
            snapshot = Files.createTempDirectory("harness-check-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
            create(snapshot.resolve("DateParser.java"), current);
            create(snapshot.resolve("CheckDates.java"), checks);
            String bin = Path.of(System.getProperty("java.home"), "bin").toString();
            Check compile = process(snapshot, List.of(bin + "/javac", "-J-Xmx128m", "-J-XX:MaxMetaspaceSize=128m", "-proc:none", "-encoding", "UTF-8", "-classpath", ".", "-sourcepath", ".", "-d", ".", "DateParser.java", "CheckDates.java"));
            if (!compile.passed()) return compile;
            return process(snapshot, List.of(bin + "/java", "-Xmx64m", "-XX:MaxMetaspaceSize=64m", "-cp", ".", "CheckDates"));
        } catch (IOException error) { throw new IllegalStateException("测试运行失败", error); }
        finally { if (snapshot != null) remove(snapshot); }
    }
    private Check process(Path directory, List<String> command) throws IOException {
        // 独立参数调用固定 JDK 命令，不经过 shell；环境不继承 API 密钥或 JVM 注入变量。
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        var process = builder.start();
        var bytes = new ByteArrayOutputStream();
        // 输出一旦超出 32 KiB 就终止进程；读线程与等待均有界。
        var reader = new Thread(() -> {
            try (var in = process.getInputStream()) {
                byte[] data = in.readNBytes(32769);
                synchronized (bytes) { bytes.writeBytes(data); }
                if (data.length > 32768) process.destroyForcibly();
            } catch (IOException ignored) { /* 终止进程会关闭管道；退出结果仍在主线程检查。 */ }
        }, "repair-output");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean completed = process.waitFor(20, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试进程未停止");
            reader.join(5000);
            if (reader.isAlive()) throw new IllegalStateException("测试输出未关闭");
            String output = bytes.toString(StandardCharsets.UTF_8);
            boolean passed = completed && bytes.size() <= 32768 && process.exitValue() == 0;
            return new Check(passed, process.exitValue(), output + (completed ? "" : "\nTIMEOUT"));
        } catch (InterruptedException cancelled) {
            // 取消不能继续留着子进程；保留当前线程中断信号。
            process.destroyForcibly();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("取消后进程未停止");
                reader.join(5000);
            } catch (InterruptedException again) {
                // 再次中断仍保留信号，由入口终止该任务。
            } finally { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("测试已取消", cancelled);
        } finally {
            // 所有出口都确认终止，避免只发出 destroy 信号就清理快照。
            boolean restoreInterrupt = Thread.interrupted();
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("最终收尾未能停止测试进程");
                }
                process.getInputStream().close();
            } catch (InterruptedException interrupted) {
                restoreInterrupt = true;
                throw new IllegalStateException("进程收尾被再次中断", interrupted);
            } finally { if (restoreInterrupt) Thread.currentThread().interrupt(); }
        }
    }
    @Override public synchronized String diff() {
        try {
            String value = expression();
            if (value.equals(ORIGINAL)) return "NO_CHANGE";
            return "--- DateParser.java (before)\n+++ DateParser.java (after)\n- " + ORIGINAL + "\n+ " + value;
        } catch (IOException error) { throw new IllegalStateException("差异读取失败", error); }
    }
    public Path directory() { return root; }
    private static void remove(Path directory) {
        // walk 默认不跟随符号链接，清理仅限本次创建的临时目录。
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (IOException failure) { System.err.println("[清理] 临时目录未完全删除"); }
    }
    @Override public synchronized void close() { remove(root); }
}
