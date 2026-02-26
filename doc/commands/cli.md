# klaw CLI Reference

The `klaw` binary is the primary administration interface for the Klaw system.
It communicates with the Engine via Unix domain socket (`engine.sock`).

## Setup

### `klaw init`

Interactive first-time setup wizard. Guides through:

1. Directory creation (XDG-compliant paths)
2. LLM provider configuration (base URL, API key, model ID)
3. Telegram bot setup (token, allowed chat IDs)
4. Config file generation (`engine.yaml`, `gateway.yaml`, `.env`)
5. Engine auto-start (systemd on Linux, launchd on macOS)
6. Identity Q&A (agent name, personality, role, user description, domain)
7. Identity file generation (SOUL.md, IDENTITY.md, AGENTS.md, USER.md via LLM)
8. Service unit installation (systemd or launchd)

```
klaw init
```

### `klaw config set KEY VALUE`

Updates a single key in `engine.yaml` without a full re-init.
Performs a line-level replacement of the YAML key's value.

```
klaw config set routing.default glm/glm-5
klaw config set maxTokens 4096
```

Prints a reminder to restart the Engine after changes:
> Updated routing.default. Restart Engine to apply changes.

### `klaw identity edit`

Opens `SOUL.md` and `IDENTITY.md` from the workspace in your `$EDITOR` (falls back to `vi`).

```
klaw identity edit
```

---

## Status & Sessions

### `klaw status`

Displays current Engine status and active session count.

```
klaw status
```

### `klaw sessions`

Lists all active chat sessions with chat ID and model.

```
klaw sessions
```

---

## Scheduling

### `klaw schedule list`

Lists all scheduled jobs.

```
klaw schedule list
```

### `klaw schedule add NAME CRON MESSAGE`

Creates a new scheduled job. The Engine will send MESSAGE at the CRON interval.

```
klaw schedule add daily "0 9 * * *" "Good morning!"
klaw schedule add weekly "0 10 * * 1" "Weekly review" --model glm/glm-5
```

Options:
- `--model MODEL` — override model for this scheduled message (optional)

### `klaw schedule remove NAME`

Removes a scheduled job by name.

```
klaw schedule remove daily
```

---

## Memory

### `klaw memory show`

Prints the contents of `core_memory.json`.

```
klaw memory show
```

### `klaw memory edit`

Opens `core_memory.json` in `$EDITOR`.

```
klaw memory edit
```

### `klaw memory search QUERY`

Searches the memory index (delegated to Engine).

```
klaw memory search "machine learning papers"
```

---

## Logs

### `klaw logs`

Shows the most recent conversation messages from JSONL files.

```
klaw logs
klaw logs --lines 50
klaw logs --chat telegram_123456
klaw logs --follow
```

Options:
- `--lines N` — number of recent messages to show (default: 20)
- `--chat CHAT_ID` — filter by chat ID
- `--follow` — stream new messages as they arrive

---

## Diagnostics

### `klaw doctor`

Checks the Klaw installation for common issues:

- `engine.yaml` exists
- `gateway.yaml` exists
- Workspace directory exists
- Engine socket present (Engine running/stopped)
- ONNX embedding model present
- sqlite-vec extension present

```
klaw doctor
```

---

## Reindex

### `klaw reindex`

Triggers full conversation re-indexing in the Engine (rebuilds SQLite from JSONL).

```
klaw reindex
```

---

## Notes

- Commands requiring the Engine (`status`, `sessions`, `schedule`, `memory search`, `reindex`) print a
  helpful error if the Engine is not running.
- All config files are in `~/.config/klaw/` (XDG-compliant, overridable via `XDG_CONFIG_HOME`).
- The `.env` file is created with `0600` permissions — never world-readable.
