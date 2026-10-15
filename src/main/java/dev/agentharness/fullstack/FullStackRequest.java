package dev.agentharness.fullstack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** 经过边界校验的 fullstack CLI 请求。 */
public record FullStackRequest(
        ModelMode mode, Path requirementFile, Path stateRoot, String session, int previewPort) {
    private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final long MAX_REQUIREMENT_BYTES = 65_536;

    public FullStackRequest {
        // 模式必须由固定枚举明确选择。
        Objects.requireNonNull(mode, "mode");
        // 会话只接受有限 ASCII，后续角色会话再以稳定哈希派生。
        if (session == null || !SESSION.matcher(session).matches()) {
            throw new IllegalArgumentException("session must match [A-Za-z0-9_-]{1,64}");
        }
        // 预览端口必须避开特权端口并落入 TCP 端口范围。
        if (previewPort < 1_024 || previewPort > 65_535) {
            throw new IllegalArgumentException("preview-port must be between 1024 and 65535");
        }
        try {
            // 需求文件必须是未跟随链接的既有普通文件。
            Path normalizedRequirement = Objects.requireNonNull(requirementFile, "requirementFile").toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalizedRequirement)
                    || !Files.isRegularFile(normalizedRequirement, LinkOption.NOFOLLOW_LINKS)
                    || !normalizedRequirement.toRealPath().equals(normalizedRequirement)) {
                throw new IllegalArgumentException("requirement-file must be a real regular file");
            }
            // 需求输入在读取前限制为 64 KiB。
            if (Files.size(normalizedRequirement) > MAX_REQUIREMENT_BYTES) {
                throw new IllegalArgumentException("requirement-file is too large");
            }
            // 状态根必须预先创建，且不能是链接或别名路径。
            Path normalizedState = Objects.requireNonNull(stateRoot, "stateRoot").toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalizedState)
                    || !Files.isDirectory(normalizedState, LinkOption.NOFOLLOW_LINKS)
                    || !normalizedState.toRealPath().equals(normalizedState)) {
                throw new IllegalArgumentException("state-root must be a real directory");
            }
            // record 保存已验证的绝对真实路径，避免后续 cwd 漂移。
            requirementFile = normalizedRequirement;
            stateRoot = normalizedState;
        } catch (IOException exception) {
            // 文件系统错误转换为稳定的输入错误，不回显底层路径。
            throw new IllegalArgumentException("fullstack input paths cannot be verified", exception);
        }
    }
}
