# 章节与代码入口

先确认目录，再复制命令。本书目前有两套互补的工程：逐章案例保留插件、会话和运行边界实验；DDD 案例提供公开的分层实现、研发助手和 A2A，以及第五、八章的离线教学实验。它们不是同一个目录下的不同模板，配置名称和状态文件不能混用。

## 只准备两次目录

**逐章案例**随本书材料提供：从小册 `v0.1` 目录进入 `examples/agentscope-demo`，需要 Java 17 和 Maven，按该目录 README 准备配置。下面表中的“逐章”均指这个目录。

**DDD 案例**在 [GitHub](https://github.com/nemoob/agent-harness-java/tree/docs/ddd-agentscope-example/examples/agentscope-ddd)：

```bash
git clone --branch docs/ddd-agentscope-example https://github.com/nemoob/agent-harness-java.git
cd agent-harness-java/examples/agentscope-ddd
```

DDD 的真实模型入口使用项目 Maven Wrapper；`run-lab.sh` 是不需要密钥的教学入口，具体构建要求见案例 README。下面表中的“DDD”均指这个目录。GitHub 根目录另外保留的确定性演示不属于本表，不要用它的命令替换章节命令。

## 按章运行

| 章节 | 目录 | 入口与观察目标 |
| --- | --- | --- |
| 01–03 认知与架构 | 无必做运行 | 看调用关系；想先体验，可在 DDD 运行 `./run.sh deepseek` 或 `./run.sh ollama` |
| 04 模型接入 | 逐章 | `./run.sh`；普通问题、工具调用、连续追问 |
| 05 Agent Loop | DDD；逐章作对照 | `./run-lab.sh loop normal`；固定响应演示接续。真实 AgentScope 对照在逐章运行 `./run.sh date` |
| 06 插件与模板 | 逐章 | `./run.sh date` 与 `./run.sh chat` |
| 07 Skill 与 MCP | 逐章 | `./run.sh skill` 与 `./run.sh mcp` |
| 08 上下文 | DDD；逐章作观察 | `./run-lab.sh context`；完整片段选择与预算。真实请求观察用逐章 `./run.sh context` |
| 09 会话与状态 | 逐章 | `./run.sh date --session demo`；跨进程恢复 |
| 10 知识与记忆 | 逐章 | `./run.sh memory`；检索和偏好 |
| 11 工作流 | 逐章 | `./run.sh workflow`；确定性校验后组织说明 |
| 12 子 Agent | 逐章 | `./run.sh parallel`；独立上下文与并行状态 |
| 13 A2A | DDD | 两个终端分别运行 `./run-a2a.sh server deepseek`、`./run-a2a.sh client 2025-02-29` |
| 14 服务发现 | 逐章 | 启动 `./run.sh a2a-server blue` 和 `green`；再运行 `./run.sh a2a-discover 2025-02-29` |
| 15 多实例 | 逐章 | 两个进程运行 `./run.sh date --session handoff`；自动检查 `mvn -q -Dtest=MultiInstanceSessionTest test` |
| 16 失败处理 | 逐章 | `mvn -q -Dtest=FailureBoundariesTest test` |
| 17 权限 | 逐章 | `mvn -q -Dtest=SecurityBoundaryTest test` |
| 18 运行观测 | 逐章 | `mvn -q -Dtest=OperationsTest test`；尚不是容器部署验收 |
| 19 测试与评测 | 逐章 | `mvn -q -Dtest=DateWorkflowTest test`；真实模型评测仍按题集另行执行 |
| 选做研发助手 | DDD | `./run-repair.sh deepseek` 或 `./run-repair.sh ollama` |
| 附录 A / B | 源码阅读 / DDD | dsh 固定版本源码解析 / 分层与模型配置指南 |

## 判断是否跑对了

先看目录和入口，再看日志和结束原因。第五、八章 `run-lab.sh` 使用固定响应，离线实验通过不能代替真实模型验收；它们也不会自动改变 `run.sh` 的 AgentScope 行为。各章原始实测记录保留对应日期与版本，测试数量不作为当前整个项目的统一质量评分。

现阶段没有把逐章工程整体搬进 DDD。这个表统一的是阅读和执行入口，避免同名 `run.sh` 造成误用；后续迁移某章时，应连同配置、测试与正文一起切换。
