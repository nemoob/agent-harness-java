# Third-party notices

The executable JAR shades the runtime dependency graph recorded in
`docs/dependency-tree.txt`. Versions below were resolved from Maven Central with
the repository-local settings in `.mvn/`.

## Apache License 2.0

The Apache License 2.0 text is provided in `LICENSE`. The following resolved
artifacts declare Apache License 2.0, directly or through their published parent
POM:

- `io.agentscope:agentscope-core:2.0.4`
- `io.projectreactor:reactor-core:3.8.2`
- `org.jspecify:jspecify:1.0.0`
- `com.fasterxml.jackson.core:jackson-annotations:2.21`
- `com.fasterxml.jackson.core:jackson-core:2.21.7`
- `com.fasterxml.jackson.core:jackson-databind:2.21.7`
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.7`
- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.7`
- `com.fasterxml:classmate:1.7.0`
- `com.github.victools:jsonschema-generator:4.38.0`
- `com.github.victools:jsonschema-module-jackson:4.38.0`
- `com.squareup.okhttp3:okhttp:5.3.2`
- `com.squareup.okhttp3:okhttp-jvm:5.3.2`
- `com.squareup.okio:okio:3.16.4`
- `com.squareup.okio:okio-jvm:3.16.4`
- `org.jetbrains.kotlin:kotlin-stdlib:2.2.21`
- `org.jetbrains:annotations:13.0`
- `com.networknt:json-schema-validator:2.0.0`
- `com.ethlo.time:itu:1.14.0`
- `org.yaml:snakeyaml:2.6`
- `io.opentelemetry:opentelemetry-api:1.61.0`
- `io.opentelemetry:opentelemetry-context:1.61.0`
- `io.opentelemetry:opentelemetry-common:1.61.0`
- `io.opentelemetry:opentelemetry-api-incubator:1.61.0-alpha`
- `io.opentelemetry.instrumentation:opentelemetry-reactor-3.1:2.27.0-alpha`
- `io.opentelemetry.instrumentation:opentelemetry-instrumentation-api:2.27.0`
- `io.opentelemetry.instrumentation:opentelemetry-instrumentation-api-incubator:2.27.0-alpha`
- `io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations-support:2.27.0-alpha`
- `io.opentelemetry.semconv:opentelemetry-semconv:1.40.0`
- `io.opentelemetry.semconv:opentelemetry-semconv-incubating:1.40.0-alpha`
- `org.apache.commons:commons-compress:1.27.1`
- `commons-codec:commons-codec:1.17.1`
- `commons-io:commons-io:2.16.1`
- `org.apache.commons:commons-lang3:3.16.0`
- `org.xerial:sqlite-jdbc:3.47.1.0`

Published NOTICE material retained here for the shaded distribution:

- Jackson JSON processor, copyright 2007–, Tatu Saloranta. Jackson is developed
  by its community of contributors.
- Java ClassMate was originally written by Tatu Saloranta; Brian Langel also
  contributed. Copyright 2007–, Tatu Saloranta.
- Apache Commons Codec 1.17.1: copyright 2002–2024 The Apache Software
  Foundation; includes software developed at the Apache Software Foundation.
- Apache Commons IO 2.16.1: copyright 2002–2024 The Apache Software Foundation;
  includes software developed at the Apache Software Foundation.
- Apache Commons Lang 3.16.0: copyright 2001–2024 The Apache Software
  Foundation; includes software developed at the Apache Software Foundation.

Apache Commons Compress 1.27.1 publishes this NOTICE:

```text
Apache Commons Compress
Copyright 2002-2024 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## MIT and MIT-0

- `org.slf4j:slf4j-api:2.0.17`: MIT License, copyright 2004–2022 QOS.ch Sarl.
- `org.reactivestreams:reactive-streams:1.0.4`: MIT-0 License.

### MCP Java SDK 0.17.2

Applies to `io.modelcontextprotocol.sdk:mcp`, `mcp-core`, `mcp-json`, and
`mcp-json-jackson2` version 0.17.2. Copied verbatim from the fixed upstream
`v0.17.2` LICENSE at
`https://github.com/modelcontextprotocol/java-sdk/blob/v0.17.2/LICENSE`:

```text
MIT License

Copyright (c) 2025 the original author or authors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### tree-sitter-ng 0.24.4

Applies to `io.github.bonede:tree-sitter:0.24.4` and the resolved language
binding `io.github.bonede:tree-sitter-bash:0.23.3`. Copied verbatim from the
fixed upstream `tree-sitter-ng` `v0.24.4` LICENSE at
`https://github.com/bonede/tree-sitter-ng/blob/v0.24.4/LICENSE`:

```text
The MIT License (MIT)

Copyright (c) 2018-2023 Wang Liang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

MIT-0 applies the same permission without a notice-retention condition to
`org.reactivestreams:reactive-streams:1.0.4`.

## Code bundled by Jackson Core

`jackson-core:2.21.7` publishes these additional notices for code embedded in
FastDoubleParser:

- FastDoubleParser, copyright 2024 Werner Randelshofer: MIT License.
- `fast_double_parser`, copyright 2022 Daniel Lemire: Boost Software License.
- `fast_float`, copyright 2021 the fast_float authors: MIT License.
- `bigint` float-FFT portions, copyright 2020 Tim Buktu: BSD 2-Clause License.
- Schubfach number writing code, copyright 2018–2020 Raffaello Giulietti: MIT
  License.

The original license and notice resources remain inside the resolved component
JARs in the repository-local Maven cache. This file records the notices needed
when those components are redistributed in the shaded executable JAR.

## Version-specific security conditions

These components remain because AgentScope 2.0.4 brings them transitively and
the first release does not exercise the affected paths. This is a scoped
reachability statement, not a claim that the versions are vulnerability-free:

- `io.modelcontextprotocol.sdk:mcp-core:0.17.2`: CVE-2026-35568 and
  CVE-2026-34237. The first-release source has no MCP server, CORS, or related
  configuration entry point, so no trigger was found in the current path.
  Before enabling MCP, upgrade to at least 1.0.1; excluding the component is
  allowed only after AgentScope 2.0.4 binary compatibility is demonstrated and
  the full suite passes.
- `io.opentelemetry:opentelemetry-api:1.61.0`: CVE-2026-45292. The first release
  does not accept or propagate W3C baggage, so no trigger was found in the
  current path. Upgrade to at least 1.62.0 before enabling that propagation.
- `org.apache.commons:commons-lang3:3.16.0`: CVE-2025-48924. Project source does
  not call Commons Lang and no recursive trigger path was found. Upgrade to at
  least 3.18.0 before using the affected APIs.

## Build wrapper

`mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` were generated
from `org.apache.maven.wrapper:maven-wrapper-distribution:3.3.4:only-script`.
Apache Maven Wrapper is distributed under Apache License 2.0. The scripts pin
Apache Maven 3.9.11 and verify its ZIP with the SHA-256 recorded in the wrapper
properties. The wrapper scripts are source-distribution tooling and are not
embedded in the executable JAR.
