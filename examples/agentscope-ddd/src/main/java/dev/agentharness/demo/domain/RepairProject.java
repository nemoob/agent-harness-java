package dev.agentharness.demo.domain;

/** 修复应用只依赖受限项目端口，不接收任意文件系统或命令执行器。 */
public interface RepairProject {
    record Check(boolean passed, int exitCode, String output) {}
    String read(String file);
    String replace(String file, String before, String after);
    Check test();
    String diff();
}
