# tl-mcp-server

Local [Model Context Protocol](https://modelcontextprotocol.io/) server that exposes
TopLogic class and type indexes (and, later, TL-Script function documentation) to AI
agents such as Claude Code.

## Status

Early prototype. Currently exposes a single `ping` tool used to verify the MCP plumbing.

## Requirements

- JDK 17
- Maven 3.6+

## Build

```bash
mvn clean package
```

## Run

```bash
mvn exec:java
```

The server speaks MCP over stdio, so direct interactive use is not meaningful — wire it
into an MCP-capable client (see below).

## Using from Claude Code

Add an entry to an `.mcp.json` file (project-local or referenced from a plugin):

```json
{
  "mcpServers": {
    "tl-mcp": {
      "command": "mvn",
      "args": ["-q", "-f", "/absolute/path/to/tl-mcp-server/pom.xml", "exec:java"]
    }
  }
}
```

Startup cost is a few seconds per Claude Code session while Maven resolves the reactor
and boots the JVM. For faster cold starts, switch to a pre-resolved classpath
(`mvn dependency:build-classpath` + `java -cp …`) or a shaded runnable JAR.
