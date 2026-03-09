# mcp.json — MCP Server Configuration

MCP (Model Context Protocol) configuration lives in `~/.config/klaw/mcp.json`. This file is **optional** — if absent, MCP is disabled and the engine runs with built-in tools only.

## Structure

```json
{
  "servers": {
    "<server-name>": {
      "enabled": true,
      "transport": "stdio" | "http",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"],
      "env": {"KEY": "value"},
      "url": "http://mcp-server:8080/mcp",
      "apiKey": "${MCP_API_KEY}",
      "timeoutMs": 30000,
      "reconnectDelayMs": 5000,
      "maxReconnectAttempts": 0
    }
  }
}
```

## Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `true` | Enable/disable this server |
| `transport` | string | required | `"stdio"` or `"http"` |
| `command` | string | null | Command to spawn (stdio only) |
| `args` | string[] | `[]` | Command arguments (stdio only) |
| `env` | map | `{}` | Extra environment variables (stdio only) |
| `url` | string | null | HTTP endpoint URL (http only) |
| `apiKey` | string | null | Bearer token for HTTP auth. Supports `${VAR}` env var substitution from `.env` |
| `timeoutMs` | long | `30000` | Per-call timeout in milliseconds |
| `reconnectDelayMs` | long | `5000` | Delay before reconnect attempt (stdio only) |
| `maxReconnectAttempts` | int | `0` | Max reconnect attempts, 0 = infinite (stdio only) |

## Transport Types

### stdio

Spawns a subprocess and communicates via stdin/stdout using JSON-RPC over newline-delimited JSON.

**Native mode**: Direct subprocess via `ProcessBuilder`.

**Docker/hybrid mode**: Auto-containerized based on command:
- `npx` / `node` → runs in `node:22-alpine` container
- `uvx` / `python` / `python3` → runs in `python:3.12-slim` container
- `docker` → passed through as-is (user manages container)
- Other commands → skipped with a warning. Use HTTP transport or wrap in a `docker` command.

### http

Sends JSON-RPC requests via HTTP POST to the specified URL. Stateless — no persistent connection.

## Tool Namespacing

MCP tools are exposed to the LLM with namespaced names: `mcp__<serverName>__<toolName>` (double underscore separator, matches Claude Code convention).

Example: A tool `read_file` from server `filesystem` becomes `mcp__filesystem__read_file`.

## Examples

### HTTP server (e.g., Home Assistant MCP)

```json
{
  "servers": {
    "home-assistant": {
      "transport": "http",
      "url": "http://ha-mcp:8080/mcp",
      "apiKey": "${HA_MCP_API_KEY}"
    }
  }
}
```

### Stdio server (e.g., filesystem MCP)

```json
{
  "servers": {
    "filesystem": {
      "transport": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/home/user/workspace"]
    }
  }
}
```

### Multiple servers with one disabled

```json
{
  "servers": {
    "web-search": {
      "transport": "http",
      "url": "http://search-mcp:8080/mcp"
    },
    "database": {
      "transport": "http",
      "url": "http://db-mcp:8080/mcp",
      "apiKey": "${DB_MCP_KEY}"
    },
    "experimental": {
      "enabled": false,
      "transport": "stdio",
      "command": "python3",
      "args": ["my_mcp_server.py"]
    }
  }
}
```

## Diagnostics

Run `klaw doctor` to validate `mcp.json` configuration:
- Checks JSON syntax and required fields
- In Docker/hybrid mode, validates stdio commands have known Docker images
- Reports disabled servers
- Warns about missing env vars referenced in `apiKey`

## Docker Deployment

The `mcp.json` file is automatically available inside containers via the existing config directory bind mount. MCP servers in Docker mode should be deployed as separate sidecar containers reachable via HTTP.
