package fullstack.generated;

/**
 * 版本比较策略由模型生成的 GeneratedVersionPolicy 实现。
 * 合法版本必须包含 2–4 个十进制段，每段范围 0..999999，非零段禁止 leading zero。
 */
public interface VersionPolicy {
    boolean isAtLeast(String clientVersion, String minVersion);
}
