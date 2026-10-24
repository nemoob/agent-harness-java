package dev.agentharness.demo.infra.repair;

import dev.agentharness.demo.domain.RepairProject;
import io.agentscope.core.tool.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 模型只获得三个受限工具，没有 Shell、任意写文件或修改测试的能力。 */
public final class RepairTools {
    private final RepairProject project;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger tests = new AtomicInteger();
    public RepairTools(RepairProject project) { this.project = project; }
    private String execute(String tool, Supplier<String> action) {
        // 工具调用数独立于模型轮数；越界拒绝也占预算。
        int count = calls.incrementAndGet();
        if (count > 16) return "REJECTED: 工具调用次数上限为16";
        System.out.println("[研发工具 #" + count + "] " + tool);
        try {
            String result = action.get();
            // 不把文件内容或未经校验的参数直接打印到终端。
            System.out.println("[研发工具 #" + count + "] RETURNED");
            return result;
        } catch (IllegalArgumentException refused) {
            System.out.println("[研发工具 #" + count + "] REJECTED");
            return "REJECTED: 路径、源码或表达式不符合固定练习约束";
        } catch (RuntimeException error) {
            System.out.println("[研发工具 #" + count + "] ERROR");
            return "ERROR: 工具未完成，请停止并说明未完成事项";
        }
    }
    @Tool(name="read_project_file",description="读取固定练习文件，仅 DateParser.java 或 CheckDates.java",readOnly=true)
    public String read(@ToolParam(name="file",description="练习文件名") String file) {
        return execute("read_project_file", () -> project.read(file));
    }
    @Tool(name="replace_expression",description="只替换 DateParser.java 唯一日期解析表达式。允许 LocalDate.parse(date, DateTimeFormatter.ofPattern(\"yyyy-MM-dd\"))、LocalDate.parse(date, DateTimeFormatter.ofPattern(\"uuuu-MM-dd\"))、LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)、LocalDate.parse(date, DateTimeFormatter.ofPattern(\"uuuu-MM-dd\").withResolverStyle(ResolverStyle.STRICT))；传完整旧/新表达式，不能改测试",readOnly=false)
    public String replace(@ToolParam(name="file",description="仅 DateParser.java") String file,
            @ToolParam(name="before",description="当前完整表达式") String before,
            @ToolParam(name="after",description="新的完整表达式") String after) {
        return execute("replace_expression", () -> project.replace(file,before,after));
    }
    @Tool(name="run_project_tests",description="用固定 javac/java 编译当前源码并运行只读日期测试，返回真实退出码与结果",readOnly=false)
    public String test() {
        return execute("run_project_tests", () -> {
            // 为程序基线和最终复验各预留一次，不让模型耗尽验收预算。
            if (tests.incrementAndGet() > 6) return "REJECTED: 模型测试次数上限为6";
            var result = project.test();
            return "passed=" + result.passed() + ", exit=" + result.exitCode() + "\n" + result.output();
        });
    }
}
