package dev.agentharness.demo.infra.a2a;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** 本机凭据文件与实例锁；不接受用户提供的主机、URL 或任意目录。 */
public final class LocalConnection implements AutoCloseable {
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private final String token;
    private boolean published;

    public record Target(int port, String token) {
        public Target {
            // 客户端构造目标时就拒绝异常端口与不是本例生成的令牌。
            if (port < 1 || port > 65535 || token == null || !token.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("target");
        }
        @Override public String toString() { return "Target[port=" + port + ", token=<redacted>]"; }
    }

    public LocalConnection(Path project) throws IOException {
        // 只在当前可信工程内创建私有目录；不支持的文件系统直接拒绝。
        directory = directory(project);
        Path file = directory.resolve("server.lock");
        channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            // 同目录只能有一个服务；失败时不得删除另一个服务的连接文件。
            lock = channel.tryLock();
            if (lock == null) throw new IOException("busy");
        } catch (IOException | RuntimeException failure) { channel.close(); throw failure; }
        // 每次启动独立生成 256 位随机令牌，不通过命令行参数传递。
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        token = HexFormat.of().formatHex(bytes);
    }
    public String token() { return token; }
    public void publish(int port) throws IOException {
        // 只发布固定端口和令牌字段，临时文件在创建时就是 0600。
        new Target(port, token);
        Path file = directory.resolve("connection.json");
        if (Files.isSymbolicLink(file)) throw new IOException("link");
        Path temp = Files.createTempFile(directory, "connection-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temp, A2aTransport.JSON.writeValueAsString(Map.of("port", port, "token", token)));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
        } finally { Files.deleteIfExists(temp); }
    }
    public static Target load(Path project) throws IOException {
        Path file = directory(project).resolve("connection.json");
        // 只读取当前用户的普通文件；拒绝符号链接和宽松权限的凭据。
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(project.toRealPath()))
                || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))) throw new IOException("credentials");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var node = A2aTransport.JSON.readTree(A2aTransport.readBounded(input, A2aTransport.LIMIT));
            if (node == null || !node.isObject() || node.size() != 2 || !node.path("port").isIntegralNumber()
                    || !node.path("port").canConvertToInt() || !node.path("token").isTextual()) throw new IOException("credentials");
            return new Target(node.path("port").asInt(), node.path("token").asText());
        }
    }
    private static Path directory(Path project) throws IOException {
        Path root = project.toRealPath(), dir = root.resolve(".a2a");
        if (!Files.getFileStore(root).supportsFileAttributeView("posix")) throw new IOException("POSIX required");
        // 新目录从创建时限制权限，已有目录先检查归属与真实类型。
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir)
                || !Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(root))) throw new IOException("directory");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        return dir;
    }
    @Override public void close() throws IOException {
        // 仅持锁实例清除自己发布的连接，锁文件保留以免产生多个锁 inode。
        try { if (published) Files.deleteIfExists(directory.resolve("connection.json")); }
        finally { try { lock.release(); } finally { channel.close(); } }
    }
}
