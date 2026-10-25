package dev.agentharness.demo.infra;

import java.nio.file.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/** 公开配置不存密钥；不生成会输出密钥的 toString。 */
public final class AppConfig {
    public final String provider, model, prompt;
    public final Duration requestTimeout, turnTimeout;
    public final int maxIterations, maxTokens;
    private final String apiKey;

    public AppConfig(Path root, String provider, Map<String, String> env) throws IOException {
        // 接入实现限定为两种，避免任意注册表名称或地址进入模型层。
        if (!provider.equals("deepseek") && !provider.equals("ollama")) {
            throw new IllegalArgumentException("仅支持 deepseek 或 ollama");
        }
        this.provider = provider;
        var properties = new Properties();
        // 配置按 UTF-8 读取，允许提示词包含中文。
        try (var reader = Files.newBufferedReader(root.resolve("config/application.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String modelEnv = provider.equals("deepseek") ? "DEEPSEEK_MODEL" : "OLLAMA_MODEL";
        this.model = env.getOrDefault(modelEnv, properties.getProperty(provider + ".model", ""));
        // 限制模型标识，避免把任意控制字符打印进启动日志。
        if (!model.matches("[a-zA-Z0-9][a-zA-Z0-9_.:/-]{0,127}")) throw new IllegalArgumentException("模型名称格式错误");
        this.apiKey = env.getOrDefault("DEEPSEEK_API_KEY", "");
        if (provider.equals("deepseek") && apiKey.isBlank()) throw new IllegalArgumentException("请设置 DEEPSEEK_API_KEY");
        this.requestTimeout = Duration.ofSeconds(number(properties, "request.timeout.seconds", 1, 300));
        this.turnTimeout = Duration.ofSeconds(number(properties, "turn.timeout.seconds", 1, 900));
        this.maxIterations = number(properties, "max.iterations", 1, 30);
        this.maxTokens = number(properties, "max.tokens", 1, 8192);
        // 提示词限定在示例目录内，解析真实路径以拒绝越界符号链接。
        Path promptPath = root.resolve(properties.getProperty("prompt.file", "config/system-prompt.txt")).toRealPath();
        if (!promptPath.startsWith(root.toRealPath()) || Files.size(promptPath) > 32768) throw new IllegalArgumentException("提示词路径越界或文件过大");
        this.prompt = Files.readString(promptPath, StandardCharsets.UTF_8);
        if (prompt.isBlank()) throw new IllegalArgumentException("提示词不能为空");
    }
    public String apiKey() { return apiKey; }
    private static int number(Properties properties, String key, int min, int max) {
        // 缺失、格式错误或越界配置都在启动时拒绝，不静默使用其他值。
        int value = Integer.parseInt(properties.getProperty(key, ""));
        if (value < min || value > max) throw new IllegalArgumentException("配置越界: " + key);
        return value;
    }
}
