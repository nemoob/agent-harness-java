package dev.agentharness.fullstack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** 拒绝路径逃逸并以全批校验、备份回滚写入候选文件的受限工作区。 */
public final class SafeWorkspace {
    private static final int MAX_FILES = 64;
    private static final int MAX_FILE_BYTES = 262_144;
    private static final int MAX_PATCH_BYTES = 1_048_576;
    private final Path root;
    private final Runnable beforeCommit;

    public SafeWorkspace(Path root) {
        this(root, () -> {});
    }

    SafeWorkspace(Path root, Runnable beforeCommit) {
        // 构造时固化无链接的真实根目录。
        this.root = trustedRoot(root);
        // 测试钩子只用于验证检查与使用边界置换，生产构造始终为空操作。
        this.beforeCommit = beforeCommit;
    }

    public synchronized WorkspaceManifest apply(WorkspacePatch patch) {
        // 文件数上限在创建任何临时文件前执行。
        if (patch == null || patch.files().isEmpty() || patch.files().size() > MAX_FILES) {
            throw new IllegalArgumentException("workspace patch file count invalid");
        }
        Map<String, byte[]> encoded = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<String, String> entry : patch.files().entrySet()) {
            // 路径先规范化并完整验证所有既有 segment。
            validateTarget(entry.getKey());
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("workspace patch file too large");
            }
            total += bytes.length;
            if (total > MAX_PATCH_BYTES) {
                throw new IllegalArgumentException("workspace patch total too large");
            }
            encoded.put(entry.getKey(), bytes);
        }
        Path stage = null;
        try {
            // staging 位于同一可信根，后续文件移动可使用同文件系统原子语义。
            stage = Files.createTempDirectory(root, ".agent-harness-stage-");
            Path stagedFiles = Files.createDirectory(stage.resolve("files"));
            Path backups = Files.createDirectory(stage.resolve("backups"));
            for (Map.Entry<String, byte[]> entry : encoded.entrySet()) {
                Path staged = stagedFiles.resolve(entry.getKey()).normalize();
                Files.createDirectories(staged.getParent());
                Files.write(staged, entry.getValue());
            }
            // 在 staging 完成后允许测试置换边界，再执行紧邻提交的第二次完整校验。
            beforeCommit.run();
            for (String relative : encoded.keySet()) {
                validateTarget(relative);
            }
            commit(encoded.keySet(), stagedFiles, backups);
            return manifest();
        } catch (IOException exception) {
            throw new IllegalStateException("workspace patch failed", exception);
        } finally {
            deleteTree(stage);
        }
    }

    public synchronized WorkspaceManifest manifest() {
        verifyRoot();
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> sorted = paths.filter(path -> !path.equals(root)).sorted().toList();
            for (Path file : sorted) {
                // manifest 不能静默跟随工作区内出现的符号链接。
                if (Files.isSymbolicLink(file)) {
                    throw new IllegalArgumentException("workspace manifest rejects links");
                }
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    // 临时事务目录不会进入候选 manifest。
                    if (!relative.startsWith(".agent-harness-stage-")) {
                        files.put(relative, sha256(Files.readAllBytes(file)));
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("workspace manifest failed", exception);
        }
        StringBuilder canonical = new StringBuilder();
        files.forEach((path, hash) -> canonical.append(path).append('\t').append(hash).append('\n'));
        return new WorkspaceManifest(
                sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)), files);
    }

    private void commit(Iterable<String> relatives, Path stagedFiles, Path backups)
            throws IOException {
        List<Path> installed = new ArrayList<>();
        Map<Path, Path> saved = new LinkedHashMap<>();
        List<Path> createdDirectories = new ArrayList<>();
        try {
            for (String relative : relatives) {
                Path target = root.resolve(relative).normalize();
                ensureParents(target.getParent(), createdDirectories);
                // 每个 leaf 使用前立即再次拒绝链接或特殊文件。
                validateTarget(relative);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Path backup = backups.resolve(relative).normalize();
                    Files.createDirectories(backup.getParent());
                    Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES);
                    saved.put(target, backup);
                }
                Files.move(
                        stagedFiles.resolve(relative).normalize(),
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                installed.add(target);
            }
        } catch (IOException | RuntimeException failure) {
            // 提交期失败时删除已安装文件并恢复所有备份，避免部分批次可见。
            for (Path target : installed) {
                Files.deleteIfExists(target);
                Path backup = saved.get(target);
                if (backup != null) {
                    Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            createdDirectories.sort(Comparator.reverseOrder());
            for (Path directory : createdDirectories) {
                try {
                    Files.deleteIfExists(directory);
                } catch (IOException ignored) {
                    // 非空目录说明不属于本次事务，保留原始提交异常。
                }
            }
            throw failure;
        }
    }

    private void ensureParents(Path parent, List<Path> created) throws IOException {
        Path relative = root.relativize(parent);
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
                created.add(current);
            } else {
                verifyDirectory(current);
            }
        }
    }

    private Path validateTarget(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("workspace relative path is required");
        }
        Path requested = Path.of(relative);
        if (requested.isAbsolute() || !requested.normalize().equals(requested)) {
            throw new IllegalArgumentException("workspace path must be normalized and relative");
        }
        verifyRoot();
        Path target = root.resolve(requested).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("workspace path escapes root");
        }
        Path current = root;
        for (int index = 0; index < requested.getNameCount(); index++) {
            current = current.resolve(requested.getName(index));
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                // 后续尚未创建的 segment 由提交阶段从最近真实父目录逐级创建。
                break;
            }
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("workspace path contains a link");
            }
            boolean leaf = index == requested.getNameCount() - 1;
            if (leaf) {
                if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("workspace leaf must be a regular file");
                }
            } else {
                verifyDirectory(current);
            }
        }
        return target;
    }

    private void verifyRoot() {
        verifyDirectory(root);
        try {
            if (!root.toRealPath().equals(root)) {
                throw new IllegalArgumentException("workspace root changed");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspace root cannot be verified", exception);
        }
    }

    private void verifyDirectory(Path directory) {
        try {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("workspace directory invalid");
            }
            Path real = directory.toRealPath();
            if (!real.equals(directory) || !real.startsWith(root)) {
                throw new IllegalArgumentException("workspace directory escaped root");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspace directory cannot be verified", exception);
        }
    }

    private static Path trustedRoot(Path requested) {
        if (requested == null || Files.isSymbolicLink(requested)) {
            throw new IllegalArgumentException("workspace root invalid");
        }
        try {
            Path normalized = requested.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("workspace root must exist");
            }
            Path real = normalized.toRealPath();
            if (!real.equals(normalized)) {
                throw new IllegalArgumentException("workspace root links are not allowed");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspace root cannot be verified", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void deleteTree(Path path) {
        if (path == null || Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(SafeWorkspace::deleteQuietly);
        } catch (IOException ignored) {
            // 临时目录清理失败不覆盖原始事务结果。
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败由后续 manifest 排除固定 stage 前缀并在最终检查中暴露。
        }
    }
}
