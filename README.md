# tl-mcp-server

Maven-plugin [Model Context Protocol](https://modelcontextprotocol.io/) server that
exposes the Java type graph of a Maven project — classes, members, annotations,
references, call graph, source snippets — to AI agents such as Claude Code.

It is packaged as a Maven plugin: you invoke it from a target project, and Maven
hands the plugin the compile classpath of that project's reactor. The plugin scans
every `.class` file with ASM, optionally reads the companion `-sources.jar`, and
serves queries over stdio or HTTP.

## Requirements

- JDK 17+
- Maven 3.6+

## Install the plugin

Release builds are published to the TopLogic Nexus
(<https://dev.top-logic.com/nexus/repository/toplogic/>). Users with that
repository configured as a mirror or plugin repository in their
`~/.m2/settings.xml` can invoke the `serve` goal directly — Maven fetches the
plugin on first use.

For local development (or if the Nexus is not reachable), install the snapshot
into your local repository:

```bash
mvn install
```

This publishes `com.top-logic:tl-mcp-server:<version>-SNAPSHOT` to `~/.m2/repository`.

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
| `-Dtl-mcp.bodies=<bool>` | `true` | Scan method bodies to populate the call graph, field-access index, and body-level reference kinds (`instantiation`, `cast`, `instanceof`, `catch`, `call_dispatch`, `field_read`, `field_write`). Turning it off cuts startup time by ~2–3× but disables `callers_of`, `field_accessors`, and body kinds in `references_to`. |
| `-Dtl-mcp.jdk=<bool>` | `false` | Also index the JDK system modules (`jrt:/`). Adds tens of thousands of types and a few seconds to startup. |

### Transports

- **stdio** (default): the MCP client launches `mvn ...:serve` as a child process
  and talks JSON-RPC over its stdin/stdout.
- **HTTP** (`-Dtl-mcp.port=...`): embedded Jetty serves the MCP streamable-HTTP
  protocol. Clients POST JSON-RPC requests to `http://<host>:<port><path>` with
  `Accept: application/json, text/event-stream`. The server replies with a session
  id in the `Mcp-Session-Id` response header; subsequent requests echo it back via
  `mcp-session-id`.

## Using from Claude Code

Add an entry to an `.mcp.json` file (project-local or shared via a plugin). For
stdio, point `command` / `args` at a `mvn ...:serve` invocation in the *target*
project's working directory:

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

For HTTP, start the server once in a terminal
(`mvn -q ...:serve -Dtl-mcp.port=18765`) and configure the client to reach it:

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

The `initialize` response carries an `instructions` block
(`McpTools.INSTRUCTIONS`) that tells the LLM when to prefer these tools over
filesystem `grep` / `find` and lists recipe-level usage notes. MCP clients that
surface server instructions to the model — Claude Code included — pick this up
automatically.

## Tools exposed

| Tool | Purpose |
|------|---------|
| `query_types` | Filter the type graph. Filters are optional, AND-combined: `name` / `pattern` / `regex`, `subtype_of` / `supertype_of` / `direct_only`, `annotated_with`, `in_module`, `kind` (`any` / `class` / `interface` / `concrete` / `enum` / `annotation`), `visibility` array (defaults to `["public"]`), `limit`. |
| `describe_type` | Metadata of a single FQN: visibility, modifiers, superclass, interfaces, enclosing outer class, module, source file, annotations with parsed parameter values, method/field counts, config/implementation pairing. |
| `list_members` | Methods and/or fields of a type with signatures (descriptor, return type, parameter types + names when the bytecode carries `MethodParameters` or an LVT), declared exceptions, modifiers, annotations with parsed parameter values, field constant values. Filters: `name` / `pattern` / `regex`, `kind` (`any` / `method` / `field`), `type` (return type or field type), `parameter_type`, `annotated_with`, `visibility`, `static` (tri-state), `limit`. Inherited members are not included — query each supertype separately. |
| `references_to` | Concrete usage sites of a type, Eclipse "Search Java" style. Each result carries `{owner, member, descriptor, target_member, target_descriptor, kind, count}`. Usage kinds: `supertype`, `type_annotation`, `field_type`, `field_annotation`, `method_return`, `method_parameter`, `method_exception`, `method_annotation` (always available); `instantiation`, `cast`, `instanceof`, `catch`, `call_dispatch`, `field_read`, `field_write` (require body scan). Filters: `kinds`, `owner_pattern`, `in_module`, `limit`. Response also includes a per-kind histogram. |
| `callers_of` | Call sites that invoke a method. Requires body scan. Calls are recorded in bytecode against the static declared owner, so by default (`include_overrides=true`) subtypes that declare an override with matching signature are also included. Filters: `owner_pattern`, `in_module`, `limit`. |
| `field_accessors` | Methods that read and/or write a field. Requires body scan. `mode`: `read` / `write` / `any`. Filters: `owner_pattern`, `in_module`, `limit`. |
| `show_source` | Returns the Java source of a type or one of its methods, from the reactor module's `src/main/java` or the companion `-sources.jar`. `mode`: `doc` (javadoc + annotations + signature only — default for classes), `source` (full implementation — default for members), `auto`. `context_lines=0` (default) anchors at the attached javadoc/annotation block; a positive value forces a fixed leading window. |
| `module_of` | Maven module id (or JAR GAV / filename) that supplied a class. |

### Member spec

`show_source` and `callers_of` accept a source-level member spec instead of a
bytecode descriptor:

| Input | Meaning |
|-------|---------|
| `foo` | Any overload named `foo` |
| `foo()` | The no-arg overload |
| `foo(ToolBar)` | Simple-name match on the parameter |
| `foo(com.top_logic.layout.ToolBar)` | FQN-exact match |
| `foo(String, int)` | Multiple parameters, any mix of simple / FQN |
| `foo(ToolBar tb)` | Trailing parameter names are stripped — source declarations paste cleanly |

Generics in the spec are ignored (the bytecode only has erased types).

### Access modifiers

Output uses a single `visibility` string (`public` / `protected` / `package` /
`private`). Input filters take a `visibility` array; `query_types` defaults to
`["public"]`, `list_members` defaults to all four. `static` is a separate tri-state
filter (omitted = both).

## Notes

- **Bytecode-only.** Generics are erased (parameters and return types are their
  raw forms). Local variable names are read from the `MethodParameters` attribute
  when present, otherwise from the LVT (debug info). JavaDoc and source formatting
  come from the companion `-sources.jar` / `src/main/java`, not the bytecode.
- **TopLogic config/implementation pairing** is rebuilt from constructor
  signatures: if a class exposes a constructor `(InstantiationContext, X)`, `X`
  is recorded as its configuration type and the reverse link is stamped onto
  `X.implementation`. Non-TopLogic projects simply leave both links `null`.
- **Reactor scope.** For multi-module projects the `serve` goal runs as an
  aggregator: invoking it from the root POM indexes every reactor module plus the
  union of their compile dependencies.
- **Result aggregation.** `callers_of`, `field_accessors` and the usage list of
  `references_to` deduplicate identical `(owner, member, kind[, target])` tuples
  and report a `count` for the multiplicity.
- **Error safety.** Every tool handler is wrapped in a `Throwable`-catching
  adapter; an uncaught exception turns into an `isError: true` result with a
  stack trace instead of leaving the client hanging.

## Release process

`distributionManagement` is wired to the TopLogic Nexus (same IDs as the engine:
`tl-releases` / `tl-snapshots`). Credentials live in your `~/.m2/settings.xml`
under those server IDs.

Snapshot deploy (continuous, from `*-SNAPSHOT` branches):

```bash
mvn deploy
```

Cutting a release:

```bash
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
mvn clean deploy
git commit -am "Release 0.1.0"
git tag v0.1.0 && git push --tags
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Bump to 0.2.0-SNAPSHOT"
git push
```

After the release lands in Nexus, update `tl-claude-tools` (or any other
consumer) to reference the released coordinate
(`com.top-logic:tl-mcp-server:0.1.0`) instead of the snapshot.

## Development

Tests use JUnit 5 and run through surefire:

```bash
mvn test
```

Two suites:

- `MemberSpecTest` — pure unit tests for the member-spec parser and overload
  matcher.
- `TypeGraphTest` — integration tests that build a `TypeGraph` from the plugin's
  own `target/classes` + `src/main/java` and assert against known facts (the
  `@Mojo` annotation on `ServeMojo`, `TypeGraph` references `TypeInfo`, source
  retrieval in both modes, overload disambiguation, module resolution).
