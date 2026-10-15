package dev.agentharness.fullstack;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一次候选变更的完整 UTF-8 文件集合。 */
public record WorkspacePatch(Map<String, String> files) {
    public WorkspacePatch {
        // 保留调用方顺序的不可变副本，便于稳定 diff 和测试。
        files = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }
}
