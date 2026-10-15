# Fullstack Case Specification

> 决策来源：已冻结的架构方案。本文只整理可复现的产品契约，
> 不新增需求或弱化固定安全参数。

## 目标与范围

新增一个 Java 17 教学型全栈交付案例。六个独立 AgentScope `ReActAgent` 依次生成、
验证和建议发布一个前端与后端候选；Harness 负责权限、工作区、预算、构建、测试、
发布和回滚裁决。真实模型只生成允许的业务制品，不能生成或修改安全关键 HTTP、
认证、JSON、状态仓库、Runner 或 Release Gate 实现。

本轮不引入 AgentScope Anthropic provider，不执行模型给出的命令，不使用宿主机运行
生成代码，不写生产或外部系统，不把 replay 宣称为 real。默认 `clean verify` 不调用
远端模型。Nacos、远端发布、完整生产认证和多主机分布式并发不在范围内。

## CLI 与模式

单 JAR 新增命令：

```text
fullstack --mode real|replay \
  --requirement-file <受限文件> \
  --state-root <可信根> \
  --session <ASCII ID> \
  --preview-port <loopback port>
```

`real` 缺少可信 `ANTHROPIC_API_KEY`、`ANTHROPIC_BASE_URL` 或 `ANTHROPIC_MODEL`
时稳定失败。`replay` 报告必须显式包含 `modelMode=REPLAY`、
`realModelRequests=0`。requirement 文件必须是既有真实普通文件，leaf 拒绝符号链接和路径
别名，并在读取前限制为 64 KiB；它不要求位于 `state-root`。`state-root` 必须预先创建为
真实目录，并由后续状态组件继续逐级执行链接与越界检查。

## 六角色与八次主链调用

角色为 `ARCHITECT`、`FRONTEND`、`BACKEND`、`TEST`、`TROUBLESHOOT`、
`RELEASE`。每个角色有独立 session、AgentScope state 和 Toolkit，只能调用统一的
`submit_role_output`。模型 payload 顶层只允许 `outputs`，`role`、`invocation` 与 lineage
由服务端任务包绑定；工具按角色固定 schema 校验，写入 provenance 后才返回结果。

主链固定八次角色调用：架构、前端、后端故障版、测试失败、排障、后端修复、测试
通过、上线建议。通常每次 Agent 调用包含模型工具请求与最终模型响应，预期共 16 个
真实模型请求。Harness 以确定性状态机迁移；模型不能改变 grant、hash、runner 结果、
测试结论或发布结论。

每个 `TaskPacket` 只携带 `capabilityGrantRef`，不携带可伪造的能力内容。每个角色输出
记录 role invocation、输入 lineage、prompt SHA-256、模型名和 artifact SHA-256；不得
保存完整 prompt、provider key 或带秘密的请求/响应。

## Provider 与预算

AgentScope core 2.0.4 没有 Anthropic provider。本项目实现受控 Anthropic Messages
transport，固定使用 `/usr/bin/curl --disable --config -`，完整 curl config、认证 header 和请求体
只经 stdin 传入；argv、文件和日志不得包含秘密。Transport 只接受 HTTPS base，使用
`--proto =https`、禁止 redirect、连接超时 10 秒。普通角色的 curl 总/读取上限为 120 秒、
Java 进程上限为 125 秒；FRONTEND 首轮分别为 180 秒与 185 秒；两者都会缩短到持久任务
截止时间的实际剩余量。HTTP base、CLI 或模型
覆盖 host 均拒绝；错误不得回显 endpoint、key、完整 prompt 或完整响应。

任务总请求硬上限为 28，output 硬上限为 60000。当前恢复现场固定结转 14 次请求和 38079
output token；剩余第一轮 cap 依 invocation 固定为
`3072/4096/1024/1024/4096/1024/1024`，七个匹配 ToolResult 后的最终轮各 256，
最坏累计 55231。每次联网前先原子持久化请求数与完整 cap；成功 usage 释放差额，usage
缺失、结果未知、超时或中断保留完整 cap。明确 pre-connect 失败只释放输出 cap，仍计
一次请求；所有失败路径均不自动重试。新 run 的绝对截止时间为 30 分钟并写入账本 v2；
重启读取同一 deadline。本次精确审计的 v1 现场只允许一次迁移，以迁移时刻设置 20 分钟
deadline，之后不得续期；缺失、过期、未来时间、时钟回拨或绑定篡改均 fail closed。
角色、invocation 与 final 阶段只取服务端 TaskPacket/匹配 ToolResult，
环境、模型和外部输入只能进一步收紧预算。

外部准备阶段已有一次独立真实 probe 和一次两请求 spike，均纳入任务历史预算。受信启动器仅为
本次子进程把同一受信配置中的 host/path 规范化为 HTTPS；不修改全局环境，也不打印
URL 或 key。产品 transport 自身始终拒绝 HTTP，以及来自 CLI 或模型的 endpoint 覆盖。

## 固定 Scaffold 与生成制品

固定资源位于 `src/main/resources/fullstack/scaffold/`，包含 Java 17 同源
`ConfigHttpServer`、`FeatureStore`、`VersionPolicy` 接口、`GeneratedAppChecks` 和
manifest。固定 API：

- `GET /api/features`
- `PUT /api/features/{id}`
- `POST /api/features/{id}/preview`
- `GET /health`

请求体最大 16 KiB、响应最大 64 KiB、超时 5 秒。版本号必须有 2–4 段，每段
`0..999999`，非零段禁止前导零。revision 冲突返回 409。页面禁止 `innerHTML`。
PUT/POST 必须具有可信 session、CSRF token 和精确 Origin。一次性 bootstrap token
只写入权限 0600 的运行文件，换取 `HttpOnly`、`SameSite` cookie 后立即失效；token
不得进入日志、文档或最终报告。

FRONTEND 必须生成完整 `web/index.html`、`web/styles.css`、`web/app.js`。BACKEND
第一次根据显式教学故障生成使用字符串比较的 `GeneratedVersionPolicy.java`；修复时
必须消费真实测试失败与 TROUBLESHOOT 证据，生成数字分段比较的新文件。两版及其 hash
都进入 provenance，旧 PASS 证据不能用于新候选。
FRONTEND 的 `outputs.files` 必须恰好包含上述三个路径且值为非空字符串，三个文件 UTF-8
内容合计不超过 20480 字节；任意缺失、额外路径、类型变化或超限在工具阶段拒绝。
FRONTEND 上下文仅投影架构摘要和固定 API、认证、错误、响应式与无障碍契约，不注入架构
计划或时间线。其他大源码制品可以在 256 KiB 工具上限内落盘，但不会直接
注入受限的 16 KiB 单制品/24 KiB 动态上下文；下游只消费 hash、manifest、runner 证据
或有界摘要。

## Capability Grant 与安全工作区

`CapabilityGrantRegistry` 在服务端绑定 principal、project、workspace 真实根、base
revision、role、tool allowlist、路径 allowlist、target、输入 artifact SHA、expiry 和
revoked 状态。每次工具调用重新读取并校验。必须拒绝伪造引用、撤销、过期、跨 project、
跨 role、跨 workspace、错误 base、错误 tool、错误参数和 artifact hash。

`SafeWorkspace` 使用 NIO 管理完整候选文件集、diff 和 manifest。路径逐级拒绝符号链接；
既有对象 realpath 必须位于可信根，新对象先验证真实父目录。必须覆盖 leaf 链接、中间
链接、越界与边界置换。一次变更最多 64 文件、单文件 256 KiB、总 patch 1 MiB；任何
文件失败则整批拒绝且不部分写入。

## Docker Runner

镜像固定为：

```text
mirror.gcr.io/library/eclipse-temurin@sha256:420933bf319e6aff4bec22eff8d2a31c824df5d853432e3dc0f46d6ea8dd5919
```

该 digest 是多平台 OCI index；Runner 固定 `--platform linux/arm64`，当前验证环境为
macOS + Colima，JDK
`17.0.20.1+1`。Runner 通过受信 `PATH` 使用固定名称 `docker`，只用固定命令模板和独立参数数组，禁止
`sh -c`。容器必须 non-root、build/test `network=none`、read-only root filesystem、
drop all capabilities、`no-new-privileges`、无 host socket、秘密或 control directory，
只有一个 workspace 可写挂载，限制 1 CPU、512 MiB、64 PID、120 秒；并发 2、队列 8；
stdout/stderr 各最大 1 MiB，超限终止。固定 entrypoint 只允许 `javac`、`java`、`jar`。
已审计基础 shaded JAR 只读作为 classpath/base，Runner 生成候选单 JAR，并在容器中实际
启动 loopback 服务和运行 API/静态页面检查。

preview 与 build runner 分离。candidate 只连接每候选独立 internal network；固定 gateway
连接该 internal network 与独立 publish network，并只发布
`127.0.0.1:<preview-port>:8080`。gateway 固定拨 `candidate:8080`，拒绝动态 URL、CONNECT、
歧义 HTTP framing 与逐跳 header。两类容器保持固定安全 flags 和资源限制，无外网。

## Release Gate 与五条实证路径

`ReleaseGate` 在任何容器动作前验证 deploy grant、candidate hash、当前候选对应的测试
证据、目标、health 和替换已有实例所需的 rollback anchor。模型只给建议，最终结果由
Gate 决定。

必须实证：

1. **Real success**：生成前端、故障/修复后端；Runner 构建测试；Gate 部署；真实 HTTP
   页面与 `2.10 >= 2.9` 检查 PASS。
2. **Defect loop**：初始字符串比较实际 FAIL；TEST 读取真实 runner 结果；
   TROUBLESHOOT 归因；BACKEND 修复产生新 hash；TEST 重跑 PASS，旧 PASS 不可复用。
3. **Blocked deploy**：无有效 deploy grant、candidate hash 错均为 `DEPLOY_BLOCKED`，
   Docker 副作用计数为 0。
4. **Rollback**：先有健康稳定候选；可信故障开关令新候选 health 失败；停止新候选、
   恢复上一 manifest，重做 health 与业务检查后返回 `ROLLED_BACK`。
5. **First failure**：无 rollback anchor 时首次候选 health 失败，返回 `DEPLOY_FAILED`，
   不声称 rollback 或 recovered。

替换事务中 gateway create/connect/start 任一点失败都先逆序清理新候选，再在稳定端口
恢复旧 gateway，并重做 health 与业务检查；恢复动作或复验失败返回 `ROLLBACK_FAILED`。
每个删除动作随后按精确名称 inspect；任一新资源残留时 replacement 必须返回
`ROLLBACK_FAILED`，首次部署返回 `CLEANUP_FAILED`，且 `cleanupComplete=false` 和受控
资源类型/ID 会进入结构化结果。正常切换清旧资源失败也不能返回 `DEPLOYED`。
若新版本已经通过 health 与业务检查、且未实际执行回滚，只有旧资源清理残留时返回
`CLEANUP_FAILED`；`ROLLBACK_FAILED` 只用于已经尝试恢复旧 anchor 但恢复或复核失败。

REAL 中断恢复采用同一 runId 的受信 open-or-create。每个已接受角色原子保存绑定
session、requirement SHA-256、mode 与 base revision 的 checkpoint；恢复时复算
prompt/artifact/payload/session/state SHA。完整四消息角色不再调用模型，单 USER 的
`UNKNOWN_RESULT` attempt 保留证据并切换到新 session。
角色输出 schema 升级前的历史 prompt/artifact 字节不向前兼容；不得离线重写历史状态，
此类中断 run 保留为证据并从全新 run 开始。

负例由确定性 Gate/Runner 完成，不额外消耗模型预算。replay fixture 位于
`src/main/resources/fullstack/replay/`，必须脱敏并真实经过 AgentScope、工具、工作区、
Runner 和 Release Gate；报告不得标记为 real。

## TDD 与证据退出

按以下顺序实施，每项先失败测试、后最小实现、定向测试通过并记录 checkpoint hash，
禁止 Git add/commit：

1. `ModelBudgetTest`、`RoleAgentExecutorTest`，随后 provider 与 ARCHITECT 真实 spike。
2. `CapabilityGrantRegistryTest`、`SafeWorkspaceTest`。
3. `GeneratedAppContractTest` 与固定 scaffold。
4. `DockerRunnerCommandTest`、真实 `FullStackDockerIT`。
5. `FullStackReplayIT`、服务、CLI 与 replay。
6. `PreviewReleaseIT`、发布适配器。
7. 一次真实 fullstack 链，保存 `docs/evidence/fullstack-real-run.json`、脱敏事件、usage、
   manifest；断言六角色均至少一次真实 AgentScope，生成物来源为 MODEL，且预算内无秘密、
   绝对本机路径或完整 prompt。
8. 更新 README、product-scope、architecture、security、verification、dependency-tree 与
   必要 NOTICE。只有新增依赖时才修改依赖与 NOTICE。

最终使用隔离 Maven settings 执行 `clean verify`，再运行 replay Docker 五路径及一次 real
链。交付证据包含命令与退出码、测试报告、镜像 digest/inspect、真实请求数/output/wall、
六角色 provenance、故障/排障/修复 hash、发布阻断/回滚/首次失败副作用、HTTP loopback
地址、运行目录、源码/制品 hash、原始测试/事件路径、HEAD/status 和未知边界。认证只回报
可信本地 0600 文件路径，不输出 bootstrap token 或 provider key。replay 成功会清理并
返回 `DEPLOYED_CLEANED`；只有 REAL 成功预览保留至入口截图完成。
