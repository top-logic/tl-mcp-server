package com.top_logic.tools.mcp;

import java.util.List;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Entry point of the TopLogic MCP server.
 */
public class Main {

	public static void main(String[] args) throws Exception {
		McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
		StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

		McpSyncServer server = McpServer.sync(transport)
			.serverInfo("tl-mcp-server", "0.1.0")
			.capabilities(McpSchema.ServerCapabilities.builder()
				.tools(null)
				.build())
			.build();

		server.addTool(pingTool(jsonMapper));

		Thread.currentThread().join();
	}

	private static SyncToolSpecification pingTool(McpJsonMapper jsonMapper) {
		String inputSchema = """
			{
			  "type": "object",
			  "properties": {
			    "message": {
			      "type": "string",
			      "description": "Optional message to echo back."
			    }
			  }
			}
			""";

		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("ping")
				.description("Liveness check. Echoes the optional 'message' argument back to the caller.")
				.inputSchema(jsonMapper, inputSchema)
				.build())
			.callHandler((exchange, request) -> {
				Object msg = request.arguments().get("message");
				String reply = msg == null ? "pong" : "pong: " + msg;
				return CallToolResult.builder()
					.content(List.of(new McpSchema.TextContent(reply)))
					.build();
			})
			.build();
	}

}
