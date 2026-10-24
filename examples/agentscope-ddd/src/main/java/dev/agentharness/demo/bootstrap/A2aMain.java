package dev.agentharness.demo.bootstrap;

import dev.agentharness.demo.app.RemoteDateService;
import dev.agentharness.demo.domain.DateValidation;
import dev.agentharness.demo.infra.*;
import dev.agentharness.demo.infra.a2a.*;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** 简单双终端入口：server [provider] / client yyyy-MM-dd。 */
public final class A2aMain {
    public static void main(String[] args) {
        int code = 2;
        try {
            // 客户端分支先执行，不加载 AppConfig，也不需要模型 Key。
            if (args.length == 2 && args[0].equals("client")) {
                A2aTransport.checkDate(args[1]);
                A2aTransport.client(LocalConnection.load(Path.of(".")), args[1]);
            } else if ((args.length == 1 || args.length == 2) && args[0].equals("server")) {
                serve(args.length == 2 ? args[1] : "deepseek");
            } else throw new IllegalArgumentException("arguments");
            code = 0;
        } catch (Exception failure) {
            // 不打印令牌或模型供应商异常；发送后失败可能结果未知，不自动重试。
            System.err.println("[A2A] 未完成。用法：./run-a2a.sh server [deepseek|ollama] / client yyyy-MM-dd；检查配置、占用和上一条阶段日志。发送后失败可能结果未知，不自动重发。");
        }
        // CLI 显式退出；资源 close 已先执行，但不能因此宣称供应商撤销了生成。
        System.exit(code);
    }
    private static void serve(String provider) throws Exception {
        var config = new AppConfig(Path.of("."), provider, System.getenv());
        // 应用整轮上限先于网络传输上限；默认 180 秒可直接使用。
        if (config.turnTimeout.getSeconds() > 180) throw new IllegalArgumentException("turn timeout exceeds A2A limit");
        Path base = Path.of("config").toRealPath(), file = base.resolve("a2a-prompt.txt").toRealPath();
        if (!file.startsWith(base) || Files.size(file) > 32768) throw new IllegalArgumentException("prompt");
        String prompt = Files.readString(file);
        if (prompt.isBlank()) throw new IllegalArgumentException("prompt");
        var application = new RemoteDateService(() -> {
            // 每次只装配日期工具，绝不把修复工具接到网络服务。
            var toolkit = new Toolkit();
            toolkit.registerTool(new DateTool(new DateValidation()));
            return new AgentScopeGateway(config, prompt, toolkit);
        });
        try (var local = new LocalConnection(Path.of("."));
             var server = new A2aTransport.Service(local.token(), application::answer)) {
            local.publish(server.port());
            System.out.println("[A2A 服务] 127.0.0.1:" + server.port() + ", provider=" + config.provider + ", model=" + config.model + ", capacity=1");
            System.out.println("[A2A 服务] 另一个终端运行 ./run-a2a.sh client 2025-02-29；此处按回车停止。");
            // 回车或 EOF 触发有界 close，服务端退出不持久化 Agent 历史。
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        }
    }
}
