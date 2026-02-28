# Workspace Files

The workspace directory (`$KLAW_WORKSPACE`) contains files that define the agent's identity, instructions, memory, and recurring tasks.

All files are optional. Missing files are silently skipped.

---

## System prompt files

These files are loaded at startup and combined into the system prompt sent with every LLM request.

### `SOUL.md`

The agent's philosophy, values, and character. Loaded under the `## Soul` section of the system prompt.

Defines the fundamental "who" — the agent's motivations, ethical principles, and personality traits. This is the most stable file; it rarely needs to change.

### `IDENTITY.md`

The agent's name, presentation, and tone. Loaded under `## Identity`.

Defines how the agent presents itself: its name, speaking style, and relationship with the user. More specific than SOUL.md.

### `USER.md`

Information about the user. Loaded under `## About the User` in the system prompt, positioned after IDENTITY.md and before AGENTS.md.

Contains user preferences, background, and any context the agent should know about the user.

### `AGENTS.md`

Operational instructions: priorities, rules, behavioral boundaries, memory usage guidance. Loaded under `## Instructions`.

This is the most important file for day-to-day behavior. Define: what the agent should focus on, what it should avoid, how it should use tools, how often to check memory, response format preferences.

### `TOOLS.md`

Environment notes: SSH host addresses, local service URLs, API quirks, tool-specific instructions. Loaded under `## Environment Notes`.

Not for defining tools (tools are built-in). Use this to tell the agent about the specific environment it operates in.

**System prompt order:** `SOUL.md` → `IDENTITY.md` → `USER.md` → `AGENTS.md` → `TOOLS.md`

---

## Memory files

### `MEMORY.md`

Long-term memory notes. Chunked and indexed in sqlite-vec at startup. Becomes searchable via `memory_search`.

Append new facts here for durable long-term storage. Changes take effect on next engine restart (re-indexed at startup).

For date-specific memories, use the `memory/` directory instead.

### `memory/` directory

Daily memory log files named `YYYY-MM-DD.md`. Indexed alongside `MEMORY.md` at startup.

Use `file_write` to append new entries. Each file is indexed as a separate source — queries can return chunks from different dates.

---

## Scheduling file

### `HEARTBEAT.md`

Defines recurring scheduled tasks. Parsed into Quartz jobs at startup.

See `doc/scheduling/heartbeat.md` for the format and import behavior.

---

## Skills directory

### `skills/`

Workspace-local skill definitions (`.md` files). Override system skills with the same name.

Workspace skills take precedence over global skills in `$XDG_DATA_HOME/klaw/skills/`.

See `doc/skills/` for skill format documentation.

---

## Summary

| File | When loaded | Purpose |
|------|-------------|---------|
| `SOUL.md` | Startup → system prompt | Agent philosophy and values |
| `IDENTITY.md` | Startup → system prompt | Agent name and tone |
| `USER.md` | Startup → system prompt | User information |
| `AGENTS.md` | Startup → system prompt | Operational instructions |
| `TOOLS.md` | Startup → system prompt | Environment notes |
| `MEMORY.md` | Startup → sqlite-vec | Long-term memory index |
| `memory/*.md` | Startup → sqlite-vec | Daily memory logs |
| `HEARTBEAT.md` | Startup → Quartz | Recurring task definitions |
| `skills/*.md` | Startup → skill registry | Workspace-local skills |

---

## See also

- `doc/workspace/openclaw-compatibility.md` — using OpenClaw workspaces
- `doc/scheduling/heartbeat.md` — HEARTBEAT.md format
- `doc/memory/` — memory system documentation
