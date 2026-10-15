package dev.agentharness.fullstack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/** 创建预览所需的双一次性 token 与只含 hash 的认证配置。 */
public final class BootstrapTokenFile {
    private BootstrapTokenFile() {}

    public static PreviewAuthMaterial createPreview(Path requestedDataRoot) throws Exception {
        // 生产预览只把 smoke token 留在适配器内存，不创建其明文文件。
        return create(requestedDataRoot, false);
    }

    public static void main(String[] args) throws Exception {
        // 固定测试入口只接受专用 data 目录，且不会输出任何 token。
        if (args.length != 1) {
            throw new IllegalArgumentException("data root required");
        }
        // 容器自检需要两个独立客户端读取，允许在隔离 data 中创建测试 smoke 文件。
        create(Path.of(args[0]), true);
    }

    private static PreviewAuthMaterial create(Path requestedDataRoot, boolean writeSmokeFile)
            throws Exception {
        Path dataRoot = trustedDirectory(requestedDataRoot);
        // 两个随机 token 分别服务自动 smoke 与人工浏览器交接。
        String smoke = randomToken();
        String handoff = randomToken();
        Path handoffFile = dataRoot.resolve("browser-handoff.token");
        Path configFile = dataRoot.resolve("auth-config.json");
        // CREATE_NEW 拒绝覆盖预置 leaf 或链接。
        writePrivate(handoffFile, handoff);
        String config =
                "{\"tokens\":[{\"tokenId\":\"smoke\",\"sha256\":\""
                        + sha256(smoke)
                        + "\"},{\"tokenId\":\"browser-handoff\",\"sha256\":\""
                        + sha256(handoff)
                        + "\"}]}";
        // 配置只含 tokenId 与 SHA-256，不含任何明文 token。
        writePrivate(configFile, config);
        // hash-only 配置允许非 root 容器只读，明文 handoff 仍保持 0600 且不挂载。
        Files.setPosixFilePermissions(
                configFile,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ));
        Path smokeFile = null;
        if (writeSmokeFile) {
            // 测试 smoke 文件仅存在于临时隔离运行目录，不被生产适配器创建。
            smokeFile = dataRoot.resolve("test-smoke.token");
            writePrivate(smokeFile, smoke);
        }
        // 清除 handoff 的额外字符串引用由 JVM 管理；对外只返回其 0600 文件路径。
        return new PreviewAuthMaterial(
                smoke.toCharArray(), handoffFile, configFile, smokeFile);
    }

    private static void writePrivate(Path target, String value) throws Exception {
        // leaf 不得预先存在，包括符号链接。
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("auth material target already exists");
        }
        Files.writeString(
                target,
                value,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        // POSIX 运行环境中将明文与 hash config 都收紧为 owner 读写。
        Files.setPosixFilePermissions(
                target,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private static Path trustedDirectory(Path requested) throws Exception {
        Path normalized = requested.toAbsolutePath().normalize();
        // 运行目录必须是无链接的既有真实目录。
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || !normalized.toRealPath().equals(normalized)) {
            throw new IllegalArgumentException("auth data root must be a real directory");
        }
        return normalized;
    }

    private static String randomToken() {
        // 256 位随机数使用 URL-safe 编码，便于表单提交且不进入 URL。
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        java.util.Arrays.fill(bytes, (byte) 0);
        return token;
    }

    private static String sha256(String value) throws Exception {
        // 配置仅保存不可逆的标准 SHA-256 十六进制值。
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public record PreviewAuthMaterial(
            char[] smokeToken, Path handoffFile, Path authConfigFile, Path testSmokeFile) {
        public PreviewAuthMaterial {
            // smoke 字符数组复制后交由适配器显式清零。
            smokeToken = smokeToken.clone();
        }

        @Override
        public char[] smokeToken() {
            // 每次读取返回副本，避免外部提前清除内部唯一副本。
            return smokeToken.clone();
        }
    }
}
