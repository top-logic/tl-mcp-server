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
mvn -q com.top-logic:tl-mcp-server:0.1.0:serve
```

Maven resolves the reactor's compile classpath and passes it to the plugin, which
indexes every class on it and starts the MCP server. With `-q` Maven keeps stdout
clean for the JSON-RPC stream.

`show_source` serves source for a dependency only when a `-sources.jar` sits next
to its binary jar in the local repository. Run `mvn dependency:sources` in the
target project beforehand to pull those in.

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
        "com.top-logic:tl-mcp-server:0.1.1-SNAPSHOT:serve",
        "-f", "/absolute/path/to/target-project/pom.xml"
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
| `list_resources` | Lists the immediate children of a directory in the application's web resource tree — the stacked `src/main/webapp` directories and `-web-fragment.war` overlays, unioned across all modules. Directory names end with `/`. `path` defaults to `/`. |
| `find_resources` | Recursively searches the resource tree for files whose name matches a glob (`pattern`, e.g. `*.layout.template.xml`), optionally scoped to a subtree (`under`, e.g. `/WEB-INF/layouts`). Returns distinct resource names (a file overridden in several modules appears once), sorted, with a `truncated` flag at `limit`. |
| `read_resource` | Returns the effective (topmost overriding) content of a web resource. Text is returned as `text`, binary as base64 `binary`; `max_bytes` caps the response with a `truncated` flag. |
| `resource_overlays` | Lists every module fragment that contributes a resource, in override order (highest priority first). The first entry is the version `read_resource` returns; later entries are overridden. Answers "which module provides / overrides this file". |
| `list_docs` | Lists the developer articles shipped by the modules on the classpath (`META-INF/tl-docs/*.md`): name, description (when to read it), contributing module, size. |
| `read_doc` | Reads a developer article by `name`. An article longer than `max_chars` (default 40000) is answered with its introduction and outline (headings); `section` (heading text or anchor) returns one section. |

The three resource tools reconstruct the same virtual filesystem the running
application sees through its `FileManager` (the resource stack and its override
order come from the reactor classpath via TopLogic's own `Workspace` / `PathInfo`
/ `MultiFileManager`). They are registered only when the inspected reactor is a
web application; a non-web project simply omits them.

### Developer articles

A module ships developer articles as Markdown files `META-INF/tl-docs/<name>.md`
in its jar (in the engine: `src/main/java/META-INF/tl-docs/`). The file name is
the article name; a front matter block gives its description, worded as when to
read it:

```markdown
---
description: Read before writing or changing a .view.xml of the React view layer ...
---

# FAQ: React view layer
```

The articles are collected from the `src/main/java` directories of the reactor
modules first, then from the compile classpath (the first article of a name
wins), so a workspace sees exactly the articles of the modules and versions it
builds against. At startup the server writes their index (one line per article
with name and description) to `target/tl-docs-index.md` of the project it runs
in; the `tl-claude-tools` plugin prints this file at the start of a Claude Code
session, so the agent learns which articles exist without starting a tool call.

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

Releases are cut by the Jenkins **`Release_Auto`** job, which runs the standard
`maven-release-plugin` flow (`release:prepare` + `release:perform`) on a clean
build agent and deploys to the TopLogic Nexus (`tl-releases`).

**Prerequisite:** push all release commits to the branch first — the job clones
the remote, so anything committed only locally is *not* released.

Trigger `Release_Auto` with:

| Parameter | Value | Notes |
|-----------|-------|-------|
| `REPO` | `https://git.top-logic.com/TopLogic/tl-mcp-server.git` | This repository. |
| `BRANCH` | `master` | Branch to release. |
| `BUILD_MODULE` | `.` | Single-module project. |
| `VERSION` | *(empty)* | Releases the version the current `-SNAPSHOT` points to (e.g. `0.1.1-SNAPSHOT` → `0.1.1`). Set only to force a version jump. |
| `NEXT_VERSION` | *(empty)* | Next development version; empty increments the micro (→ `0.1.2-SNAPSHOT`). |
| `NEXT_MINOR_VERSION` | `false` | `true` increments the minor (micro = 0) for the next development version instead. |

The job tags `v<version>`, runs `clean install`, deploys the release to Nexus,
and commits the next `-SNAPSHOT` back to the branch. Tests are **skipped** on the
release build (`-DskipTests=true`), so run them locally before triggering — see
[Development](#development).

After the release lands in Nexus, update every consumer to the released
coordinate `com.top-logic:tl-mcp-server:<version>`:

- **`tl-claude-tools`** (the Claude Code plugin that launches this server) — bump
  the `serve` coordinate in `.mcp.json`, update the version references in its
  README, and bump the plugin `version` in `.claude-plugin/plugin.json`. Pushing
  the plugin repo is not enough: refresh the installed plugin via Claude Code's
  `/plugin` flow so sessions pick up the new version.

### Local release / snapshot deploy

`distributionManagement` is wired to the TopLogic Nexus (same IDs as the engine:
`tl-releases` / `tl-snapshots`); credentials live in your `~/.m2/settings.xml`
under those server IDs. To deploy a snapshot or cut a release without Jenkins
(the underlying `maven-release-plugin` flow; tag format `v<version>`, preparation
runs `clean install`):

```bash
mvn deploy              # snapshot deploy from a *-SNAPSHOT
mvn release:prepare     # drops SNAPSHOT, clean install, commit, tag, bump to next SNAPSHOT
mvn release:perform     # checks out the tag and deploys to Nexus
mvn release:clean       # discard release working files to retry before perform
```

## Development

Tests use JUnit 5 and run through surefire:

```bash
mvn test
```

Four suites:

- `MemberSpecTest` — pure unit tests for the member-spec parser and overload
  matcher.
- `TypeGraphTest` — integration tests that build a `TypeGraph` from the plugin's
  own `target/classes` + `src/main/java` and assert against known facts (the
  `@Mojo` annotation on `ServeMojo`, `TypeGraph` references `TypeInfo`, source
  retrieval in both modes, overload disambiguation, module resolution).
- `ResourceBrowserTest` — reconstructs a stacked web resource tree from a
  synthetic classpath and asserts override ordering, listing, text/binary reads,
  truncation, and `-web-fragment.war` overlays.
- `DocCatalogTest` — collects developer articles from a source directory and a
  jar and asserts precedence, front matter, outline, sections and the index.

## Pending maintenance

**Remove the `McpResourcePaths` shim once engine ticket #29382 ships.**

`ResourceBrowser` reconstructs the application's web resource stack via
`com.top_logic.basic.core.workspace.PathInfo`, whose builder API is
package-private. Until the engine exposes a supported entry point, this is
reached through `McpResourcePaths` — a shim declared in that engine package but
compiled into this plugin (a deliberate split-package workaround).

Engine [ticket #29382](http://tl/trac/ticket/29382) adds a public
`Workspace.getAppPaths(Iterable<Path>, String, String[])` overload
([PR #1451](http://git.top-logic.com/TopLogic/tl-engine-7/pulls/1451)). When a
`tl-basic` release containing it is available:

1. Delete `src/main/java/com/top_logic/basic/core/workspace/McpResourcePaths.java`.
2. In `ResourceBrowser.fromClasspath`, call `Workspace.getAppPaths(classpath, …)`
   instead of `McpResourcePaths.getAppPaths(classpath, …)`.
3. Bump `<tl.version>` to that release.
