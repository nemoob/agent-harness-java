# Security boundary

This document records the v0.1 source review boundary. It is not a security
certification.

## Local runtime controls

- State paths are limited to the checkout. Existing path components,
  `application`, `agentscope`, the fixed AgentScope user, and the session
  directory are opened or created one level at a time without following
  symbolic links and are checked against their real parent path before the
  framework state store is constructed.
- The A2A server binds only `127.0.0.1`. Request and response bodies are streamed
  in 8 KiB chunks, capped at 64 KiB, and closed at explicit deadlines. The
  server uses four workers and an eight-entry queue; local task and message ID
  state is capped at 64 and 128 entries. Capacity exhaustion returns a stable
  service error or closes an exchange rejected by the bounded executor.
- Media types are compared exactly after parameters are removed. JSON duplicate
  fields, invalid unions, and task/context/business/artifact mapping conflicts
  are rejected. The client does not redirect or retry an unknown result.

## Transitive dependency conditions

AgentScope 2.0.4 brings the following versions transitively. The current
first-release paths were inspected for reachability; this does not mean the
versions passed a general vulnerability review.

- `mcp-core:0.17.2`: CVE-2026-35568 and CVE-2026-34237. No MCP server, CORS, or
  related configuration entry point exists in v0.1, so no current trigger was
  found. Before enabling MCP, upgrade to at least 1.0.1. Exclusion requires
  demonstrated AgentScope 2.0.4 binary compatibility and a full test pass.
- `opentelemetry-api:1.61.0`: CVE-2026-45292. v0.1 does not accept or propagate
  W3C baggage, so no current trigger was found. Upgrade to at least 1.62.0
  before enabling that propagation.
- `commons-lang3:3.16.0`: CVE-2025-48924. Project source makes no Commons Lang
  call and no recursive trigger path was found. Upgrade to at least 3.18.0
  before using the affected APIs.

Jackson core, databind, JSR-310, and YAML are managed by the Jackson 2.21.7 BOM.
Annotations remains at the upstream 2.21 release because that module uses the
major.minor version line.
