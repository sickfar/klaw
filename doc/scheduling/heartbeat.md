# HEARTBEAT.md

## What is HEARTBEAT.md

`HEARTBEAT.md` is a workspace file at `$KLAW_WORKSPACE/HEARTBEAT.md`. It provides instructions for the periodic heartbeat — an autonomous LLM run that reads the file, has full tool access, and decides whether to deliver results to the user.

The heartbeat is configured via the `heartbeat` section in `engine.json`. The file is **read-only** — Klaw never modifies it. This is intentional for OpenClaw compatibility (the file can be version-controlled with the workspace).

---

## How it works

1. The engine reads `HEARTBEAT.md` at each heartbeat interval.
2. The file content is sent as a user message to an LLM session (chatId `"heartbeat"`) with the full system prompt and all standard tools.
3. An additional tool `heartbeat_deliver` is available — the LLM calls it when it has information worth sharing with the user.
4. If the LLM calls `heartbeat_deliver` and `injectInto`/`channel` are configured, the message is pushed to the user via Gateway.

The heartbeat LLM has access to all standard tools (file, memory, docs, schedule, subagent, sandbox, etc.) plus `heartbeat_deliver`. This means it can read files, search memory, run code, and make autonomous decisions about what to report.

---

## Configuration

The heartbeat is configured in `engine.json`:

```json
{
  "heartbeat": {
    "interval": "PT1H",
    "model": "zai/glm-5",
    "injectInto": "telegram_123456",
    "channel": "telegram"
  }
}
```

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `interval` | no | `"off"` | ISO-8601 duration (`"PT1H"`, `"PT30M"`) or `"off"` to disable. |
| `model` | no | `routing.default` | LLM model for heartbeat runs. |
| `injectInto` | no | `null` | chatId for delivering results (e.g. `"telegram_123456"`). |
| `channel` | no | `null` | Channel for delivery (e.g. `"telegram"`). |

Both `injectInto` and `channel` must be set for delivery to work. If either is missing, the heartbeat run is **skipped entirely** — no LLM tokens are consumed.

### Setup via `klaw init`

When you run `klaw init`, the wizard automatically sets `channel` based on the first configured messaging channel (e.g. `"telegram"`). The `interval` defaults to `"PT1H"` when a channel is available, or `"off"` otherwise.

However, `injectInto` (the chatId) is **not** set during init — it is unknown until the first message arrives from a user. See the pairing section below.

### Pairing: `/heartbeat`

To complete heartbeat setup, send `/heartbeat` in any chat channel. This command:

1. Sets the current channel and chatId as the heartbeat delivery target.
2. Persists the change to `engine.json` on disk.
3. Takes effect on the next heartbeat run.

This is required because the chatId is only known after the first message arrives from a user on a channel. Until pairing, heartbeat runs are skipped (no delivery target configured).

---

## Creating HEARTBEAT.md

`HEARTBEAT.md` is **not** created by `klaw init`. Create it manually in your workspace when you are ready to use the heartbeat feature:

```bash
cat > ~/.local/share/klaw/workspace/HEARTBEAT.md << 'EOF'
# Your heartbeat instructions here
EOF
```

If `HEARTBEAT.md` is missing or blank, the heartbeat run is skipped silently.

---

## Format

`HEARTBEAT.md` is free-form Markdown. There is no required structure — write whatever instructions you want the heartbeat LLM to follow.

**Example:**

```markdown
# Heartbeat Instructions

Check the following and report only if something requires attention:

1. Read the latest log entries in $STATE/logs/engine.log for errors
2. Check if any scheduled tasks have failed recently
3. Review memory for any pending reminders due today

If nothing noteworthy, do not call heartbeat_deliver.
If something needs attention, call heartbeat_deliver with a concise summary.
```

---

## The `heartbeat_deliver` tool

Available only during heartbeat runs. The LLM calls this tool when it has information worth sharing:

- **name:** `heartbeat_deliver`
- **parameters:** `message` (string, required) — the text to deliver to the user
- **behavior:** Queues the message for delivery. Only the last call's message is delivered (subsequent calls overwrite previous ones).

If the LLM does not call `heartbeat_deliver`, no message is sent — the heartbeat completes silently.

---

## Concurrency

Only one heartbeat run executes at a time. If a heartbeat is still running when the next interval fires, the new run is skipped.

---

## HEARTBEAT.md vs `schedule_add`

| | Heartbeat | `schedule_add` |
|---|---|---|
| Trigger | Fixed interval (ISO-8601 duration) | Cron expression or one-time datetime |
| Context | Full tool access, autonomous LLM | Subagent with task message |
| Decision-making | LLM decides what to report | Always delivers result |
| Configuration | `engine.json` `heartbeat` section | Runtime API or CLI |

Use heartbeat for periodic autonomous monitoring with intelligent filtering.
Use `schedule_add` for specific recurring or one-time tasks.

---

## See also

- `doc/scheduling/how-scheduling-works.md` — execution model for scheduled tasks
- `doc/scheduling/cron-format.md` — cron syntax (for `schedule_add`)
- `doc/tools/schedule.md` — schedule tool reference
- `doc/config/engine-json.md` — engine configuration reference
