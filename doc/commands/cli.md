# klaw CLI Reference

The `klaw` binary is the primary administration interface for the Klaw system.
It communicates with the Engine via Unix domain socket (`engine.sock`).

## Setup

### `klaw init`

Interactive first-time setup wizard. Guides through 10 phases:

1. Pre-check (aborts if `engine.json` already exists)
2. Deployment mode selection (native or Docker services — skipped inside Docker containers)
3. LLM provider configuration (base URL, API key, model ID)
4. Telegram bot setup (token, allowed chat IDs)
5. WebSocket chat setup (enable `klaw chat`, port selection)
6. Setup (directory creation, config file generation: `engine.json`, `gateway.json`, `.env`, `deploy.conf`, `docker-compose.json` for hybrid mode)
7. Engine auto-start
8. Service/container startup
9. Identity Q&A (agent name, personality, role, user description, domain)
10. Identity file generation (SOUL.md, IDENTITY.md, AGENTS.md, USER.md via LLM)

```
klaw init
```

#### Deployment modes

Phase 2 presents a mode selector with two options when running on the host (not inside Docker):

- **Fully native (systemd/launchd)** — Engine and Gateway run as native services. Phase 8 writes systemd unit files (Linux) or launchd plists (macOS).
- **Docker services** (hybrid) — CLI stays native on the host; Engine and Gateway run in Docker containers with bind mounts to host XDG directories. Phase 6 generates `~/.config/klaw/docker-compose.json` with host-path bind mounts. Phase 8 runs `docker compose up -d`.

When running inside a Docker container (detected via `/.dockerenv`), mode is auto-set to **Docker** and Phase 2 is skipped. See [klaw init in Docker](../deployment/local-dev.md#klaw-init-in-docker) for details.

For hybrid and Docker modes, Phase 2 also prompts for a **Docker image tag** (default: `latest`).

The chosen mode and tag are persisted to `~/.config/klaw/deploy.conf` so subsequent `klaw engine start`, `klaw stop`, etc. route commands correctly without re-prompting.

### `klaw config set KEY VALUE`

Updates a single key in `engine.json` without a full re-init.
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

Routing depends on the deployment mode stored in `deploy.conf`:
- **Native:** delegates to `systemctl --user` (Linux) or `launchctl` (macOS).
- **Hybrid / Docker:** uses `docker compose` with the appropriate compose file.

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
- `--url URL` — connect to a specific gateway WebSocket URL, bypassing the `enabled` check in `gateway.json`

**Requirements:** The console channel must be enabled in `gateway.json`. If it is not, `klaw chat` prints an actionable error with instructions.

**Enabling:** Run `klaw init` and answer `y` at the WebSocket chat setup phase, or add to `gateway.json`:

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

Prints the contents of `MEMORY.md` from the workspace.

```
klaw memory show
```

### `klaw memory edit`

Opens `MEMORY.md` from the workspace in `$EDITOR`.

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

- `engine.json` exists
- `gateway.json` exists
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
