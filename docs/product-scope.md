# Product scope v0.1

## Goal

Provide a cloneable Java 17 example that teaches five observable journeys with
deterministic public fixtures and one executable JAR:

1. A real AgentScope `ReActAgent` executes model → `ToolUseBlock` → `Toolkit` →
   matching `ToolResultBlock` → final `TextBlock`.
2. Missing request evidence produces `INSUFFICIENT_EVIDENCE` without inventing
   a fact.
3. Two independent Java processes resume one application session without
   executing a completed tool twice.
4. Diagnosis, requirements, development, and testing agents exchange validated
   packets and artifact lineage. An unfrozen requirement stops the flow, while
   a fixture mismatch returns to its derived owner.
5. A loopback HTTP client and server exchange an Agent Card, Message, Task, and
   Artifact using the documented A2A v1.0 semantic subset.

## Acceptance boundary

- Java 17, `io.agentscope:agentscope-core:2.0.4`, one Maven project, one shaded
  executable JAR.
- No paid model, credential, production data, business code generation, remote
  write, shell construction, Docker daemon, or host service.
- The development role produces only a teaching JSON artifact. The testing role
  evaluates that artifact against a fixed public fixture.
- State records session, revision, phase, facts/evidence, pending/completed
  actions, and call counts. AgentScope internal context uses its own
  `JsonFileAgentStateStore`.

## Deferred

Nacos 3.2.3, a real Nacos server, `AiFactory`/`A2aService`, remote LLMs, Docker,
full A2A authentication, streaming, push, JSON-RPC, gRPC, and SDK
interoperability certification are P1 or later. No compatibility claim is made
for `a2a-java:1.2.0.Final`.
