# Built-in Tools Overview

Klaw Engine provides 19 built-in tools that LLM agents can invoke during conversations. Tools are registered via `ToolRegistryImpl` and executed concurrently through `DispatchingToolExecutor`.

## Tool Categories

### Memory Tools (5)
| Tool | Description |
|------|-------------|
| `memory_search` | Hybrid search over long-term memory (vector + FTS5) |
| `memory_save` | Save information to long-term memory |
| `memory_core_get` | Retrieve core memory (user/agent sections) |
| `memory_core_update` | Update a key in core memory |
| `memory_core_delete` | Delete a key from core memory |

See [memory.md](memory.md) for details.

### File Tools (3)
| Tool | Description |
|------|-------------|
| `file_read` | Read a file from the workspace |
| `file_write` | Write content to a file |
| `file_list` | List directory contents |

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

### Schedule Tools (3)
| Tool | Description |
|------|-------------|
| `schedule_list` | List scheduled tasks |
| `schedule_add` | Add a cron-scheduled task |
| `schedule_remove` | Remove a scheduled task |

See [schedule.md](schedule.md).

### Subagent Tools (1)
| Tool | Description |
|------|-------------|
| `subagent_spawn` | Spawn a fire-and-forget subagent |

See [subagent.md](subagent.md).

### Utility Tools (2)
| Tool | Description |
|------|-------------|
| `current_time` | Get the current date and time |
| `send_message` | Send a message to a specific channel and chat |

See [utils.md](utils.md).

## Tool Call Loop Protection

Engine enforces a `maxToolCallRounds` limit (configured in `engine.yaml` under `engine.llm.maxToolCallRounds`). After this many consecutive rounds of tool calls without a final text response, the engine injects a stop signal to prevent infinite loops.

## Architecture

- `ToolRegistry` interface (in `engine/context/`) lists available tool definitions.
- `ToolRegistryImpl` is the `@Singleton` implementation that holds all 19 `ToolDef` entries and dispatches execution to the appropriate tool class.
- `DispatchingToolExecutor` executes tool calls concurrently via `coroutineScope { async {} }`.
- Dependency services (memory, docs, scheduler) are injected as interfaces with stub implementations replaced by real ones in later phases.
