# klaw CLI Reference

The `klaw` binary is the primary administration interface for the Klaw system.
It communicates with the Engine via TCP localhost (port `7470`).

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

The chosen mode and tag are persisted to `~/.config/klaw/deploy.conf` so subsequent `klaw service start engine`, `klaw service stop all`, etc. route commands correctly without re-prompting.

### `klaw config set KEY VALUE`

Updates a single key in `engine.json` without a full re-init.
Performs a line-level replacement of the YAML key's value.

```
klaw config set routing.default glm/glm-5
klaw config set context.defaultBudgetTokens 100000
```

Prints a reminder to restart the Engine after changes:
> Updated routing.default. Restart Engine to apply changes.

### `klaw config edit engine` / `klaw config edit gateway`

Opens an interactive TUI editor for the specified configuration file. Displays all properties with descriptions, grouped by section, with explicit values shown first and defaults below.

```
klaw config edit engine
klaw config edit gateway
```

**Navigation:**
- **Up/Down arrows** — move between properties
- **PageUp/PageDown** — scroll by page
- **Left/Right arrows** — cycle boolean and enum values
- **Enter** — edit a text/number value inline
- **Escape** — cancel inline edit
- **A** — add a new key to the map section under the cursor
- **D** — delete the map key under the cursor (press D again to confirm)
- **S** — save changes (validates against JSON schema before writing)
- **Q** — quit without saving

**Display:**
- Properties set in the config file appear first; defaults appear below a divider
- Boolean and enum values show `◂ value ▸` indicators
- Sensitive values (API keys, tokens) are masked as `***` unless they contain an env var pattern like `${API_KEY}`
- The current property's description is shown in the status bar

**Map sections** (e.g. `providers`, `models`):
- Map section headers show `── providers ──── [A]dd`; press **A** to add a new key
- Each key within a section shows `  ▸ keyname ──── [D]`; press **D** to delete it
- Deleting a key requires a second **D** to confirm; **Escape** cancels

**Validation:** On save, the editor validates the modified config against the JSON schema. If validation fails, errors are displayed and changes are not written until fixed.

### `klaw service start / stop / restart TARGET`

Manages Klaw services. TARGET is required and must be one of: `engine`, `gateway`, or `all`.

```
klaw service start engine
klaw service stop gateway
klaw service restart all
klaw service stop all
```

Routing depends on the deployment mode stored in `deploy.conf`:
- **Native:** delegates to `systemctl --user` (Linux) or `launchctl` (macOS).
- **Hybrid / Docker:** uses `docker compose` with the appropriate compose file.

---

## Chat

### `klaw chat`

Opens an interactive split-screen TUI for chatting with the agent directly from the terminal.
Messages are routed through the Gateway's `/chat` WebSocket endpoint using the persistent `local_ws_default` session — the same path as Telegram, giving local WebSocket conversations full JSONL logging and memory integration.

```
klaw chat
klaw chat --url ws://sickfar-pi.local:37474/chat
```

Options:
- `--url URL` — connect to a specific gateway WebSocket URL, bypassing the `enabled` check in `gateway.json`

**Input:**
- **Enter** — send message
- **Alt+Enter** — insert newline (multi-line input)
- **Arrow keys** — move cursor within input
- **Home / End** — jump to start/end of current line
- **Delete** — forward delete
- **Backspace** — delete before cursor

**Status indicator:** A spinner appears in the separator bar while the agent is processing your message.

**Tool approval:** When the agent requests tool execution approval, the TUI shows an `Approve: <command>? [Y/n]` prompt. Press `Y` to approve or `N` to reject.

**Requirements:** The local WebSocket channel must be enabled in `gateway.json`. If it is not, `klaw chat` prints an actionable error with instructions.

**Enabling:** Run `klaw init` and answer `y` at the WebSocket chat setup phase, or add to `gateway.json`:

```yaml
channels:
  localWs:
    enabled: true
    port: 37474
```

Then restart the gateway:

```
klaw service restart gateway
```

**Quit:** Press `Ctrl+C` or type `/exit` and press Enter.

---

## Pairing

### `klaw pair CHANNEL CODE`

Pairs a new chat with the bot using a pairing code. The code is generated when a user sends `/start` in an unpaired Telegram chat. Codes expire after 5 minutes.

```
klaw pair telegram ABC123
```

This adds the chat to the `allowedChats` list in `gateway.json` and removes the pairing request.

### `klaw unpair CHANNEL CHATID`

Removes a paired chat from the `allowedChats` list in `gateway.json`.

```
klaw unpair telegram 123456789
```

---

## Status

### `klaw status`

Displays current Engine status and active session count.

```
klaw status
```

#### `--deep`

Deep health probe — shows gateway status, uptime, database health, scheduler, memory, sandbox, MCP servers, and more.

```
klaw status --deep
```

#### `--json`

Output as JSON format. Can be combined with `--deep` and/or `--usage`.

```
klaw status --json
klaw status --deep --json
```

#### `--usage`

Show LLM usage statistics per model (request counts, token usage). Stats are in-memory and reset on engine restart.

```
klaw status --usage
klaw status --usage --json
```

#### `--all`

Full diagnosis — combines `--deep` and `--usage`.

```
klaw status --all
klaw status --all --json
```

#### `--timeout <ms>`

Probe timeout in milliseconds (reserved for future deep probes).

```
klaw status --deep --timeout 5000
```

---

## Sessions

### `klaw sessions list`

Lists all active chat sessions.

```
klaw sessions list
```

#### `--active <minutes>`

Show only sessions active within the last N minutes.

```
klaw sessions list --active 60
```

#### `--json`

Output as JSON format.

```
klaw sessions list --json
```

#### `--verbose`

Show detailed info including token counts and timestamps.

```
klaw sessions list --verbose --json
```

### `klaw sessions cleanup`

Remove old/expired sessions and their associated messages.

```
klaw sessions cleanup
```

#### `--older-than <minutes>`

Remove sessions inactive for more than N minutes (default: 1440 = 24 hours).

```
klaw sessions cleanup --older-than 60
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
- `--inject-into CHAT_ID` — chatId for delivering results to a user

When using the LLM tool interface (`schedule_add`), `injectInto` and `channel` are auto-populated from the conversation context. The `schedule_add` tool also supports an `at` parameter for one-time triggers (mutually exclusive with cron), but the CLI currently only supports cron scheduling.

### `klaw schedule remove NAME`

Removes a scheduled job by name.

```
klaw schedule remove daily
```

### `klaw schedule edit NAME`

Edits an existing scheduled job. Updates only the specified fields; unspecified fields remain unchanged.

```
klaw schedule edit daily --cron "0 18 * * *"
klaw schedule edit daily --message "Good evening!"
klaw schedule edit daily --model deepseek-chat
klaw schedule edit daily --cron "0 18 * * *" --message "Good evening!" --model deepseek-chat
```

Options:
- `--cron EXPR` — new cron expression
- `--message TEXT` — new subagent instruction
- `--model MODEL` — new LLM model

At least one option must be provided.

### `klaw schedule enable NAME`

Resumes a paused (disabled) scheduled job.

```
klaw schedule enable daily
```

### `klaw schedule disable NAME`

Pauses a scheduled job. The job remains in the scheduler but will not fire until re-enabled. Disabled jobs show `[PAUSED]` in `schedule list`.

```
klaw schedule disable daily
```

### `klaw schedule run NAME`

Triggers a scheduled job to execute immediately, regardless of its schedule or pause state. Useful for debugging.

```
klaw schedule run daily
```

### `klaw schedule runs NAME`

Shows execution history for a scheduled job (from `subagent_runs` table).

```
klaw schedule runs daily
klaw schedule runs daily --limit 5
```

Options:
- `--limit N` — maximum number of runs to show (default: 20)

### `klaw schedule status`

Shows scheduler health: whether it's running, job count, and currently executing jobs.

```
klaw schedule status
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

### `klaw memory consolidate`

Triggers daily memory consolidation manually (delegated to Engine). Reviews conversation history and extracts important facts into long-term memory using an LLM with the `memory_save` tool.

```
klaw memory consolidate
klaw memory consolidate --date 2026-03-18
klaw memory consolidate --force
```

Options:
- `--date YYYY-MM-DD` — date to consolidate (default: yesterday)
- `--force` — re-consolidate even if already done for the given date

---

## Logs

### `klaw logs`

Shows the most recent conversation messages from JSONL files. Output is colorized by default (timestamps in cyan, chat IDs in green, roles in yellow/green).

```
klaw logs
klaw logs --limit 50
klaw logs --chat telegram_123456
klaw logs --follow
klaw logs --json
klaw logs --follow --interval 500
klaw logs --max-bytes 1000000
klaw logs --local-time
klaw logs --no-color
klaw logs --timeout 5000
```

Options:
- `--limit N` / `-n N` — number of recent messages to show (default: 50)
- `--chat CHAT_ID` — filter by chat ID
- `--follow` / `-f` — stream new messages as they arrive
- `--json` — output raw JSONL format (machine-readable, one JSON object per line)
- `--interval MS` — polling interval in milliseconds for follow mode (default: 1000)
- `--max-bytes N` — maximum bytes to read from log files
- `--local-time` — display timestamps in local timezone instead of UTC
- `--no-color` — disable ANSI color output
- `--timeout MS` — timeout in milliseconds for initial read; exits if no data found within the timeout

#### JSON output format

```json
{"id":"uuid","ts":"2026-03-22T12:00:00Z","role":"user","content":"Hello"}
{"id":"uuid","ts":"2026-03-22T12:00:05Z","role":"assistant","content":"Hi!"}
```

---

## Diagnostics

### `klaw doctor`

Checks the Klaw installation for common issues:

- `engine.json` exists
- `gateway.json` exists
- Workspace directory exists
- Engine TCP port reachable (Engine running/stopped)
- ONNX embedding model present
- sqlite-vec extension present
- Skills validation (when Engine is running)

```
klaw doctor
```

### `klaw doctor fix`

Automatically repairs common issues found by `klaw doctor`:

1. Creates missing directories (workspace, config, state, logs)
2. Sanitizes config JSON (removes unknown keys from `engine.json` / `gateway.json`)
3. Fixes `docker-compose.json` structure (adds missing mounts, env vars, port mappings)
4. Starts the engine if it is not running

```
klaw doctor fix
```

---

## Update

### `klaw update`

Checks for CLI updates from GitHub releases and optionally installs them.

```
klaw update
klaw update --check
klaw update --version v0.2.0
```

Options:
- `--check` — check for updates without installing
- `--version TAG` — install a specific version (e.g., `v0.2.0`)

---

## Reindex

### `klaw reindex`

Triggers full conversation re-indexing in the Engine (rebuilds SQLite from JSONL).

```
klaw reindex
```

---

## Global Options

| Flag | Description |
|------|-------------|
| `-v`, `--verbose` | Enable DEBUG-level CLI logging (default: INFO). Logs are written to `~/.local/state/klaw/logs/cli.log`. Falls back to `/tmp/klaw/cli.log` if the logs directory does not yet exist. |

---

## Notes

- Commands requiring the Engine (`status`, `schedule`, `memory search`, `memory consolidate`, `reindex`) print a
  helpful error if the Engine is not running.
- All config files are in `~/.config/klaw/` (XDG-compliant, overridable via `XDG_CONFIG_HOME`).
- The `.env` file is created with `0600` permissions — never world-readable.
- CLI logs are written to `~/.local/state/klaw/logs/cli.log`. Use `-v` for verbose (DEBUG) output.
