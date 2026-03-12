# Built-in Tools Overview

Klaw Engine provides 21 standard tools plus 2 contextual delivery tools that are injected only in specific execution contexts. Tools are registered via `ToolRegistryImpl` and executed concurrently through `DispatchingToolExecutor`.

## Tool Categories

### Memory Tools (2)
| Tool | Description |
|------|-------------|
| `memory_search` | Hybrid search over long-term memory (vector + FTS5) |
| `memory_save` | Save information to long-term memory |

See [memory.md](memory.md) for details.

### File Tools (4)
| Tool | Description |
|------|-------------|
| `file_read` | Read a file from the workspace |
| `file_write` | Write content to a file |
| `file_list` | List directory contents |
| `file_patch` | Replace a text fragment in a file by exact match |

All paths are restricted to `$KLAW_WORKSPACE`. Path traversal attempts are rejected. See [files.md](files.md).

### Documentation Tools (3)
| Tool | Description |
|------|-------------|
| `docs_search` | Search project documentation |
| `docs_read` | Read a specific document |
| `docs_list` | List available documents |

See [docs.md](docs.md).

### Skills Tools (2)
| Tool | Description |
|------|-------------|
| `skill_list` | List available skills |
| `skill_load` | Load a skill's full content into context |

See [skills.md](skills.md).

### Schedule Tools (3 + 1 contextual)
| Tool | Description |
|------|-------------|
| `schedule_list` | List scheduled tasks |
| `schedule_add` | Add a scheduled or one-time task |
| `schedule_remove` | Remove a scheduled task |
| `schedule_deliver` | **Contextual** — deliver a result to the user; only available inside scheduled tasks and spawned subagents when `injectInto` is set |

See [schedule.md](schedule.md).

### Subagent Tools (1)
| Tool | Description |
|------|-------------|
| `subagent_spawn` | Spawn a fire-and-forget subagent |

See [subagent.md](subagent.md).

### Code Execution Tools (2)
| Tool | Description |
|------|-------------|
| `sandbox_exec` | Execute bash scripts in an isolated Docker container |
| `host_exec` | Execute a shell command on the host with access control |

See [sandbox-exec.md](sandbox-exec.md) and [host-exec.md](host-exec.md).

### History Tools (1)
| Tool | Description |
|------|-------------|
| `history_search` | Search past conversation messages semantically |

See [history-search.md](history-search.md).

### Configuration Tools (2)
| Tool | Description |
|------|-------------|
| `config_get` | Read current engine or gateway configuration |
| `config_set` | Update a configuration field |

See [config.md](config.md).

### Utility Tools (1)
| Tool | Description |
|------|-------------|
| `send_message` | Send a message to a specific channel and chat |

See [utils.md](utils.md).

### Heartbeat Tools (1 contextual)
| Tool | Description |
|------|-------------|
| `heartbeat_deliver` | **Contextual** — deliver a result during a heartbeat run; only available when a delivery target is configured |

See [heartbeat.md](../scheduling/heartbeat.md).

## Tool Call Loop Protection

Engine enforces a `maxToolCallRounds` limit (configured in `engine.json` under `engine.llm.maxToolCallRounds`). After this many consecutive rounds of tool calls without a final text response, the engine injects a stop signal to prevent infinite loops.

## Architecture

- `ToolRegistry` interface (in `engine/context/`) lists available tool definitions.
- `ToolRegistryImpl` is the `@Singleton` implementation that holds all 21 `ToolDef` entries and dispatches execution to the appropriate tool class.
- `DispatchingToolExecutor` executes tool calls concurrently via `coroutineScope { async {} }`.
- Dependency services (memory, docs, scheduler) are injected as interfaces with stub implementations replaced by real ones in later phases.
