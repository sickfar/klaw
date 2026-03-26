# Migrating from OpenClaw

This guide covers migrating from [OpenClaw](https://openclaw.ai/) to Klaw.

## Compatibility Overview

| Component | Status |
|-----------|--------|
| Workspace files (SOUL.md, IDENTITY.md, USER.md, MEMORY.md, AGENTS.md, TOOLS.md) | Fully compatible |
| HEARTBEAT.md | Compatible (read-only in both) |
| Daily memory (memory/*.md) | Fully compatible |
| LLM providers (z.ai, Anthropic, OpenAI-compatible) | Fully compatible |
| Telegram channel | Compatible (same bot token) |
| Discord channel | Compatible (same bot token) |
| Brave Search | Compatible (same API key) |
| Web fetch | Built-in in both |
| Scheduled tasks (cron jobs) | Import tool available |

### Not migrated

- **Headless browser** — Klaw uses `web_fetch` instead of a full browser
- **Audio/STT** (Deepgram) — not supported in Klaw
- **Node.js skills** — rewrite as Klaw Markdown skills
- **Chat history** — starts fresh; MEMORY.md preserves context

## Prerequisites

- Klaw binaries installed (see [installation guide](installing.md))
- Access to OpenClaw config directory (`~/.openclaw/`)
- Access to OpenClaw workspace directory

## Step 1: Initialize Klaw with existing workspace

Point `klaw init` at your existing OpenClaw workspace:

```bash
klaw init --workspace ~/clawd
```

Or during the interactive wizard, enter your workspace path when prompted:

```
Workspace directory [~/klaw-workspace]: ~/clawd
```

If the workspace already contains `SOUL.md` and `IDENTITY.md`, Klaw skips identity generation and uses existing files.

The wizard will then ask for:
- Deployment mode (native or Docker)
- LLM provider and API key
- Telegram bot token
- Discord bot token (optional)
- Web search configuration

## Step 2: Configure channels

Use `klaw configure` to set up channels if not done during init:

```bash
klaw configure telegram    # Telegram bot token + allowed chats
klaw configure discord     # Discord bot token + allowed guilds
klaw configure web-search  # Brave/Tavily API key
```

## Step 3: Import scheduled tasks

Import cron jobs from OpenClaw:

```bash
klaw schedule import --from-openclaw ~/.openclaw/cron/jobs.json
```

This reads `jobs.json`, converts OpenClaw cron format to Klaw/Quartz format, and creates schedule entries.

Options:
- `--all` — import all jobs including disabled ones (default: enabled only)

Verify imported jobs:

```bash
klaw schedule list
```

### Cron format differences

OpenClaw uses 5-field standard cron (`MIN HOUR DOM MON DOW`). Klaw uses 6-field Quartz cron (`SEC MIN HOUR DOM MON DOW`). The import tool handles this conversion automatically.

### What's converted

| OpenClaw | Klaw |
|----------|------|
| `schedule.kind: "cron"` + `expr` | `cron` parameter (Quartz format) |
| `schedule.kind: "at"` | `at` parameter (ISO-8601) |
| `payload.text` / `payload.message` | `message` parameter |
| `payload.model` | `model` parameter |
| `delivery.channel` + `delivery.to` | `injectInto` + `channel` |

### What's not converted

- `wakeMode: "next-heartbeat"` — Klaw executes scheduled tasks immediately
- `deleteAfterRun` — remove manually after execution with `klaw schedule remove`
- `sessionTarget: "main"` vs `"isolated"` — Klaw scheduled tasks run as subagents

## Step 4: Adapt workspace files

Some workspace files may need manual editing:

### AGENTS.md
Remove OpenClaw-specific instructions and update for Klaw tool names.

### TOOLS.md
Update tool references, skill paths, and any OpenClaw-specific environment notes.

### HEARTBEAT.md
Update script paths and tool references for Klaw.

### Skills
OpenClaw Node.js skills need to be rewritten as Klaw Markdown skills. Place them in `$KLAW_WORKSPACE/skills/<name>/SKILL.md`.

## Step 5: Verify

```bash
klaw status          # Check Engine health
klaw schedule list   # Verify imported jobs
klaw schedule status # Scheduler health
```

Send a test message via Telegram to verify the agent responds.

## Rollback

OpenClaw is not modified during migration. To rollback:
1. Stop Klaw services
2. Restart OpenClaw
