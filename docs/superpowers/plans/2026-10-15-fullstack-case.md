# Fullstack Case Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java 17 fullstack teaching flow in which six real AgentScope agents generate, test, diagnose, repair, and safely preview a candidate application in replay or bounded real-model mode.

**Architecture:** A deterministic coordinator owns lineage, grants, hashes, budgets, Docker commands, test evidence, and release decisions. Role-specific AgentScope loops can only submit schema-checked artifacts through one tool; generated files enter an atomic bounded workspace and fixed scaffold, then a constrained Docker runner and release gate produce the five required outcomes.

**Tech Stack:** Java 17, AgentScope core 2.0.4, Jackson 2.21.7, JDK HTTP server/client, JUnit 5, Maven 3.9.11 Wrapper, `/usr/bin/curl`, Docker CLI with a digest-pinned Temurin image.

**Spec:** `docs/fullstack-case.md`

## Global Constraints

- Keep `io.agentscope:agentscope-core:2.0.4`, Java 17, Jackson BOM 2.21.7, Apache-2.0, and the existing isolated Maven settings.
- Do not add an Anthropic provider dependency; real transport is fixed `/usr/bin/curl --disable --config -` with HTTPS only, no redirect, 10-second connect timeout, 120-second total timeout, a 125-second Java process deadline, capped response, stdin-only secret/config/body, and sanitized errors.
- Limit each real run to 24 model requests, `max_tokens=4096`, output usage at most 60000, and a 30-minute deadline; keep the external preparation probe separate from product-run accounting.
- Use six independent AgentScope sessions and the fixed eight invocation route; only `submit_role_output` is available to role models.
- Keep security-critical HTTP/auth/JSON/store/runner/release code in fixed scaffold or harness code; model output is restricted to the three frontend files and generated version-policy source.
- Use the multi-platform OCI image index `mirror.gcr.io/library/eclipse-temurin@sha256:420933bf319e6aff4bec22eff8d2a31c824df5d853432e3dc0f46d6ea8dd5919`; the verified local platform is arm64. Apply the exact resource/security limits from the spec.
- Never execute generated code on the host, never use shell composition for model or Docker commands, and never log secrets, complete prompts, endpoints, tokens, or absolute host paths.
- Replace every commit step with a SHA-256 checkpoint and its targeted test result; perform no Git write.

## Review Focus

- A valid grant whose artifact hash or base revision changed after issuance must fail before workspace or Docker side effects; Task 2 tests both mutations.
- A complete-looking generated file set with one oversized or linked destination must reject atomically without modifying any prior file; Task 2 tests the batch invariant.
- A model response with missing usage, an unexpected block, malformed tool JSON, or budget exhaustion must fail closed without retrying except the three allowed transient classes; Task 1 tests each branch.
- A test PASS for candidate A must never authorize candidate B, even when paths and role lineage are otherwise identical; Tasks 5 and 6 test candidate hash binding.
- A failed first preview without a rollback anchor must stay `DEPLOY_FAILED`, while replacement failure may become `ROLLED_BACK` only after restored health and business checks; Task 6 tests both states.

---

### Task 1: Provider, Budget, and Role Agent Loop

**Files:**
- Create: `src/main/java/dev/agentharness/fullstack/FullStackRole.java`
- Create: `src/main/java/dev/agentharness/fullstack/ModelMode.java`
- Create: `src/main/java/dev/agentharness/fullstack/ModelBudget.java`
- Create: `src/main/java/dev/agentharness/fullstack/ModelUsage.java`
- Create: `src/main/java/dev/agentharness/fullstack/ModelTransport.java`
- Create: `src/main/java/dev/agentharness/fullstack/AnthropicCurlTransport.java`
- Create: `src/main/java/dev/agentharness/fullstack/AnthropicRoleModel.java`
- Create: `src/main/java/dev/agentharness/fullstack/ReplayRoleModel.java`
- Create: `src/main/java/dev/agentharness/fullstack/RoleAgentExecutor.java`
- Create: `src/main/java/dev/agentharness/fullstack/RoleOutputTool.java`
- Create: `src/main/java/dev/agentharness/fullstack/FullStackTaskPacket.java`
- Create: `src/main/java/dev/agentharness/fullstack/FullStackArtifact.java`
- Create: `src/main/java/dev/agentharness/fullstack/Provenance.java`
- Test: `src/test/java/dev/agentharness/fullstack/ModelBudgetTest.java`
- Test: `src/test/java/dev/agentharness/fullstack/RoleAgentExecutorTest.java`

**Interfaces:**
- Consumes: AgentScope `Model`, `ReActAgent`, `Toolkit`, `ToolUseBlock`, and `ToolResultBlock` contracts.
- Produces: `ModelBudget.beforeRequest(int)`, `ModelBudget.record(ModelUsage)`, `ModelTransport.complete(RolePrompt)`, and `RoleAgentExecutor.execute(FullStackTaskPacket, ModelMode)` returning `FullStackArtifact` plus provenance.

- [ ] **Step 1: Write failing `ModelBudgetTest` cases** for the 24-request product-run cap, 4096 max tokens, 60000 output cap, 30-minute deadline, pre-call rejection, and retry accounting.
- [ ] **Step 2: Run** `./mvnw ... -Dtest=ModelBudgetTest test`; expect compilation/test failure because the budget types do not exist.
- [ ] **Step 3: Implement the budget and sanitized transport boundary** with fixed argv `/usr/bin/curl --disable --config -`, stdin config, HTTPS validation, capped output, and retry only for a proven pre-connect failure.
- [ ] **Step 4: Write failing `RoleAgentExecutorTest` cases** for six distinct session IDs, real/replay labels, fixed tool allowlist, lineage, malformed output, usage, and close behavior.
- [ ] **Step 5: Implement replay and real role models plus `submit_role_output`** so each invocation performs tool-use then final-text and persists only hashes/usage.
- [ ] **Step 6: Run both Task 1 tests** and expect PASS with no network use in default tests.
- [ ] **Step 7: Run one ARCHITECT real spike** only after transport safety tests pass; cap it at two requests and record sanitized usage/event evidence.
- [ ] **Step 8: Record checkpoint** by hashing Task 1 files and saving the targeted command/exit in the implementation log; do not stage or commit.

### Task 2: Capability Grants and Atomic Safe Workspace

**Files:**
- Create: `src/main/java/dev/agentharness/fullstack/CapabilityGrant.java`
- Create: `src/main/java/dev/agentharness/fullstack/CapabilityGrantRegistry.java`
- Create: `src/main/java/dev/agentharness/fullstack/SafeWorkspace.java`
- Create: `src/main/java/dev/agentharness/fullstack/WorkspacePatch.java`
- Create: `src/main/java/dev/agentharness/fullstack/WorkspaceManifest.java`
- Test: `src/test/java/dev/agentharness/fullstack/CapabilityGrantRegistryTest.java`
- Test: `src/test/java/dev/agentharness/fullstack/SafeWorkspaceTest.java`

**Interfaces:**
- Consumes: `FullStackTaskPacket.capabilityGrantRef`, role, tool, project, workspace, base revision, target path, input hash, and current time.
- Produces: `CapabilityGrantRegistry.authorize(GrantUse)` and `SafeWorkspace.apply(WorkspacePatch)` with an immutable SHA-256 manifest.

- [ ] **Step 1: Write grant failure tests** for forged, revoked, expired, cross-project/role/workspace/base/tool/path/target/hash/argument use.
- [ ] **Step 2: Run the grant test** and confirm it fails before implementation.
- [ ] **Step 3: Implement server-side immutable grants and per-call revalidation** without trusting packet capability fields.
- [ ] **Step 4: Write workspace failure tests** for leaf/intermediate links, outside paths, boundary replacement, 65 files, 256 KiB+1 file, 1 MiB+1 patch, and atomic no-partial-write.
- [ ] **Step 5: Implement staged same-root writes** with complete validation before atomic promotion and deterministic diff/manifest hashes.
- [ ] **Step 6: Run both Task 2 tests** and expect PASS.
- [ ] **Step 7: Record Task 2 checkpoint hashes and targeted test exit** without Git writes.

### Task 3: Fixed Generated Application Scaffold

**Files:**
- Create: `src/main/resources/fullstack/scaffold/ConfigHttpServer.java`
- Create: `src/main/resources/fullstack/scaffold/FeatureStore.java`
- Create: `src/main/resources/fullstack/scaffold/VersionPolicy.java`
- Create: `src/main/resources/fullstack/scaffold/GeneratedAppChecks.java`
- Create: `src/main/resources/fullstack/scaffold/manifest.txt`
- Create: `src/test/java/dev/agentharness/fullstack/GeneratedAppContractTest.java`

**Interfaces:**
- Consumes: generated `GeneratedVersionPolicy.java`, frontend files, a 0600 bootstrap-token file, and fixed runtime arguments.
- Produces: one Java 17 HTTP application with the four fixed routes, cookie/CSRF/origin checks, bounded bodies/responses, and self-check entrypoints.

- [ ] **Step 1: Write failing scaffold contract tests** for GET/PUT/preview/health, 409 revision, bootstrap exchange, one-time token, cookie flags, CSRF, exact Origin, 16 KiB body, version grammar, and static-page `innerHTML` rejection.
- [ ] **Step 2: Run the contract test** and confirm missing resources fail.
- [ ] **Step 3: Implement the smallest standard-library scaffold** with no dynamic deserialization or model-controlled auth/network behavior.
- [ ] **Step 4: Run `GeneratedAppContractTest`** and expect PASS.
- [ ] **Step 5: Record Task 3 checkpoint hash and test exit** without Git writes.

### Task 4: Digest-Pinned Docker Build and Test Runner

**Files:**
- Create: `src/main/java/dev/agentharness/fullstack/DockerRunner.java`
- Create: `src/main/java/dev/agentharness/fullstack/DockerCommand.java`
- Create: `src/main/java/dev/agentharness/fullstack/RunnerResult.java`
- Test: `src/test/java/dev/agentharness/fullstack/DockerRunnerCommandTest.java`
- Test: `src/test/java/dev/agentharness/fullstack/FullStackDockerIT.java`

**Interfaces:**
- Consumes: candidate workspace/manifest, fixed base JAR, fixed digest, and runner purpose `BUILD_TEST|PREVIEW`.
- Produces: bounded stdout/stderr, exit code, actual checks, candidate JAR hash, and side-effect count.

- [ ] **Step 1: Write command tests** asserting array arguments, fixed digest, non-root, network none for build, read-only, cap-drop, no-new-privileges, one writable mount, 1 CPU/512 MiB/64 PID/120 seconds, no socket/secret/control mount, and no shell.
- [ ] **Step 2: Run command tests** and confirm failure.
- [ ] **Step 3: Implement fixed ProcessBuilder execution** with concurrency 2/queue 8 and independent 1 MiB stream caps that terminate overflow.
- [ ] **Step 4: Write Docker IT** that compiles/packages one candidate JAR, proves string comparison FAIL, numeric repair PASS, starts loopback in-container, and checks API/static files.
- [ ] **Step 5: Run Docker command test and `FullStackDockerIT`** against the prevalidated local image; stop on daemon/image drift without pulling or changing context.
- [ ] **Step 6: Record Task 4 checkpoint, image inspect, commands, exits, and candidate hash** without Git writes.

### Task 5: Eight-Invocation Coordinator, Replay, and CLI

**Files:**
- Create: `src/main/java/dev/agentharness/fullstack/FullStackRequest.java`
- Create: `src/main/java/dev/agentharness/fullstack/FullStackReport.java`
- Create: `src/main/java/dev/agentharness/fullstack/FullStackService.java`
- Create: `src/main/java/dev/agentharness/fullstack/FullStackStateStore.java`
- Create: `src/main/resources/fullstack/replay/role-outputs.json`
- Create: `src/test/java/dev/agentharness/fullstack/FullStackReplayIT.java`
- Modify: `src/main/java/dev/agentharness/Main.java`

**Interfaces:**
- Consumes: request, requirement text/hash, registry, role executor, safe workspace, runner, and release adapter.
- Produces: fixed eight-role route, artifact lineage/revisions, model mode/request usage, failure/repair hashes, test evidence, and final release outcome.

- [ ] **Step 1: Write replay integration test** for all eight invocations, six independent sessions, artifact lineage, revision increments, replay label/zero real requests, model-origin frontend/backend, initial FAIL, troubleshoot cause, repair hash, PASS, and stale evidence rejection.
- [ ] **Step 2: Run replay IT** and confirm missing service/CLI fails.
- [ ] **Step 3: Implement deterministic service transitions and file-backed resumable summaries** while keeping secret/prompt material out of state.
- [ ] **Step 4: Add the `fullstack` CLI parser** with exact options, trusted requirement file, ASCII session, loopback port, and stable missing-real-config failure.
- [ ] **Step 5: Run replay IT and CLI negative tests** and expect PASS with no remote model call.
- [ ] **Step 6: Record Task 5 checkpoint and test exit** without Git writes.

### Task 6: Preview Release Gate and Five Outcomes

**Files:**
- Create: `src/main/java/dev/agentharness/fullstack/ReleaseGate.java`
- Create: `src/main/java/dev/agentharness/fullstack/ReleaseDecision.java`
- Create: `src/main/java/dev/agentharness/fullstack/PreviewReleaseAdapter.java`
- Create: `src/test/java/dev/agentharness/fullstack/PreviewReleaseIT.java`

**Interfaces:**
- Consumes: deploy grant, exact candidate/test hashes, target, preview port, stable manifest anchor, deterministic health/business probes, and fault switch.
- Produces: `DEPLOYED`, `DEPLOY_BLOCKED`, `ROLLED_BACK`, `ROLLBACK_FAILED`, or `DEPLOY_FAILED` with bounded side-effect evidence.

- [ ] **Step 1: Write five-path release tests** for success, absent grant/no side effects, hash mismatch/no side effects, replacement health failure with verified rollback, and first failure without rollback claim.
- [ ] **Step 2: Run preview release tests** and confirm missing implementation fails.
- [ ] **Step 3: Implement pre-side-effect gate checks and preview adapter** using an internal Docker network and only `127.0.0.1:<port>:8080`, with build-equivalent security/resource flags.
- [ ] **Step 4: Run `PreviewReleaseIT`** and expect all five outcomes and side-effect assertions to pass.
- [ ] **Step 5: Record Task 6 checkpoint, container/network IDs in sanitized form, and test exit** without Git writes.

### Task 7: Bounded Real Fullstack Run and Evidence

**Files:**
- Create: `docs/evidence/fullstack-real-run.json`
- Create: `docs/evidence/fullstack-real-events.jsonl`
- Create: `docs/evidence/fullstack-real-manifest.txt`
- Test: `src/test/java/dev/agentharness/fullstack/FullStackEvidenceTest.java`

**Interfaces:**
- Consumes: all earlier components plus the three trusted Anthropic environment variables and the remaining request budget.
- Produces: sanitized real-run summary, events, usage, six-role invocation proof, generated artifact hashes, runner/release outcomes, run directory, and preview handoff data.

- [ ] **Step 1: Write evidence schema/redaction tests** requiring `REAL`, at least one invocation for all six roles, request/output/wall bounds, model-origin frontend and both backend versions, initial FAIL/diagnosis/repair/PASS, no key/token/endpoint/absolute local path/full prompt.
- [ ] **Step 2: Run the evidence test** and confirm missing evidence fails.
- [ ] **Step 3: Execute one real eight-invocation chain**; stop and classify provider/runner failures rather than falling back to replay.
- [ ] **Step 4: Persist only sanitized evidence and hashes**, then run `FullStackEvidenceTest`.
- [ ] **Step 5: Verify preview over real loopback HTTP** without printing the 0600 bootstrap token; keep the successful preview container for the entry screenshot.
- [ ] **Step 6: Record Task 7 checkpoint, real requests/output/wall, candidate source/JAR hashes, raw report paths, run directory, URL, and token-file path** without Git writes.

### Task 8: Public Documentation and Final Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/product-scope.md`
- Modify: `docs/architecture.md`
- Modify: `docs/security.md`
- Modify: `docs/verification.md`
- Modify only if dependency graph changes: `pom.xml`, `docs/dependency-tree.txt`, `NOTICE`, `THIRD_PARTY_NOTICES.md`

**Interfaces:**
- Consumes: actual build/test/runner/real-run evidence and final public files.
- Produces: runnable commands, scaffold/generated boundary, five-path evidence, security limits, dependency attribution, final manifest/JAR hashes, and screenshot handoff.

- [ ] **Step 1: Update Chinese-first README and scope/architecture/security docs** with exact real/replay commands, generated/scaffold boundary, Docker constraints, token-file handling, and limitations.
- [ ] **Step 2: Regenerate dependency tree only if needed** and verify license/NOTICE alignment; do not change dependencies speculatively.
- [ ] **Step 3: Run isolated `clean verify`** and require all Surefire/Failsafe reports green with no remote calls in the default build.
- [ ] **Step 4: Run replay Docker five-path evidence and the one authorized real chain**, preserving the successful preview for screenshot handoff.
- [ ] **Step 5: Inspect the final JAR resources and generated candidate JAR**, run secret/path scans and `git diff --check`, and compute the public file manifest/JAR SHA-256.
- [ ] **Step 6: Record final checkpoint** with commands/exits, image digest/inspect, model request/output/wall counts, role provenance, defect/repair hashes, release side effects, loopback URL, run/token paths, HEAD/status, and unknowns; perform no Git write.
