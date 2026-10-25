package dev.agentharness.demo.bootstrap;

import dev.agentharness.demo.adapter.ChatCli;
import dev.agentharness.demo.app.ChatService;
import dev.agentharness.demo.infra.*;
import java.nio.file.Path;

/** 唯一装配入口；业务应用层不依赖基础设施实现。 */
public final class Main {
    public static void main(String[] args) {
        try {
            // CLI 只接受一个供应商选择，其他参数从公开配置与环境变量读取。
            if (args.length > 1) throw new IllegalArgumentException("只接受一个模型供应商参数");
            var config = new AppConfig(Path.of("."), args.length == 0 ? "deepseek" : args[0], System.getenv());
            System.out.println("[配置] provider=" + config.provider + ", model=" + config.model
                    + ", requestTimeout=" + config.requestTimeout + ", turnTimeout=" + config.turnTimeout);
            try (var gateway = new AgentScopeGateway(config)) {
                ChatCli.run(new ChatService(gateway));
            }
        } catch (Exception error) {
            // 不输出第三方异常正文，避免把凭据或请求材料打印到公开日志。
            System.err.println("启动或调用失败（" + error.getClass().getSimpleName()
                    + "）。请检查环境变量、配置、模型服务及最近一条阶段日志。详见 README 排错表。");
            System.exit(1);
        }
    }
}
