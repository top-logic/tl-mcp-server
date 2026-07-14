#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
#
# Launcher for the tl-mcp server that first pulls dependency source jars into the
# local Maven repository, so `show_source` can serve source for external
# dependencies (not just reactor modules). Without this step the server only finds
# a `-sources.jar` if it already happens to sit next to the binary jar.
#
# All arguments are forwarded verbatim to Maven, e.g.:
#   ./serve.sh -f /path/to/target-project/pom.xml
#   ./serve.sh -f /path/to/pom.xml -Dtl-mcp.port=18765
#
set -u

# Coordinate of the serve goal; override with TL_MCP_COORD when testing a build.
TL_MCP_COORD="${TL_MCP_COORD:-com.top-logic:tl-mcp-server:0.1.1-SNAPSHOT}"

# Resolve `-sources.jar` siblings for every dependency on the reactor classpath.
# Best-effort: many artifacts publish no sources and offline runs should still
# start, so failure is non-fatal (-fn, plus `|| true`). stdout is redirected to
# stderr because on the default stdio transport Maven's build output would
# otherwise corrupt the JSON-RPC stream the MCP client reads from stdout.
mvn -q -fn dependency:sources "$@" 1>&2 || true

# Hand off to the server. exec so signals reach Maven directly.
exec mvn -q "${TL_MCP_COORD}:serve" "$@"
