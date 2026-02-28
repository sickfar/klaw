# HEARTBEAT.md

## What is HEARTBEAT.md

`HEARTBEAT.md` is a workspace file at `$KLAW_WORKSPACE/HEARTBEAT.md`. It defines recurring scheduled tasks in a declarative format.

On engine startup, Klaw parses the file and imports all valid tasks into the Quartz scheduler. The file is **read-only** — Klaw never modifies it. This is intentional for OpenClaw compatibility (the file can be version-controlled with the workspace).

---

## Format

Each task is a level-2 Markdown heading followed by bullet-point fields:

```markdown
## Task Name
- Cron: 0 0 9 * * ?
- Message: Check email and report important messages
- Model: glm/glm-4-plus
- InjectInto: telegram_123456
```

**Fields:**

| Field | Required | Description |
|-------|----------|-------------|
| `Cron` | yes | Quartz 7-field cron expression. See `doc/scheduling/cron-format.md`. |
| `Message` | yes | Instruction sent to the subagent when the task fires. |
| `Model` | no | LLM model. Defaults to `routing.tasks.subagent` from `engine.json`. |
| `InjectInto` | no | chatId for delivering results to a user (e.g. `telegram_123456`). |

Tasks missing `Cron` or `Message` are silently skipped.

---

## Multiple tasks

Separate tasks with blank lines or any non-`##` content between them:

```markdown
# Recurring Tasks

## Morning Check
- Cron: 0 0 9 * * ?
- Message: Check email and summarize urgent items

## Weekly Report
- Cron: 0 0 9 ? * MON
- Message: Generate weekly activity summary
- Model: glm/glm-4-plus
- InjectInto: telegram_123456
```

Content before the first `##` heading is ignored (use it for comments or description).

---

## Import behavior

- Parsed **once** at startup.
- Changes to the file do **not** take effect until engine restart.
- To add a task immediately without restart: use `schedule_add` tool.
- If a task name from `HEARTBEAT.md` already exists in `scheduler.db` (e.g. from a previous restart), the import logs a warning and skips it — the existing job is kept.

---

## HEARTBEAT.md vs `schedule_add`

Both produce identical Quartz jobs. The difference is lifecycle:

| | `HEARTBEAT.md` | `schedule_add` |
|---|---|---|
| Source | Workspace file (version-controlled) | Runtime API |
| Persistence | Imported at startup | Immediate |
| Survives file edit | No (requires restart) | Yes |
| Survives engine restart | Yes (re-imported) | Yes (stored in `scheduler.db`) |

For tasks you want permanently defined with the workspace: use `HEARTBEAT.md`.
For tasks added dynamically at runtime: use `schedule_add`.

---

## Updating a task at runtime

To update parameters of a task originally from `HEARTBEAT.md`:

```
schedule_remove("Morning Check")
schedule_add("Morning Check", "0 0 10 * * ?", "Check email at 10 AM", ...)
```

Or: edit `HEARTBEAT.md` and restart the engine (the updated version is re-imported).

---

## Silent pattern

When the task should only notify the user if something requires attention:

```markdown
## Email Check
- Cron: 0 0/2 8-20 * * ?
- Message: Check email for urgent messages. If nothing requires attention, respond with JSON: {"silent": true, "reason": "no urgent emails found"}
- InjectInto: telegram_123456
```

The subagent returns `{"silent": true, ...}` → Engine logs the result but does not send a message to the user.

---

## See also

- `doc/scheduling/how-scheduling-works.md` — execution model
- `doc/scheduling/cron-format.md` — cron syntax
- `doc/tools/schedule.md` — schedule tool reference
