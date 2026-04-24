# tl-mcp-server

Maven-plugin [Model Context Protocol](https://modelcontextprotocol.io/) server that
exposes the Java type graph of a Maven project (classes, members, annotations,
references, call graph) to AI agents such as Claude Code.

It is packaged as a Maven plugin: you invoke it from a target project, and Maven hands
the plugin the compile classpath of that project's reactor. The plugin scans every
`.class` file with ASM and serves queries over stdio or HTTP.

## Requirements

- JDK 17+
- Maven 3.6+

## Install the plugin

Once, in this repo, so the artifact lands in your local Maven repository:

```bash
mvn install
```

This publishes `com.top-logic:tl-mcp-server:0.1.0-SNAPSHOT` to `~/.m2/repository`.

## Run against another project

The plugin runs **inside the target project**, not inside this repo. `cd` into any
Maven project (single-module or multi-module aggregator) and invoke:

```bash
mvn -q com.top-logic:tl-mcp-server:0.1.0-SNAPSHOT:serve
```

Maven resolves the reactor's compile classpath and passes it to the plugin, which
indexes every class on it and starts the MCP server. With `-q` Maven keeps stdout
clean for the JSON-RPC stream.

### Options

| Flag | Default | Effect |
|------|---------|--------|
| `-Dtl-mcp.port=<n>` | `0` (stdio) | Switch to HTTP transport on port `<n>`. |
| `-Dtl-mcp.host=<host>` | `127.0.0.1` | Bind address for HTTP transport. |
| `-Dtl-mcp.path=<path>` | `/mcp` | MCP endpoint path for HTTP transport. |
| `-Dtl-mcp.bodies=<bool>` | `true` | Scan method bodies to populate the call graph and field-access index. Turning it off cuts startup time by ~2–3× but disables `callers_of` / `field_accessors`. |
| `-Dtl-mcp.jdk=<bool>` | `false` | Also index the JDK system modules (`jrt:/`). Adds tens of thousands of types and a few seconds to startup. |

### Transports

- **stdio** (default): the MCP client launches `mvn ...:serve` as a child process and
  talks JSON-RPC over its stdin/stdout.
- **HTTP** (`-Dtl-mcp.port=...`): embedded Jetty serves the MCP streamable-HTTP
  protocol. Clients POST JSON-RPC requests to `http://<host>:<port><path>` with
  `Accept: application/json, text/event-stream`. The server replies with a session id
  in the `Mcp-Session-Id` response header; subsequent requests echo it back via
  `mcp-session-id`.

## Using from Claude Code

Add an entry to an `.mcp.json` file (project-local or shared via a plugin). For stdio,
point `command` / `args` at a `mvn ...:serve` invocation in the *target* project's
working directory:

```json
{
  "mcpServers": {
    "tl-mcp": {
      "command": "mvn",
      "args": [
        "-q",
        "-f", "/absolute/path/to/target-project/pom.xml",
        "com.top-logic:tl-mcp-server:0.1.0-SNAPSHOT:serve"
      ]
    }
  }
}
```

For HTTP, start the server once in a terminal (`mvn -q ...:serve -Dtl-mcp.port=18765`)
and configure the client to reach it:

```json
{
  "mcpServers": {
    "tl-mcp": {
      "url": "http://127.0.0.1:18765/mcp"
    }
  }
}
```

Startup takes a few seconds while Maven resolves the reactor and ASM scans every
class. The server blocks afterwards until the client disconnects.

## Tools exposed

| Tool | Purpose |
|------|---------|
| `query_types` | Composite type filter: by name/FQN pattern, subtype-of, supertype-of, annotation, module, kind, AND-combined. |
| `describe_type` | Single-type metadata: modifiers, superclass + interfaces, enclosing outer class, module, source file, annotations with parsed parameter values, method/field counts. |
| `list_members` | All declared methods and fields of a type: signatures, modifiers, annotations, field constant values (no inherited members — query each supertype separately). |
| `references_to` | Types that reference the given FQN: from superclass/interfaces, annotations, member signatures, and (with body scan on) method bodies. |
| `callers_of` | Call sites that invoke a method. Requires body scan. Keyed by bytecode declaring class — a call dispatched through a supertype appears under the supertype, not the runtime subclass. |
| `field_accessors` | Methods that read and/or write a field. Requires body scan. |
| `module_of` | Maven module id (or JAR GAV / filename) that supplied a class. |

## Notes

- The scanner is bytecode-only. Generics are erased (parameters and return types are
  their raw forms). Source-level information beyond the `SourceFile` attribute (e.g.
  local variable names, line numbers, JavaDoc) is not indexed.
- Config/implementation pairing (TopLogic convention) is rebuilt from constructor
  signatures: if a class exposes a constructor `(InstantiationContext, X)`, `X` is
  recorded as its configuration type and the reverse link is stamped onto `X`. Works
  transparently on non-TopLogic projects — both links stay `null`.
- For multi-module projects the `serve` goal runs as an aggregator: invoking it from
  the root POM indexes every reactor module plus the union of their compile
  dependencies.
