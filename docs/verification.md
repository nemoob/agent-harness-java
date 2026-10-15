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

The pre-fullstack v0.1 checkpoint shaded JAR SHA-256 was
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
interoperability remain outside the base v0.1 journeys. The optional fullstack
extension below separately verifies its bounded Docker path.

## Fullstack implementation freeze

The optional fullstack extension was verified on 2026-10-15 with the same
isolated wrapper settings. The fresh command was:

```text
./mvnw -s .mvn/settings.xml -gs .mvn/global-settings.xml \
  -Dmaven.repo.local=$PWD/.m2/repository clean verify
```

It exited `0` in 2 minutes 17 seconds. Surefire ran 77 tests with no failure,
error, or skip. Failsafe ran 13 tests with no failure or error and skipped the
opt-in real-model spike. Reports are under `target/surefire-reports/` and
`target/failsafe-reports/`. Default verification made no remote model request.
The resulting shaded JAR SHA-256 is
`1dc2e7bc38cfde97dd34abaa12bb1a3f0f84a883e0e194eae767ba0000db45f0`.

The focused schema/budget command selected `ModelBudgetTest`,
`RoleAgentExecutorTest`, `FullStackResumeTest`, and `SafeWorkspaceTest`; it
exited `0` with 25 tests. It covers outputs-only identity binding, strict
duplicate/trailing-token rejection, the exact three-file frontend path set,
cross-write/read canonical artifact SHA, the 33783-byte artifact/context
boundary, fixed role/phase `max_tokens`, conservative ledger restart, and zero
automatic retries.

The limited non-Docker selection
`clean verify -Dit.test=ResumeProcessIT` also exited `0` in 19.034 seconds,
running 67 Surefire tests plus the one selected Failsafe test. It omits the
Fullstack Docker integration suite and therefore is not a substitute for the
complete verification above.

The integration evidence includes:

- `PreviewReleaseIT`: 8 tests in 79.48 seconds. It covers deploy, blocked
  grants/hash, first failure, verified rollback, rollback failure, gateway
  create/connect/start compensation, stable-port recovery, inspect topology,
  all four container/network delete-failure points, explicit residual results,
  unrelated-container isolation, and final cleanup. A healthy replacement with
  only old-resource cleanup residue returns `CLEANUP_FAILED`; no rollback is
  claimed unless old-anchor recovery was actually attempted.
- `FullStackReplayIT`: 1 test in 21.91 seconds. It executes all eight role
  invocations through AgentScope, builds/tests the initial defect and repair in
  Docker, and finishes the replay release path with zero real-model requests.
- `FullStackDockerIT`: 2 tests in 21.43 seconds. They verify the initial version
  comparison failure, repaired pass, candidate isolation, immutable acceptance
  baseline, configuration persistence across restart, and one candidate JAR.
  The cleanup regression also kills or interrupts the Docker CLI, triggers
  stdout/stderr/dual-stream overflow and non-zero exit, then proves the exact
  randomized container is absent while a pre-existing unrelated container keeps
  the same ID and running state.
- `FullStackResumeTest`: 1 unit test. It opens an existing trusted run, rebuilds
  the complete ARCHITECT artifact/provenance from the exact four-message state
  without transport calls, and moves a USER-only FRONTEND unknown result to a
  fresh attempt session.

The fixed empty `303` login redirect now uses an explicit no-body response.
This keeps the gateway's rejection of chunked and ambiguous framing intact;
both preview and replay integration tests pass through that path. Gateway tests
also cover request/response control characters, hop-by-hop headers, duplicate
lengths, transfer encoding, absolute-form/CONNECT targets, body limits, slow
peers, and unique rebuilt response framing. Preview command tests cover silent,
stdout, stderr, dual-stream, timeout, overflow, process-tree termination, and
stream cleanup.

The targeted `DockerRunnerCommandTest,PreviewReleaseIT` command exited `0` with
16 tests. Dedicated local parent/child JVM probes cover stdout overflow, stderr
overflow, timeout, and caller interruption. Each probe records both PIDs and
asserts both are gone after the unified termination path; the interruption case
also verifies that the caller's interrupt status is restored. The production
path snapshots recursive descendants before terminating the root, uses one
bounded cleanup deadline for descendant/root waits and reader joins, and then
performs the exact daemon-container remove plus explicit not-found inspection.

Docker testing used the pinned Temurin OCI digest on macOS + Colima with
`--platform linux/arm64`. Docker commands use the trusted launcher `PATH`; only
the model transport fixes an absolute `/usr/bin/curl` path. Candidate containers
remain internal-only and the fixed gateway is the sole container publishing a
loopback port. Build/test commands generate a fresh
`agent-harness-run-<32 hex>` name and always terminate descendants/root, close
streams, run bounded exact-name `rm -f`, and require inspect to explicitly
report no such container. Preview cleanup applies the same remove-and-inspect
rule to its four owned resource types; partial cleanup cannot report deployed or
rolled back.

The authorized real attempts are deliberately incomplete and are not a success
claim. Sanitized evidence is in
`docs/evidence/fullstack-real-attempt-20261015.json`: 7 cumulative requests were
consumed, ARCHITECT completed with a verified four-message closure, FRONTEND had
two exit-28 unknown results with no artifact/tool call, and Docker side effects
were zero. The terminal partial ledger is preserved in
`docs/evidence/fullstack-real-partial-20261016.json`: 18 cumulative requests and
42359 conservatively reserved output tokens, with accepted ARCHITECT, FRONTEND,
and initial BACKEND artifacts. Execution stopped before Docker after the backend
consumer found the pre-fix shape mismatch. REAL is now permanently stopped; no
deadline, budget, or network run will be reopened.

A later fresh attempt consumed four additional requests: ARCHITECT produced one
accepted artifact, while FRONTEND reached `submit_role_output` but the pre-fix
tool rejected its outputs-only payload. The rejected payload SHA-256 was
`6597ff1395f6e71140a672f5fb584714c04c9048e4e04b2dade0a4ec364d14f8`;
no FRONTEND artifact or Docker/release side effect was created. The cumulative
task count is therefore 11 requests. That historical result remains failure
evidence and is not reconstructed into an artifact.

The trusted seed uses sanitized source facts only: the one-request preparation
probe reported one output token (evidence SHA-256
`84899ce5ac69ffc791b4c73e19346b837a3531788c32a1f63082fa39ea2ce7f7`),
and the two-request AgentScope spike reported 188 output tokens (evidence
SHA-256 `d1d8829057c51f26d22c6e65cbc94111c795effedbe60b45b8b37ce8ba5002d7`).
The final partial REAL ledger supersedes the earlier recovery seed: 18 requests
and 42359 reserved output tokens. Ledger schema v2 persisted an absolute UTC
deadline; it was not extended after the final stop decision.

The existing full verification baseline remains `77 unit + 12 actual IT + 1 real
skip`. The budget/frontend increment was checked separately without model or
Docker calls using `-Dtest=ModelBudgetTest,RoleAgentExecutorTest,ContextAssemblerTest,FullStackResumeTest test`:
32 tests passed with zero failures, errors, or skips. This incremental result is
not a replacement for a new full verification run.

The affected non-Docker build
`-DskipITs clean package` separately ran 85 unit tests with zero failures,
errors, or skips and rebuilt the shaded JAR. It intentionally did not execute
Failsafe or replace the existing full verification baseline.

The README replay command, including creation of its trusted state root, exited
`0` in 22.47 seconds. Its report recorded the fixed eight-role route,
`realModelRequests=0`, initial test `FAIL`, repaired test `PASS`, stale-evidence
rejection, release status `DEPLOYED`, and final cleanup as `DEPLOYED_CLEANED`.

After the BACKEND replay fixtures were migrated to the strict `file={path,
content}` plus `fault`/`repair` schema, the focused isolated wrapper command
`-Dtest=RoleAgentExecutorTest -Dit.test=FullStackReplayIT verify` exited `0`.
Surefire ran 13 tests and Failsafe ran the actual `FullStackReplayIT` once, with
no failure, error, or skip. The latest replay product path completed in 21.12 seconds with repaired candidate
archive `2762f376ca5c80d744568b53b29aff129aea638e8c61f34ca786a565a6260a38`
and repaired source manifest
`23a7e90e92d6e5bccca0621cad4d681e59714e29d63e8ec38c1ceb8b262cb53b`.
The candidate archive includes build-time metadata, so its SHA is scoped to that
verified run; the source manifest and four public file hashes are the stable
lineage values. The later retained preview will replace the archive SHA with its
exact screenshot candidate.
The full path was `FAIL -> TROUBLESHOOT -> repair -> PASS -> DEPLOYED_CLEANED`;
health, business probes, stale-evidence rejection, and cleanup assertions all
passed with zero real-model requests.

The replay frontend is derived from accepted partial REAL artifact
`0b6a4286c6aad8d36d2ef1a0d01ca59f0c9f6f75dcc7aea68763f7819ac8994f`.
The fixture records original and derived hashes per file. The deterministic
adapter adds a visible REPLAY banner and replaces the incompatible JSON login
with navigation to the fixed one-time-token `/login` flow. The resulting files
contain no external URL or `innerHTML`; the integration test checks the fixed
auth/CSRF/Origin and error-contract references and proves that fixture,
SafeWorkspace candidate, and `examples/fullstack-generated/` are byte-identical.

No dependency or shaded-license input changed in the fullstack implementation;
the runtime dependency graph and root/JAR license resources remain governed by
the dependency evidence above. A completed six-role REAL run, final generated
source snapshot, retained preview, and screenshot handoff are still unverified.
