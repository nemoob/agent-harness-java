package dev.agentharness.fullstack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/** 将脱敏运行摘要原子保存到可信状态根。 */
final class FullStackStateStore {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final Path directory;

    FullStackStateStore(Path stateRoot) {
        // 请求层已经验证状态根，这里紧邻使用再次验证根目录。
        Path root = realDirectory(stateRoot, "state root");
        // 摘要目录采用固定名称并拒绝预置链接。
        this.directory = createFixedDirectory(root.resolve("fullstack-summary"), root);
    }

    void save(String session, FullStackReport report) {
        Path target = directory.resolve(session + ".json");
        // 预置 leaf 或临时链接必须在任何写入前拒绝。
        rejectNonRegular(target);
        Path temporary = null;
        try {
            // 随机临时文件避免攻击者预置固定 .tmp 名称。
            temporary = Files.createTempFile(directory, ".summary-", ".tmp");
            // 仅保存脱敏报告，随后用同目录原子移动替换目标。
            JSON.writeValue(temporary.toFile(), report);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("fullstack summary cannot be saved", exception);
        } finally {
            // 失败路径清理当前进程创建的随机临时文件。
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 清理失败不覆盖原始保存错误。
                }
            }
        }
    }

    private static Path createFixedDirectory(Path requested, Path root) {
        try {
            // 既有路径不得是链接或特殊文件。
            if (Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
                Path existing = realDirectory(requested, "summary directory");
                if (!existing.startsWith(root)) {
                    throw new IllegalArgumentException("summary directory escapes state root");
                }
                return existing;
            }
            // 新建前复核真实父目录仍在可信根内。
            if (!requested.getParent().toRealPath().startsWith(root)) {
                throw new IllegalArgumentException("summary parent escapes state root");
            }
            return Files.createDirectory(requested).toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("summary directory cannot be created", exception);
        }
    }

    private static Path realDirectory(Path path, String label) {
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

    private static void rejectNonRegular(Path path) {
        // 不存在的 leaf 可由本次原子移动创建。
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // 已存在 leaf 必须是普通文件且不能是链接。
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("summary target must be a regular file");
        }
    }
}
