# Architecture

## Agent loop

`DeterministicModel` implements the AgentScope 2.0.4 `Model` SPI. On its first
call it emits a real `ToolUseBlock` for an allowlisted annotated tool. AgentScope
executes the `Toolkit`, preserves call ID/name in a `ToolResultBlock`, and calls
the model again. The second call validates matching IDs and returns a final
`TextBlock` without another tool request. Every `ReActAgent` is closed with
try-with-resources.

## State and recovery

Application state and AgentScope state are intentionally separate:

- `AppStateStore` persists phase, revision, evidence, action keys, and tool call
  counts using a random same-directory temporary file plus atomic move.
- A bounded `tryLock` loop serializes one session across local processes. A
  damaged JSON snapshot fails closed and releases the channel/lock so a caller
  can repair and retry.
- Existing symlink components, the fixed `application` directory, state files,
  and lock files are rejected. The implementation assumes the selected state
  directory and checkout are trusted against a privileged concurrent attacker;
  it makes no distributed-lock or filesystem-race guarantee.
- AgentScope `JsonFileAgentStateStore` persists framework context separately.
  Before constructing that store and immediately before and after every agent
  call, the fixed AgentScope directory chain and all `agent_state`, legacy
  memory/toolkit, hash, and SDK `.tmp` leaves are checked with no link following.
  RuntimeContext attributes are not treated as durable application state.

Within one AgentScope agent, the same `(userId, sessionId)` is used serially.
Role sessions are distinct. Toolkit tool objects may be shared by AgentScope;
the diagnostic tool is protected by the application session lock and the
workflow store synchronizes packet/artifact access.

## Role handoff

Each role receives a `TaskPacket` containing business/project/session identity,
input revision, source/target role, predecessor artifact IDs, evidence IDs,
unknowns, allowed actions, and requirement status. The model can pass only the
packet ID to `produce_role_artifact`. The tool reloads the packet and predecessor
artifacts from the local `WorkflowStore`, then rejects role, revision, ID, or
evidence mismatches.

Requirements freeze only when `unknowns` is empty. Development loads the frozen
requirement and emits a teaching JSON change. Testing loads that requirement and
change and compares the public fixture. A failure artifact names its return
target, test case, expected value, actual value, and lineage; the coordinator
routes from that artifact rather than from a preset pass/fail flag.

## A2A adapter

`A2aServer` uses JDK `HttpServer`; `A2aClient` uses JDK `HttpClient`. Jackson
encodes fixed records. The surface is:

- `GET /.well-known/agent-card.json`
- `POST /message:send`
- `GET /tasks/{id}`
- `A2A-Version: 1.0`
- `application/a2a+json`

The server can return a direct Message or a completed Task with an Artifact.
Direct Message responses cannot inject a task ID when the request had none.
Reply-only requests carrying a task ID first prove that Task exists and that its
context/business mapping matches. Get Task validates the response task,
context, and business IDs against the request.
Unknown task reconciliation performs only Get Task and returns
`UNKNOWN_RECONCILIATION_REQUIRED`; it never resends a Message. The adapter is a
documented semantic subset and does not claim the entire A2A protocol or SDK
interoperability.

Both directions stream in 8 KiB chunks and stop at 64 KiB. The client applies
one overall response deadline. The server closes slow request bodies after one
second, uses four workers plus an eight-entry queue, and caps its in-memory
Task/messageId sets at 64/128. A full state set returns
`SERVER_CAPACITY_REACHED`; an exchange beyond executor capacity is closed.

## Fullstack build and preview

`RoleAgentExecutor` gives every role invocation an independent hash-derived
AgentScope session. A complete REAL state must be exactly USER → tool use →
matching tool result → final text. Recovery reruns the current output schema,
recomputes artifact and state hashes, and makes no model call. A USER-only
unknown-result state is retained while the next attempt uses a new derived
session. `FullStackCheckpointStore` atomically records only run binding,
lineage, hashes, usage metadata, and the next invocation.

`DockerRunner` first inspects the exact local Temurin digest, then uses
`--pull=never`, fixed entrypoints, read-only source/base/candidate mounts, and
separate writable output/data. `PreviewReleaseAdapter` starts the candidate on
its own internal network and a fixed sidecar gateway on internal plus publish
networks. The gateway has no mounts, environment secrets, or dynamic upstream;
it validates bounded HTTP/1 requests and responses before rebuilding the
downstream response with one length and a closed connection.

Replacement is a compensating transaction. The new candidate first passes on a
shadow loopback port. Stable-port activation may remove/recreate gateways, but
any failure cleans new resources, restores the old gateway at the stable port,
and repeats health and business checks. Only that verified path is
`ROLLED_BACK`; failed recovery is `ROLLBACK_FAILED`.

Build/test Docker commands assign a new unpredictable exact container name for
every execution. CLI completion, timeout, output overflow, interruption, and
non-zero exit all converge on bounded exact-name removal plus absence inspection.
Preview cleanup applies the same execute-then-inspect rule independently to its
gateway, candidate, internal network, and publish network. Partial cleanup stays
represented in adapter memory and produces `CLEANUP_FAILED` or
`ROLLBACK_FAILED`; it cannot be reported as a deployed or recovered state.
After a healthy replacement, residual old resources produce `CLEANUP_FAILED`
because no rollback was attempted. `ROLLBACK_FAILED` is reserved for an actual
old-anchor restoration whose restore or verification fails.
