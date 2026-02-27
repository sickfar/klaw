# klaw CLI Reference

The `klaw` binary is the primary administration interface for the Klaw system.
It communicates with the Engine via Unix domain socket (`engine.sock`).

## Setup

### `klaw init`

Interactive first-time setup wizard. Guides through:

1. Directory creation (XDG-compliant paths)
2. LLM provider configuration (base URL, API key, model ID)
3. Telegram bot setup (token, allowed chat IDs)
4. Engine configuration
5. WebSocket chat setup (enable `klaw chat`, port selection)
6. Config file generation (`engine.yaml`, `gateway.yaml`, `.env`)
7. Engine auto-start
8. Identity Q&A (agent name, personality, role, user description, domain)
9. Identity file generation (SOUL.md, IDENTITY.md, AGENTS.md, USER.md via LLM)
10. Service setup

```
klaw init
```

**Docker mode:** When `klaw init` is run inside a Docker container (detected via `/.dockerenv`), phases 5 and 8 use Docker Compose instead of systemd/launchd. The engine and gateway containers are started via `docker compose up -d` — no systemd unit files are written. See [klaw init in Docker](../deployment/local-dev.md#klaw-init-in-docker) for details.

**Native mode (Linux/Pi):** Phase 5 runs `systemctl --user start klaw-engine`, phase 8 writes systemd unit files to `~/.config/systemd/user/`.

**Native mode (macOS):** Phase 5 runs `launchctl load -w ~/Library/LaunchAgents/io.github.klaw.engine.plist`, phase 8 writes launchd plist files.

### `klaw config set KEY VALUE`

Updates a single key in `engine.yaml` without a full re-init.
Performs a line-level replacement of the YAML key's value.

```
klaw config set routing.default glm/glm-5
klaw config set maxTokens 4096
```

Prints a reminder to restart the Engine after changes:
> Updated routing.default. Restart Engine to apply changes.

### `klaw engine start / stop / restart`

Starts, stops, or restarts the engine service.

```
klaw engine start
klaw engine stop
klaw engine restart
```

In Docker environments, uses `docker compose up -d` / `stop` / `restart`.
On Linux, delegates to `systemctl --user`.
On macOS, delegates to `launchctl`.

### `klaw gateway start / stop / restart`

Starts, stops, or restarts the gateway service.

```
klaw gateway start
klaw gateway stop
klaw gateway restart
```

### `klaw stop`

Stops both gateway and engine (gateway first, then engine).

```
klaw stop
```

---

### `klaw identity edit`

Opens `SOUL.md` and `IDENTITY.md` from the workspace in your `$EDITOR` (falls back to `vi`).

```
klaw identity edit
```

---

## Chat

### `klaw chat`

Opens an interactive split-screen TUI for chatting with the agent directly from the terminal.
Messages are routed through the Gateway's `/chat` WebSocket endpoint using the persistent `console_default` session — the same path as Telegram, giving console conversations full JSONL logging and memory integration.

```
klaw chat
klaw chat --url ws://sickfar-pi.local:37474/chat
```

Options:
- `--url URL` — connect to a specific gateway WebSocket URL, bypassing the `enabled` check in `gateway.yaml`

**Requirements:** The console channel must be enabled in `gateway.yaml`. If it is not, `klaw chat` prints an actionable error with instructions.

**Enabling:** Run `klaw init` and answer `y` at the WebSocket chat setup phase, or add to `gateway.yaml`:

```yaml
channels:
  console:
    enabled: true
    port: 37474
```

Then restart the gateway:

```
klaw gateway restart
```

**Quit:** Press `Ctrl+C` or type `/exit` and press Enter.

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
