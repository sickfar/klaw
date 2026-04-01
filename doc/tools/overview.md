# Built-in Tools Overview

Klaw Engine provides standard tools plus 2 contextual delivery tools that are injected only in specific execution contexts. All tools are directly available. Bundled skills provide detailed usage guidelines — load with `skill_load`. Tools are registered via `ToolRegistryImpl` and executed concurrently through `DispatchingToolExecutor`.

## Tool Categories

### Memory Tools (5)
| Tool | Description |
|------|-------------|
| `memory_search` | Hybrid search over long-term memory (vector + FTS5) |
| `memory_save` | Save information to long-term memory |
| `memory_rename_category` | Rename a memory category |
| `memory_merge_categories` | Merge memory categories into one |
| `memory_delete_category` | Delete a memory category |

See [memory.md](memory.md) for details.

### File Tools (5)
| Tool | Description |
|------|-------------|
| `file_read` | Read a file from the workspace (supports inline vision for images) |
| `file_write` | Write content to a file |
| `file_list` | List directory contents |
| `file_patch` | Replace a text fragment in a file by exact match |
| `file_glob` | Search for files by glob pattern |

All paths are restricted to accessible directories (workspace, state, data, config, cache). Path traversal attempts are rejected. See [files.md](files.md).

### Vision Tools (1)
| Tool | Description |
|------|-------------|
| `image_analyze` | Send an image to a vision-capable model for text description |

Vision tools are available only when `vision.enabled` is `true` in `engine.json` and a vision model is configured. See [vision.md](vision.md).

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

### Schedule Tools (6 + 1 contextual)
| Tool | Description |
|------|-------------|
| `schedule_list` | List scheduled tasks |
| `schedule_add` | Add a scheduled or one-time task |
| `schedule_remove` | Remove a scheduled task |
| `schedule_edit` | Edit a scheduled task |
| `schedule_enable` | Resume a paused task |
| `schedule_disable` | Pause a scheduled task |
| `schedule_deliver` | **Contextual** — deliver a result to the user; only available inside scheduled tasks and spawned subagents when `injectInto` is set |

See [schedule.md](schedule.md). The `scheduling` bundled skill provides detailed usage guidelines.

### Subagent Tools (4)
| Tool | Description |
|------|-------------|
| `subagent_spawn` | Spawn a fire-and-forget subagent |
| `subagent_status` | Get status of a subagent run by ID |
| `subagent_list` | List recent subagent runs |
| `subagent_cancel` | Cancel a running subagent |

See [subagent.md](subagent.md).

### Code Execution Tools (2)
| Tool | Description |
|------|-------------|
| `sandbox_exec` | Execute bash scripts in an isolated Docker container |
| `host_exec` | Execute a shell command on the host with access control |

See [sandbox-exec.md](sandbox-exec.md) and [host-exec.md](host-exec.md).

### Web Tools (2)
| Tool | Description |
|------|-------------|
| `web_fetch` | Fetch a web page and convert HTML to readable markdown |
| `web_search` | Search the internet via Brave or Tavily API |

`web_fetch` is enabled by default. `web_search` requires an API key and is disabled by default — configure via `engine.json` `web.search` section. See [web-fetch.md](web-fetch.md) and [web-search.md](web-search.md).

### History Tools (1)
| Tool | Description |
|------|-------------|
| `history_search` | Search past conversation messages semantically |

See [history-search.md](history-search.md).

### Document Tools (2)
| Tool | Description |
|------|-------------|
| `pdf_read` | Read a PDF file and extract text content with page markers |
| `md_to_pdf` | Convert a Markdown file to PDF format |

See [documents.md](documents.md). The `documents` bundled skill provides detailed usage guidelines.

### Configuration Tools (2)
| Tool | Description |
|------|-------------|
| `config_get` | Read current engine or gateway configuration |
| `config_set` | Update a configuration field |

See [config.md](config.md). The `configuration` bundled skill provides detailed usage guidelines.

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

Engine enforces a `maxToolCallRounds` limit (configured in `engine.json` under `processing.maxToolCallRounds`). After this many consecutive rounds of tool calls without a final text response, the engine injects a stop signal to prevent infinite loops.

## Architecture

- `ToolRegistry` interface (in `engine/context/`) lists available tool definitions.
- `ToolRegistryImpl` is the `@Singleton` implementation that holds tool definitions and dispatches execution to the appropriate tool class.
- `DispatchingToolExecutor` executes tool calls concurrently via `coroutineScope { async {} }`.
- Dependency services (memory, docs, scheduler) are injected as interfaces with stub implementations replaced by real ones in later phases.
