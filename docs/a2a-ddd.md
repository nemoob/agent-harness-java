# A2A：把日期任务交给 DDD 服务端

这是 `examples/agentscope-ddd` 的真实模型入口。普通 Java 客户端通过 HTTP 把一个日期交给服务端；服务端创建独立 Agent，调用日期工具，再返回一条 Message。客户端不请求模型，也不读取模型密钥。

## 两个终端

需要 JDK 17、macOS 或 Linux 的 POSIX 文件权限。先按 [示例 README](../examples/agentscope-ddd/README.md) 配置模型。以下命令均在 `examples/agentscope-ddd` 执行。

终端 A：

```bash
./run-a2a.sh server deepseek
# 或先启动本机 Ollama，再使用已下载的工具模型：
OLLAMA_MODEL=qwen2.5:0.5b ./run-a2a.sh server ollama
```

两个服务命令二选一。同一目录只允许启动一个服务。DeepSeek 的 `DEEPSEEK_API_KEY` 只需要设置在服务端终端。

终端 B：

```bash
./run-a2a.sh client 2025-02-29
./run-a2a.sh client 2024-02-29
```

端口和随机令牌由服务端写入本机 `.a2a/connection.json`，客户端自动读取。不要复制这个文件到 Git、截图或共享目录。试完在终端 A 按回车停止；关闭后连接文件被删除。

## 代码怎样连接

| 位置 | 职责 |
| --- | --- |
| `bootstrap/A2aMain` | 先分流 server/client；仅 server 加载模型配置，装配日期 Toolkit |
| `app/RemoteDateService` | 验证日期形状，每次创建并关闭独立 `AssistantGateway` |
| `domain/AssistantGateway` | 复用模型助手端口 |
| `infra/AgentScopeGateway` | AgentScope 的模型与工具循环，复用 DeepSeek/Ollama 接入 |
| `infra/DateTool` → `domain/DateValidation` | 工具适配与确定性日期规则 |
| `infra/a2a/A2aTransport` | Card、报文校验、HTTP、认证、并发与响应 |
| `infra/a2a/LocalConnection` | 私有连接文件、实例锁、随机令牌与清理 |

`config/application.properties` 管模型名称和预算，`config/a2a-prompt.txt` 只用于服务端的日期任务；通用聊天和修复助手的提示词不变。请求不能指定供应商、提示词、工具、项目路径或任意问题。A2A 不装配 `RepairTools`，远程修复源码不在本例范围。

## 本次实际运行

2026 年 10 月 24 日，使用两个独立 JVM、上面的脚本入口和不含模型密钥的客户端环境，得到以下结果：

| 模型 | 工具与传输 | 回答质量 |
| --- | --- | --- |
| DeepSeek deepseek-flash | 两个日期分别调用一次 `check_date`、两次模型；客户端退出 0 | 与工具结果一致：2025-02-29 为 false，2024-02-29 为 true |
| Ollama qwen2.5:0.5b | 两个日期分别调用一次工具、两次模型；客户端退出 0 | 无效日期答成“不存在，有效”，存在自相矛盾；有效日期回答正确 |

Ollama 首轮只收到日期文本时没有调用工具，还误判无效日期。后续把应用任务写成明确要求调用 `check_date`，才得到表中的工具链路。两轮原始记录都保留：`attempt1` 对应只传日期的旧应用任务，其中 DeepSeek 成功、Ollama 失败；不带该后缀的最终记录才与当前源码匹配。不把改进后的连通当成稳定成功率。

客户端退出 0 只说明接收到支持的 direct Message。`MODEL_STOP` 只表示模型正常停止；二者都不等于答案正确。模型文本打印在 `[远端回答，非验收]` 后，控制字符与换行转义显示，不能伪造程序日志。

两个服务都还验证了 `03/04` 在客户端拒绝、退出 2，未发送到模型；回车停止后服务退出 0，连接凭据已清理。

原始记录：[DeepSeek 最终](evidence/a2a-ddd-deepseek-20261024.json)、[Ollama 最终](evidence/a2a-ddd-ollama-20261024.json)、[首轮 DeepSeek](evidence/a2a-ddd-deepseek-20261024-attempt1.json)、[首轮 Ollama](evidence/a2a-ddd-ollama-20261024-attempt1.json)。记录包含源码、公开配置及脚本 SHA-256，不含凭据。

## 协议与边界

按 [A2A v1.0.1 HTTP+JSON](https://a2a-protocol.org/v1.0.1/specification/) 选取 direct Message 子集：`GET /.well-known/agent-card.json`、`POST /message:send`，`A2A-Version: 1.0`、`application/a2a+json`、`ROLE_USER` / `ROLE_AGENT`。不支持 Task、Artifact、流式、推送、会话续接、发现、多实例、自动重试或跨机器部署，也没有第三方 SDK 互操作认证。

- 服务只绑定 `127.0.0.1`；Card 允许匿名读取，但执行必须提供随机 Bearer。客户端目标来自本机受限文件，核验 Card 后仍使用固定环回地址，禁代理、禁重定向。
- `.a2a` 为 0700，连接文件为 0600；持有文件锁才发布，临时文件原子替换。符号链接、宽权限凭据、超限或非法字段都被拒绝。
- 请求最多 8 KiB、响应最多 64 KiB；重复字段、尾随内容、额外字段、错误角色、多 Part、Origin 与 query 请求均拒绝。
- 最多一个日期任务在执行，第二个返回 429；网络工作线程与队列分别为 2 和 4。模型整轮配置不超过 180 秒，服务端连接上限 200 秒，客户端读等待 210 秒。请求体单独限 5 秒；启动脚本还设置 JDK 17 的请求头与连接限制。
- `close()` 停止接入并请求工作线程中断，最多等待 5 秒；这不证明供应商已撤销计算。强杀、机器掉电和同一 OS 账号恶意修改不在保证范围。

不存在的日期是正常业务结果。401、400 和 429 属于拒绝；请求发出后超时或断连可能结果未知。当前客户端统一停止，不自动重发，也没有把 `messageId` 当作幂等保证。

## 离线验证

从仓库根目录运行：

```bash
./mvnw -f examples/agentscope-ddd/pom.xml test
```

测试覆盖严格报文、Card 与响应、认证、版本、输入上限、慢请求、并发拒绝、客户端超时和重定向、资源关闭、凭据保护及日志注入。测试中的响应函数是受控替身；它们与真实模型记录分别计数。

仓库根目录 README 的 A2A `java -jar ...` 旅程是另一个确定性教学 fixture；本篇的入口始终是 `examples/agentscope-ddd/run-a2a.sh`。不要混用两套状态、命令或测试结论。

本次独立回归：DDD 示例 34 项、根工程 86 项均通过，详见 [离线汇总](evidence/a2a-ddd-offline-20261024.json)。旧修复助手的取消测试发现 PID 文件刚创建尚未写完的测试竞态，改成原子发布 PID 后连续复验五次通过；生产修复逻辑未改。
