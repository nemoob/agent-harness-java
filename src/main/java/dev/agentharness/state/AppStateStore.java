package dev.agentharness.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/** 单机文件状态仓库；通过会话文件锁约束同一会话的跨进程并发。 */
public final class AppStateStore {
    private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);
    private static final long MAX_STATE_BYTES = 1_048_576;
    // 固定集合来自 AgentScope 2.0.4 Store 主文件、兼容键及其同名 .tmp 写入规则。
    private static final Set<String> AGENT_STATE_LEAVES =
            Set.of(
                    "agent_state.json",
                    "agent_state.json.tmp",
                    "memory_messages.jsonl",
                    "memory_messages.jsonl.tmp",
                    "memory_messages.hash",
                    "memory_messages.hash.tmp",
                    "toolkit_activeGroups.json",
                    "toolkit_activeGroups.json.tmp");
    private final Path root;
    private final Path applicationRoot;

    public AppStateStore(Path requestedRoot) {
        // 将状态根限制在当前克隆目录，阻止写入任意主机路径。
        this.root = secureRoot(requestedRoot);
        // 创建并验证固定 application 子目录，拒绝构造前预置的目录链接。
        this.applicationRoot = secureChildDirectory(root, "application");
    }

    public Path root() {
        // 返回规范化后的受限状态根，供 AgentScope 状态仓库复用。
        return root;
    }

    public Path agentStateRoot(String userId, String sessionId) {
        // AgentScope 的固定教学用户和会话目录使用同一有限 ASCII 规则。
        validateStateSegment(userId, "userId");
        validateStateSegment(sessionId, "sessionId");
        // 每次构造框架状态仓库前复核顶层状态根未被链接替换。
        verifyTrustedDirectory(root, root, "状态根");
        // 创建或打开 agentscope 固定子目录时拒绝预置链接和普通文件。
        Path agentScopeRoot = secureChildDirectory(root, "agentscope");
        // 预先安全创建框架随后使用的用户目录，避免框架跟随预置链接。
        Path userRoot = secureChildDirectory(agentScopeRoot, userId);
        // 预先安全创建框架随后使用的会话目录，保持每一级真实路径在根内。
        Path sessionRoot = secureChildDirectory(userRoot, sessionId);
        // 构造 JsonFileAgentStateStore 前检查框架可触达的全部固定主文件和临时文件。
        verifyAgentStateLeaves(sessionRoot);
        // JsonFileAgentStateStore 仍接收顶层 agentscope 目录以保持原生状态布局。
        return agentScopeRoot;
    }

    public void verifyAgentStateSlot(String userId, String sessionId) {
        // 每次 Agent 调用紧邻处都重新验证相同的有限路径片段。
        validateStateSegment(userId, "userId");
        validateStateSegment(sessionId, "sessionId");
        // 先复核保存的状态根，拒绝调用期间被替换的根目录。
        verifyTrustedDirectory(root, root, "状态根");
        // 调用期只验证已经由 agentStateRoot 安全创建的固定目录，不隐式补建目录。
        Path agentScopeRoot = root.resolve("agentscope");
        Path userRoot = agentScopeRoot.resolve(userId);
        Path sessionRoot = userRoot.resolve(sessionId);
        verifyTrustedDirectory(root, agentScopeRoot, "agentscope 目录");
        verifyTrustedDirectory(root, userRoot, "AgentScope 用户目录");
        verifyTrustedDirectory(root, sessionRoot, "AgentScope 会话目录");
        // 主文件和 SDK 固定 .tmp 文件存在时只能是根内普通文件。
        verifyAgentStateLeaves(sessionRoot);
    }

    public LockedSession lock(String session) {
        // 在形成任何路径前校验会话标识。
        validateSession(session);
        // 锁文件名只由已校验会话标识生成。
        Path lockPath = applicationRoot.resolve(session + ".lock");
        FileChannel channel = null;
        FileLock fileLock = null;
        try {
            // 每次操作前复核可信固定目录仍是原真实目录。
            verifyApplicationRoot();
            // 拒绝预置锁文件链接，避免锁定工作树外文件。
            rejectLink(lockPath, "锁文件");
            // 使用 NOFOLLOW_LINKS 打开白名单锁文件。
            channel =
                    FileChannel.open(
                            lockPath,
                            Set.of(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS));
            // 以有界轮询获取本机独占锁，防止调用无限等待。
            fileLock = acquireLock(channel, LOCK_TIMEOUT);
            // 加锁后读取最新状态，避免基于陈旧快照更新。
            AppState state = readUnlocked(session);
            // 将锁、通道与状态交给一个可关闭会话对象管理。
            return new LockedSession(this, session, channel, fileLock, state);
        } catch (IOException e) {
            // 任一步失败都释放已经取得的锁与通道。
            closeAfterFailure(fileLock, channel);
            // 将文件锁失败转为明确的应用错误。
            throw new IllegalStateException("无法锁定会话状态", e);
        } catch (RuntimeException e) {
            // 损坏状态或安全校验失败同样不能泄漏锁。
            closeAfterFailure(fileLock, channel);
            // 保留原始异常类别和原因供调用方处理。
            throw e;
        }
    }

    public AppState read(String session) {
        // 读取路径前执行同样的会话白名单校验。
        validateSession(session);
        // 只读场景直接返回当前原子快照。
        return readUnlocked(session);
    }

    void write(String session, AppState state) {
        // 最终文件名由白名单会话标识生成。
        Path destination = applicationRoot.resolve(session + ".json");
        Path temporary = null;
        try {
            // 每次写入前复核目录和目标均未被替换为链接。
            verifyApplicationRoot();
            // 原子替换前拒绝预置的目标符号链接。
            rejectLink(destination, "状态文件");
            // 使用随机安全临时文件避免固定临时名被预置。
            temporary = Files.createTempFile(applicationRoot, session + ".", ".tmp");
            // 用 Jackson 写入 UTF-8 JSON，避免手工拼接不可信字段。
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
            // 优先以原子移动替换完整快照。
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 写入失败时尽力清理不完整临时文件。
            try {
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException ignored) {
                // 清理失败不覆盖原始写入异常。
            }
            // 向调用方报告持久化失败，禁止继续声称已完成动作。
            throw new IllegalStateException("无法原子保存会话状态", e);
        }
    }

    private AppState readUnlocked(String session) {
        // 状态文件名由已验证会话标识生成。
        Path file = applicationRoot.resolve(session + ".json");
        // 每次读取前复核固定目录仍在可信状态根内。
        verifyApplicationRoot();
        // 文件不存在表示首次运行，返回 revision=0 的初始状态。
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return AppState.initial(session);
        }
        // 会话状态文件不能被替换为符号链接。
        if (Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("拒绝符号链接状态文件");
        }
        try {
            // 只接受普通小文件，避免设备文件或超大状态耗尽资源。
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_STATE_BYTES) {
                throw new IllegalStateException("会话状态必须是受限大小的普通文件");
            }
            // 使用固定类型反序列化，禁止危险多态反序列化。
            return JSON.readValue(file.toFile(), AppState.class);
        } catch (IOException e) {
            // 损坏状态必须显式失败，避免把已执行动作当成未执行。
            throw new IllegalStateException("无法读取会话状态", e);
        }
    }

    private static Path secureRoot(Path requestedRoot) {
        // 拒绝空状态根，避免退化到用户主目录等宽泛位置。
        if (requestedRoot == null) {
            throw new IllegalArgumentException("state root is required");
        }
        try {
            // 当前克隆目录是状态写入的唯一允许祖先。
            Path workspace = Path.of(System.getProperty("user.dir")).toRealPath();
            // 相对路径按当前克隆解析，绝对路径也必须落在该目录内。
            Path candidate =
                    (requestedRoot.isAbsolute() ? requestedRoot : workspace.resolve(requestedRoot))
                            .normalize()
                            .toAbsolutePath();
            // 阻止 .. 或绝对路径逃逸当前克隆。
            if (!candidate.startsWith(workspace)) {
                throw new IllegalArgumentException("状态根必须位于当前克隆目录内");
            }
            // 逐级打开或创建目录，每一级都拒绝链接和非目录对象。
            Path relative = workspace.relativize(candidate);
            Path current = workspace;
            for (Path segment : relative) {
                Path next = current.resolve(segment);
                if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(next)
                            || !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalArgumentException("状态根不能包含链接或非目录对象");
                    }
                } else {
                    Files.createDirectory(next);
                }
                // 创建或打开后用真实路径确认本级仍在工作区内且没有链接跳转。
                Path real = next.toRealPath();
                if (!real.startsWith(workspace) || !real.equals(next)) {
                    throw new IllegalArgumentException("状态根真实路径越界");
                }
                // 下一层只从已经验证的真实目录继续解析。
                current = real;
            }
            // 最终再用真实路径确认目标没有在检查后变成越界链接。
            Path real = candidate.toRealPath();
            if (!real.startsWith(workspace) || !real.equals(candidate)) {
                throw new IllegalArgumentException("状态根真实路径越界");
            }
            // 返回不含符号链接的真实路径。
            return real;
        } catch (IOException e) {
            // 路径无法规范化时拒绝继续。
            throw new IllegalArgumentException("无法验证状态根", e);
        }
    }

    private static void validateSession(String session) {
        // 仅允许有限 ASCII 标识，直接排除路径分隔符和控制字符。
        if (session == null || !SESSION.matcher(session).matches()) {
            throw new IllegalArgumentException("session must match [A-Za-z0-9_-]{1,64}");
        }
    }

    private static void validateStateSegment(String value, String label) {
        // AgentScope 路径片段沿用会话白名单，排除分隔符、点路径和控制字符。
        if (value == null || !SESSION.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must match [A-Za-z0-9_-]{1,64}");
        }
    }

    private void verifyApplicationRoot() {
        // 状态根和 application 目录在每次文件操作前一并复核。
        verifyTrustedDirectory(root, root, "状态根");
        verifyTrustedDirectory(root, applicationRoot, "application 目录");
    }

    private static Path secureChildDirectory(Path parent, String child) {
        // 子目录名称由应用固定，不接收外部路径片段。
        Path directory = parent.resolve(child);
        // 构造前已存在的链接必须直接拒绝。
        rejectLink(directory, "状态子目录");
        try {
            // 已存在对象必须是未跟随链接的目录。
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("状态子目录必须是目录");
            }
            // 父目录已验证，缺失时只创建当前一级，避免隐式跟随中间路径。
            if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(directory);
            }
            // 创建后以真实路径确认没有越出父状态根。
            Path real = directory.toRealPath();
            if (!real.startsWith(parent) || !real.equals(directory)) {
                throw new IllegalArgumentException("状态子目录真实路径越界");
            }
            // 返回已验证的真实目录。
            return real;
        } catch (IOException e) {
            // 无法确认真实目录时禁止继续。
            throw new IllegalStateException("无法验证状态子目录", e);
        }
    }

    private static void verifyTrustedDirectory(Path trustedRoot, Path directory, String label) {
        try {
            // 路径本身必须存在且是未跟随链接的普通目录。
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(label + "不能是链接或非目录对象");
            }
            // 真实路径必须与已保存路径一致并继续位于可信根内。
            Path real = directory.toRealPath();
            if (!real.equals(directory) || !real.startsWith(trustedRoot)) {
                throw new IllegalArgumentException(label + "真实路径越界");
            }
        } catch (IOException e) {
            // 目录被删除、损坏或无法解析时停止状态操作。
            throw new IllegalStateException("无法复核" + label, e);
        }
    }

    private void verifyAgentStateLeaves(Path sessionRoot) {
        // 固定集合覆盖 AgentState、旧版 Memory/Toolkit 以及 SDK 原子写临时文件。
        for (String leafName : AGENT_STATE_LEAVES) {
            Path leaf = sessionRoot.resolve(leafName);
            // 缺失文件表示首次运行或当前未使用兼容键，可以安全跳过。
            if (Files.notExists(leaf, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            // NOFOLLOW 检查拒绝有效或失效符号链接、目录、设备等非普通对象。
            if (Files.isSymbolicLink(leaf)
                    || !Files.isRegularFile(leaf, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("AgentScope 状态文件必须是普通文件: " + leafName);
            }
            try {
                // 真实路径必须仍是同一个固定 leaf，并保持在可信状态根内。
                Path real = leaf.toRealPath();
                if (!real.equals(leaf) || !real.startsWith(root)) {
                    throw new IllegalArgumentException("AgentScope 状态文件真实路径越界: " + leafName);
                }
            } catch (IOException e) {
                // 无法稳定解析文件时禁止进入框架调用。
                throw new IllegalStateException("无法复核 AgentScope 状态文件: " + leafName, e);
            }
        }
    }

    private static FileLock acquireLock(FileChannel channel, Duration timeout) throws IOException {
        // 使用单调截止时间限制锁等待。
        Instant deadline = Instant.now().plus(timeout);
        do {
            try {
                // 非阻塞尝试获取跨进程独占锁。
                FileLock acquired = channel.tryLock();
                if (acquired != null) {
                    return acquired;
                }
            } catch (OverlappingFileLockException ignored) {
                // 同一 JVM 已持锁时按相同超时规则重试。
            }
            try {
                // 短暂休眠避免锁竞争时忙等。
                Thread.sleep(25);
            } catch (InterruptedException e) {
                // 恢复中断标志并停止等待。
                Thread.currentThread().interrupt();
                throw new IOException("等待会话锁时被中断", e);
            }
        } while (Instant.now().isBefore(deadline));
        // 超时明确失败，不把无界等待伪装成成功。
        throw new IOException("等待会话锁超时");
    }

    private static void rejectLink(Path path, String label) {
        // 已存在的路径若是链接则拒绝，缺失路径由后续安全创建处理。
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("拒绝符号链接" + label);
        }
    }

    private static void closeAfterFailure(FileLock lock, FileChannel channel) {
        try {
            // 已取得的锁优先释放。
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // 清理失败不覆盖原始异常。
        }
        try {
            // 已打开通道始终关闭。
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // 清理失败不覆盖原始异常。
        }
    }

}
