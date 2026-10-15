package dev.agentharness.state;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 持有单会话文件锁的可变事务视图。 */
public final class LockedSession implements AutoCloseable {
    private final AppStateStore store;
    private final String session;
    private final FileChannel channel;
    private final FileLock lock;
    private AppState state;

    LockedSession(
            AppStateStore store,
            String session,
            FileChannel channel,
            FileLock lock,
            AppState state) {
        // 保存经过校验的仓库引用。
        this.store = store;
        // 保存白名单会话标识。
        this.session = session;
        // 保存锁文件通道用于关闭。
        this.channel = channel;
        // 保存独占文件锁用于释放。
        this.lock = lock;
        // 保存加锁后读取的最新状态。
        this.state = state;
    }

    public synchronized AppState state() {
        // 返回不可变状态快照供协调器判断恢复点。
        return state;
    }

    public synchronized boolean isCompleted(String action) {
        // 已完成动作集合是工具去重的唯一应用层依据。
        return state.completedActions().contains(action);
    }

    public synchronized void markPending(String action, String phase) {
        // 用有序集合保持 JSON 输出稳定并去重。
        Set<String> pending = new LinkedHashSet<>(state.pendingActions());
        // 登记下一步动作。
        pending.add(action);
        // revision 每次持久化增加一次。
        state =
                new AppState(
                        session,
                        state.revision() + 1,
                        phase,
                        state.facts(),
                        state.evidence(),
                        List.copyOf(pending),
                        state.completedActions(),
                        state.callCounts());
        // 立即原子保存恢复点。
        store.write(session, state);
    }

    public synchronized void recordEvidence(String action, String evidenceId, String summary) {
        // 已完成动作禁止再次产生工具副作用或调用计数。
        if (isCompleted(action)) {
            throw new IllegalStateException("动作已完成，拒绝重复执行: " + action);
        }
        // 复制事实表并写入本动作的脱敏摘要。
        Map<String, String> facts = new LinkedHashMap<>(state.facts());
        facts.put(action, summary);
        // 复制证据列表并追加稳定 evidenceId。
        List<Evidence> evidence = new ArrayList<>(state.evidence());
        evidence.add(new Evidence(action, evidenceId, summary));
        // 复制调用计数并仅增加当前工具一次。
        Map<String, Integer> counts = new LinkedHashMap<>(state.callCounts());
        counts.merge(action, 1, Integer::sum);
        // 构造新的不可变状态快照。
        state =
                new AppState(
                        session,
                        state.revision() + 1,
                        state.phase(),
                        facts,
                        evidence,
                        state.pendingActions(),
                        state.completedActions(),
                        counts);
        // 工具返回前原子落盘，支持进程中断后的去重判断。
        store.write(session, state);
    }

    public synchronized void complete(String action, String phase) {
        // 从待执行集合中移除已完成动作。
        Set<String> pending = new LinkedHashSet<>(state.pendingActions());
        pending.remove(action);
        // 把动作加入完成集合，后续进程据此跳过工具。
        Set<String> completed = new LinkedHashSet<>(state.completedActions());
        completed.add(action);
        // 构造带新阶段的新快照。
        state =
                new AppState(
                        session,
                        state.revision() + 1,
                        phase,
                        state.facts(),
                        state.evidence(),
                        List.copyOf(pending),
                        List.copyOf(completed),
                        state.callCounts());
        // 在释放会话锁前持久化完成标记。
        store.write(session, state);
    }

    @Override
    public void close() {
        try {
            // 先释放独占文件锁。
            lock.release();
            // 再关闭锁文件通道。
            channel.close();
        } catch (IOException e) {
            // 锁释放失败必须暴露，避免误判并发边界。
            throw new IllegalStateException("无法释放会话锁", e);
        }
    }
}
