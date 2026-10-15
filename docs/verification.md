# Verification evidence

Evidence date: 2026-10-15 (Asia/Shanghai). All Maven commands used the checked-in
settings and the project-local `.m2/repository`; no system or private repository
settings were used.

## Toolchain

- Java: Oracle JDK `17.0.12+8-LTS-286`.
- Host Maven observed before wrapper generation: `3.6.3`.
- Checked-in wrapper: official only-script `3.3.4`, Apache Maven `3.9.11`.
- Wrapper distribution SHA-256:
  `0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb`.
- `MAVEN_USER_HOME=$PWD/.m2/wrapper-home ./mvnw ... -version`: exit `0`, reported
  Maven `3.9.11` and JDK `17.0.12`.

## Build and tests

The common prefix for the commands below was:

```text
MAVEN_USER_HOME=$PWD/.m2/wrapper-home ./mvnw \
  -s .mvn/settings.xml -gs .mvn/global-settings.xml \
  -Dmaven.repo.local=$PWD/.m2/repository
```

| Goal | Exit | Result |
|---|---:|---|
| `compile` | 0 | 27 Java 17 production sources compiled |
| `test` | 0 | 29 JUnit unit/contract tests passed |
| `package` | 0 | `target/agent-harness-java.jar` created |
| `verify` | 0 | 29 unit/contract tests and 1 `ResumeProcessIT` passed |

Reports are written to `target/surefire-reports/` and
`target/failsafe-reports/`. `ResumeProcessIT` launches two independent
`java -jar` processes through a fixed `java.home/bin/java` path, waits at most
20 seconds per process, bounds captured output to 65,536 bytes, and verifies
that the completed configuration tool remains at call count 1.

The AgentScope spike asserts exactly two model calls, one tool call, generated
tool schema presence, a second-round `ToolResultBlock` with matching ID/name,
and a final response with no `ToolUseBlock`. The agent is closed through
try-with-resources. State tests cover different-session isolation, pre-created
application/AgentScope/intermediate symlinks, corrupt-state lock cleanup, and
atomic snapshots. Every fixed AgentScope primary, legacy, hash, and SDK `.tmp`
leaf is also pre-created as a link in turn; each is rejected before Store use
without modifying its external target. A separate call-boundary test replaces a
previously validated leaf and verifies the immediate pre/post-call guard rejects
it without changing the target.

A2A regressions cover 64 KiB streaming limits in both directions, a three-second
client response deadline, a one-second server request deadline, four occupied
workers recovering, executor queue saturation rejecting promptly and then
recovering, fixed task-state capacity returning a stable 503 error, exact media
types, duplicate JSON fields, response-union conflicts, and task/context/
business/artifact mapping mismatches. The workflow test also verifies that the
artifact tool declares its state write with `readOnly=false`. Additional A2A
cases reject a malicious Get Task ID/context/business substitution and a direct
Message task-ID injection; reply-only with an unknown Task returns 404 and a
wrong mapping returns 409.

## Five journeys executed from the shaded JAR

Every listed CLI process exited `0`; server shutdown by the verification harness
used Ctrl-C after the client response.

| Journey | Observed evidence |
|---|---|
| Normal diagnosis | `status=COMPLETE`, `modelCalls=2`, both tool call counts `1`, matching tool-result IDs, final has no tool use |
| Missing evidence | Independent state/session returned `status=INSUFFICIENT_EVIDENCE`; request evidence was `present=false` |
| Cross-process resume | First process returned `REQUEST_PENDING` with config count `1`; second returned `COMPLETE` with config/request counts `1/1` and final text `COMPLETE` |
| Four-role workflow | Route was `diagnosis, requirements, development, testing`; all four artifacts recorded packet/predecessor/evidence IDs; test fixture observed `expected=enabled;actual=enabled` and `PASS` |
| A2A loopback | Server reported `RUNNING` on `127.0.0.1:8080`; client validated Card `1.0`, received `task-1`, `ctx-001`, `CFG-001`, and `artifact-task-1` |

Contract tests additionally cover a direct Message result, invalid version and
media type, missing Message fields, bad Card interface/version, incomplete
Task/Artifact, oversized/invalid IDs, and unknown-task reconciliation without a
resend.

## Dependency and license evidence

`mvn ... dependency:tree -Dscope=runtime` exited `0`; the complete resolved tree
is in `dependency-tree.txt`. `agentscope-core:2.0.4` declares Jackson 2.21.1,
while this project imports `com.fasterxml.jackson:jackson-bom:2.21.7` and leaves
the direct databind dependency version to that BOM. The runtime tree resolves
databind/core/JSR-310/YAML to the single 2.21.7 repair line, with no 2.21.1
component. Annotations remains at its normal upstream `2.21` major.minor line.

All runtime coordinates were checked against their repository-local published
POM license fields and embedded NOTICE/LICENSE resources. The resulting
Apache-2.0, MIT, MIT-0, and Jackson-bundled component notices are recorded in
`THIRD_PARTY_NOTICES.md`; the Maven Wrapper source and distribution relationship
is included there as well. MCP Java SDK 0.17.2 and tree-sitter-ng 0.24.4 retain
their complete fixed-tag MIT copyright and permission text; Commons Compress
1.27.1 retains its artifact NOTICE.

The independent S1 review identified three retained transitive conditions:
`mcp-core:0.17.2` has CVE-2026-35568/CVE-2026-34237,
`opentelemetry-api:1.61.0` has CVE-2026-45292, and
`commons-lang3:3.16.0` has CVE-2025-48924. Current first-release source paths
showed no trigger: there is no MCP server/CORS configuration entry, W3C baggage
propagation, or Commons Lang call. Before enabling those paths, use respectively
at least MCP 1.0.1, OpenTelemetry 1.62.0, and Commons Lang 3.18.0. MCP exclusion
also requires demonstrated AgentScope 2.0.4 binary compatibility and a full
test pass. Full wording is in `security.md`; this is not a claim that the
retained versions passed a general vulnerability scan.

The final shaded JAR SHA-256 is
`1409eec89a1dfea80cd02834a8bdd16877556323548479ddd651254b68f62c4b`.
`META-INF/LICENSE`, `META-INF/NOTICE`, and
`META-INF/THIRD_PARTY_NOTICES` were opened from the JAR and matched their
three root files byte for byte.

## Security self-check and limits

The diff was checked for interface authorization assumptions, loopback-only
network targets, fixed HTTP paths, redirect rejection, bounded bodies/timeouts,
JSON fixed types, path/symlink handling, secrets/logging, tool allowlists, and
bounded process execution. No credential, arbitrary command, shell, arbitrary
URL, production endpoint, or dangerous deserialization path was added.

The shade plugin reports upstream overlapping metadata and eight duplicate MCP
JSON classes contributed by the AgentScope 2.0.4 dependency graph. All required
v0.1 commands and tests pass from the shaded JAR, but unused AgentScope MCP paths
were not enabled or certified. Nacos, Docker, remote LLMs, distributed locking,
complete A2A authentication/protocol coverage, and third-party SDK
interoperability remain unverified and outside v0.1.
