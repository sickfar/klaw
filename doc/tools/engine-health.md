# Engine Health

## engine_health

Get engine health status including gateway connection, uptime, database, sandbox, scheduled jobs, active sessions, pending deliveries, and more.

**Parameters:** None.

**Returns:** JSON object with system health data:

| Field | Type | Description |
|-------|------|-------------|
| `gateway_status` | string | `"connected"` or `"disconnected"` |
| `engine_uptime` | string | ISO 8601 duration (e.g. `"PT72H15M"`) |
| `docker` | boolean | Whether engine runs inside a Docker container |
| `sandbox` | object | Sandbox container status (see below) |
| `mcp_servers` | array | Names of connected MCP servers |
| `embedding_service` | string | `"onnx"` or `"ollama"` |
| `sqlite_vec` | boolean | Whether sqlite-vec extension is loaded |
| `database_ok` | boolean | Database health check result |
| `scheduled_jobs` | int | Number of scheduled Quartz jobs |
| `active_sessions` | int | Number of active chat sessions |
| `pending_deliveries` | int | Messages buffered for gateway delivery |
| `heartbeat_running` | boolean | Whether a heartbeat run is in progress |
| `docs_enabled` | boolean | Whether documentation service is enabled |
| `memory_facts` | int | Total memory facts in long-term memory |

### Sandbox object

| Field | Type | Description |
|-------|------|-------------|
| `enabled` | boolean | Whether sandbox execution is available |
| `keep_alive` | boolean | Whether keep-alive container mode is on |
| `container_active` | boolean | Whether a sandbox container is running |
| `executions` | int | Execution count in current container session |

## Environment Awareness

The system prompt includes an `## Environment` section injected automatically on every request:

```
## Environment
2026-03-17 10:30:00 MSK
Gateway: connected | Uptime: 3d 2h
Jobs: 5 | Sessions: 2 | Sandbox: ready
Embedding: onnx | Docker: yes
```

This provides situational awareness without a tool call. For detailed diagnostics, use `engine_health`.

## Use Cases

- **Heartbeat health check:** Agent calls `engine_health` during heartbeat to detect issues and report proactively.
- **Troubleshooting:** When something seems wrong, agent can check gateway connection, pending deliveries, database status.
- **Situational awareness:** The `## Environment` section gives the agent instant context about system state on every interaction.
