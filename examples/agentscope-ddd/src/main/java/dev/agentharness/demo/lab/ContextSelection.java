package dev.agentharness.demo.lab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** 片段选择与完整请求字节预算，保留原始历史，不实现模型摘要。 */
public final class ContextSelection {
    public record Episode(String topic, List<Message> messages) {
        public Episode {
            // 快照列表避免选择过程中修改原历史。
            messages = List.copyOf(messages);
            if (topic == null || messages.isEmpty()) throw new IllegalArgumentException("交互片段不能为空");
            Message.validate(messages, true);
        }
        public boolean pending() {
            return messages.get(messages.size() - 1).role().equals("call");
        }
    }

    public static List<Message> assemble(List<Message> fixed, List<Episode> history, Message current) {
        // 固定说明包含约束与工具定义，每轮均显式放入请求。
        var request = new ArrayList<>(fixed);
        for (var episode : history) request.addAll(episode.messages());
        request.add(current);
        return List.copyOf(request);
    }

    public static List<Message> select(List<Message> fixed, List<Episode> history,
                                       Message current, Set<String> requiredTopics, int budget) {
        // 先检查必要材料本身；无法容纳时停止，而不是静默删掉用户要求。
        if (Message.bytes(assemble(fixed, List.of(), current)) > budget) {
            throw new IllegalStateException("必要输入已超预算");
        }
        // 输入历史也必须是有效串行链，不能靠裁剪掩盖旧的重复 ID 或孤立结果。
        Message.validate(history.stream().flatMap(e -> e.messages().stream()).toList(), true);
        if (!history.stream().map(Episode::topic).collect(java.util.stream.Collectors.toSet()).containsAll(requiredTopics)) {
            throw new IllegalStateException("缺少本轮所需主题证据");
        }
        // 有在途调用时先完成回填，原历史保持不动，不拼接下一条用户消息。
        if (history.stream().anyMatch(Episode::pending)) {
            throw new IllegalStateException("存在未完成工具链，保留原历史并等待结果回填");
        }
        var selected = new ArrayList<Integer>();
        for (int i = 0; i < history.size(); i++) {
            // 每个指定主题的最新事实为必选；主题由应用给出，不模拟语义检索。
            var episode = history.get(i);
            boolean latest = history.subList(i + 1, history.size()).stream()
                    .noneMatch(later -> later.topic().equals(episode.topic()));
            if (latest && requiredTopics.contains(episode.topic())) selected.add(i);
        }
        var required = materialize(fixed, history, current, selected);
        // 预算验收对象始终是完整请求，不能只量历史或摘要的长度。
        if (Message.bytes(required) > budget) throw new IllegalStateException("必要交互片段无法完整放入预算");
        for (int i = history.size() - 1; i >= 0; i--) {
            // 剩余额度从最近片段补充，超额时整段跳过。
            if (selected.contains(i)) continue;
            selected.add(i);
            if (Message.bytes(materialize(fixed, history, current, selected)) > budget) {
                selected.remove(Integer.valueOf(i));
            }
        }
        return materialize(fixed, history, current, selected);
    }

    private static List<Message> materialize(List<Message> fixed, List<Episode> history,
                                              Message current, List<Integer> indexes) {
        // 选择优先级不改变原时间顺序；原片段及其调用 ID 原样保留。
        var episodes = indexes.stream().sorted(Comparator.naturalOrder()).map(history::get).toList();
        var request = assemble(fixed, episodes, current);
        // 验证整份请求，防止片段局部有效但组合后出现重复调用 ID。
        Message.validate(request, false);
        return request;
    }

    public static String summarize(List<Message> request) {
        // 固定模型读取实际选出的消息；缺失回填时拒绝生成看似正常的答案。
        Message.validate(request, false);
        var results = request.stream().filter(m -> m.role().equals("tool")).map(Message::text).toList();
        if (results.isEmpty()) return "证据不足，无法汇总";
        // 不重新调用工具，仅组织本次请求中真正存在的工具事实。
        return String.join("；", results);
    }
}
