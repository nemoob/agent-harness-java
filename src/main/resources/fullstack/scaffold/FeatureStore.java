package fullstack.generated;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** 将唯一公开教学 feature 原子持久化到受限 runtime data 目录。 */
public final class FeatureStore {
    public static final String FEATURE_ID = "homepage_recommendation";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final Path dataRoot;
    private final Path stateFile;
    private final MoveStrategy mover;
    private Feature feature;
    private boolean atomicFallbackUsed;

    public FeatureStore(Path requestedDataRoot) {
        // 生产路径使用固定的原子移动实现。
        this(requestedDataRoot, FeatureStore::moveIntoPlace);
    }

    FeatureStore(Path requestedDataRoot, MoveStrategy mover) {
        // 根目录必须已存在、无链接且真实路径等于规范路径。
        this.dataRoot = trustedDirectory(requestedDataRoot);
        // 状态 leaf 使用固定文件名并在每次访问前拒绝链接。
        this.stateFile = dataRoot.resolve("feature-config.json");
        // 测试钩子只替换最终移动动作，用于证明失败不部分覆盖。
        this.mover = java.util.Objects.requireNonNull(mover, "mover");
        // 既有状态严格读取；首次启动则原子写入冻结默认值。
        if (Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            this.feature = readExisting();
        } else {
            this.feature = new Feature(FEATURE_ID, true, "2.9", 7);
            persist(feature);
        }
    }

    public synchronized List<Feature> list() {
        // 每次读取前再次验证 leaf，避免静默跟随运行期链接替换。
        verifyStateLeaf();
        // 返回不可变单元素快照。
        return List.of(feature);
    }

    public synchronized Feature get(String id) {
        // API 仅允许冻结的唯一 feature ID。
        if (!FEATURE_ID.equals(id)) {
            throw new UnknownFeatureException();
        }
        // 读取前验证持久化 leaf 仍是根内普通文件。
        verifyStateLeaf();
        return feature;
    }

    public synchronized Feature update(
            String id, boolean enabled, String minVersion, long expectedRevision) {
        // 服务端先读取可信当前状态和 revision。
        Feature current = get(id);
        // 客户端 expectedRevision 不等于当前值时拒绝覆盖。
        if (current.configRevision() != expectedRevision) {
            throw new RevisionConflictException();
        }
        // 新 revision 只由服务端递增，字段名与公开契约一致。
        Feature updated =
                new Feature(
                        current.featureId(),
                        enabled,
                        minVersion,
                        current.configRevision() + 1);
        // 先完整持久化，成功后才替换进程内状态，失败不部分生效。
        persist(updated);
        this.feature = updated;
        return updated;
    }

    boolean atomicFallbackUsed() {
        // 仅供固定验收记录当前文件系统是否缺少原子移动支持。
        return atomicFallbackUsed;
    }

    private Feature readExisting() {
        // 损坏或越界状态必须稳定拒绝，不回退为默认配置。
        verifyStateLeaf();
        try {
            Feature loaded = JSON.readValue(stateFile.toFile(), Feature.class);
            if (!FEATURE_ID.equals(loaded.featureId())
                    || loaded.configRevision() < 7
                    || !validVersion(loaded.minVersion())) {
                throw new IllegalStateException("feature state invalid");
            }
            return loaded;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("feature state JSON invalid", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("feature state cannot be read", exception);
        }
    }

    private void persist(Feature value) {
        verifyRoot();
        verifyStateLeafIfPresent();
        Path temporary = null;
        try {
            // 随机临时文件位于同一可信目录，防止预置固定 temp 链接。
            temporary = Files.createTempFile(dataRoot, ".feature-config-", ".tmp");
            byte[] bytes = JSON.writeValueAsBytes(value);
            // FileChannel force(true) 在 close 前将文件内容与元数据提交给文件系统。
            try (FileChannel channel =
                    FileChannel.open(
                            temporary,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            // 移动前再次验证根和 leaf，缩小检查与使用间隔。
            verifyRoot();
            verifyStateLeafIfPresent();
            mover.move(temporary, stateFile, this);
            temporary = null;
            // 移动完成后确认最终 leaf 仍是根内普通文件。
            verifyStateLeaf();
        } catch (IOException exception) {
            throw new IllegalStateException("feature state cannot be persisted", exception);
        } finally {
            // 失败路径只删除本次随机临时文件。
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 清理失败不覆盖原始持久化错误。
                }
            }
        }
    }

    private static void moveIntoPlace(Path source, Path target, FeatureStore store)
            throws IOException {
        try {
            // 首选同目录原子替换，避免读者观察到半写文件。
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            // 首版不能把普通替换冒充原子持久化；平台不支持时保持旧文件并关闭写入。
            throw exception;
        }
    }

    private void verifyRoot() {
        // 根必须持续保持原真实目录身份；同用户主动竞态不在本地教学承诺内。
        Path verified = trustedDirectory(dataRoot);
        if (!verified.equals(dataRoot)) {
            throw new IllegalStateException("feature data root changed");
        }
    }

    private void verifyStateLeafIfPresent() {
        // 不存在时允许本次原子移动创建 leaf。
        if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        verifyStateLeaf();
    }

    private void verifyStateLeaf() {
        try {
            // leaf 必须是未跟随链接的普通文件且真实父目录仍为可信根。
            if (Files.isSymbolicLink(stateFile)
                    || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)
                    || !stateFile.toRealPath().startsWith(dataRoot)) {
                throw new IllegalStateException("feature state leaf invalid");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("feature state leaf cannot be verified", exception);
        }
    }

    private static Path trustedDirectory(Path requested) {
        try {
            Path normalized = requested.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                    || !normalized.toRealPath().equals(normalized)) {
                throw new IllegalArgumentException("feature data root must be a real directory");
            }
            return normalized;
        } catch (IOException exception) {
            throw new IllegalArgumentException("feature data root cannot be verified", exception);
        }
    }

    private static boolean validVersion(String value) {
        // 持久化状态复用 2–4 段、每段 0..999999、非零无前导零规则。
        if (value == null
                || !value.matches(
                        "(?:0|[1-9][0-9]{0,5})(?:\\.(?:0|[1-9][0-9]{0,5})){1,3}")) {
            return false;
        }
        for (String segment : value.split("\\.")) {
            if (Integer.parseInt(segment) > 999_999) {
                return false;
            }
        }
        return true;
    }

    public record Feature(
            String featureId, boolean enabled, String minVersion, long configRevision) {}

    @FunctionalInterface
    interface MoveStrategy {
        // 测试策略必须保留 source/target/store 三个边界输入。
        void move(Path source, Path target, FeatureStore store) throws IOException;
    }

    public static final class UnknownFeatureException extends RuntimeException {}

    public static final class RevisionConflictException extends RuntimeException {}
}
