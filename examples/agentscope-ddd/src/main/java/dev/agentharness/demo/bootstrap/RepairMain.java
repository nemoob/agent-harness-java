package dev.agentharness.demo.bootstrap;

import dev.agentharness.demo.infra.*;
import dev.agentharness.demo.infra.repair.*;
import dev.agentharness.demo.app.RepairService;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.*;

/** 独立单任务入口：自带练习目录，不接受用户仓库路径。 */
public final class RepairMain {
    public static void main(String[] args) {
        int code = 1;
        try {
            // 仅接受供应商选择；应用配置、密钥规则与通用助手一致。
            if (args.length > 1) throw new IllegalArgumentException("只接受供应商参数");
            var config = new AppConfig(Path.of("."), args.length == 0 ? "deepseek" : args[0], System.getenv());
            String prompt = readConfig("repair-prompt.txt");
            String task = readConfig("repair-task.txt");
            try (var project = new RepairWorkspace()) {
                var toolkit = new Toolkit();
                toolkit.registerTool(new RepairTools(project));
                System.out.println("[研发助手] provider=" + config.provider + ", model=" + config.model);
                try (var assistant = new AgentScopeGateway(config, prompt, toolkit)) {
                    code = new RepairService().run(assistant, project, task) ? 0 : 2;
                }
            }
        } catch (Exception error) {
            // 不输出可能含供应商请求的异常正文，失败使用非零退出码。
            System.err.println("[研发助手] ERROR，检查配置与最后阶段日志");
        }
        System.exit(code);
    }
    private static String readConfig(String name) throws Exception {
        // 固定配置名且限制在配置目录内，不允许符号链接越界或大文件输入。
        Path base = Path.of("config").toRealPath();
        Path file = base.resolve(name).toRealPath();
        if (!file.startsWith(base) || Files.size(file) > 32768) throw new IllegalArgumentException("配置路径或大小错误");
        String value = Files.readString(file);
        if (value.isBlank()) throw new IllegalArgumentException("配置不能为空");
        return value;
    }
}
