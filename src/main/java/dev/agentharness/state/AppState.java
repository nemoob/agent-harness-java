package dev.agentharness.state;

import java.util.List;
import java.util.Map;

/** 应用层恢复状态；AgentScope 自身上下文由独立的 AgentStateStore 管理。 */
public record AppState(
        String session,
        long revision,
        String phase,
        Map<String, String> facts,
        List<Evidence> evidence,
        List<String> pendingActions,
        List<String> completedActions,
        Map<String, Integer> callCounts) {

    public AppState {
        // 复制所有集合，避免调用方在持久化后修改状态。
        facts = Map.copyOf(facts);
        // 复制证据列表，确保 revision 对应不可变快照。
        evidence = List.copyOf(evidence);
        // 复制待执行动作，避免跨线程共享可变列表。
        pendingActions = List.copyOf(pendingActions);
        // 复制已完成动作，作为恢复去重依据。
        completedActions = List.copyOf(completedActions);
        // 复制调用计数，作为不重复执行的现场证据。
        callCounts = Map.copyOf(callCounts);
    }

    public static AppState initial(String session) {
        // 新会话从 NEW 和空证据开始。
        return new AppState(session, 0, "NEW", Map.of(), List.of(), List.of(), List.of(), Map.of());
    }
}
