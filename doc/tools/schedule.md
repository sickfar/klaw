# Schedule Tools

Schedule tools manage persistent scheduled tasks via the Quartz scheduler. Tasks can be recurring (cron) or one-time (fixed datetime). All tasks survive engine restarts.

---

## `schedule_list`

No parameters. Returns all active scheduled tasks.

**Returns:** For each task: name, schedule (cron expression or one-time datetime), message, model (if set), inject_into (if set), channel (if set), next fire time, previous fire time.

**Example output:**
```
- Morning Check
  Cron: 0 0 9 * * ?
  Message: Check email and report important messages
  Model: zai/glm-5
  InjectInto: telegram_123456
  Channel: telegram
  Next: Mon Feb 27 09:00:00 UTC 2026

- One-Time Reminder
  At: 2026-03-15T14:00:00Z
  Message: Send quarterly report
  Channel: telegram
```

---

## `schedule_add`

Adds a new scheduled or one-time task.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Unique task name. Duplicate names return an error. |
| `cron` | string | no | Quartz 7-field cron expression. Mutually exclusive with `at`. See `doc/scheduling/cron-format.md`. |
| `at` | string | no | ISO-8601 datetime for a one-time trigger (e.g. `2026-03-15T14:00:00Z`). Mutually exclusive with `cron`. |
| `message` | string | yes | Instruction sent to the subagent when the task fires. |
| `model` | string | no | LLM model to use. Defaults to `routing.tasks.subagent` from `engine.json`. |

Exactly one of `cron` or `at` must be provided. Providing both or neither returns an error.

**`injectInto` and `channel`** are not exposed as parameters — they are automatically populated from the current chat context. When a user asks the LLM to schedule a task, the result will be delivered back to that same user's chat.

**Returns:**
- Cron: `"OK: 'name' scheduled with cron 'expression'"`
- One-time: `"OK: 'name' scheduled at 'datetime'"`
- Error: descriptive error message

**Notes:**
- Tasks persist in `scheduler.db` across engine restarts.
- Duplicate names are rejected. Remove the existing task first.
- One-time tasks are automatically removed from the scheduler after they fire.
- Use cheaper or local models (e.g. `ollama/qwen3:8b`) for routine checks to save cost.

---

## `schedule_remove`

Permanently removes a scheduled task.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Name of the task to remove. |

**Returns:** `"OK: 'name' removed"` on success, or `"Error: schedule 'name' not found"`.

---

## `schedule_deliver`

Used by scheduled subagents to deliver a result to the user. This is the **only** way for a scheduled task to send output back. If the LLM does not call `schedule_deliver`, nothing is sent — silence is the default.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `message` | string | yes | Message text to deliver to the user. |

**Only available when `injectInto` is set on the task.** Background tasks (no delivery target) do not receive this tool.

---

## Result delivery (`injectInto` and `channel`)

When `schedule_add` is called via the LLM tool interface, `injectInto` (chatId) and `channel` (e.g. `telegram`, `discord`) are automatically set from the current chat context. This means the scheduled task's result will be delivered back to the user who created it, on the same platform.

- If `injectInto` is set: the `schedule_deliver` tool is available to the subagent. Delivery only occurs if the LLM explicitly calls it.
- If `injectInto` is `null` (e.g. tasks created via CLI without specifying a target): result is logged only, `schedule_deliver` is not available.
- Legacy jobs without a stored `channel` fall back to `"engine"` for backwards compatibility.

**Reminder delivery pattern** — when the user wants to receive a specific text at the scheduled time:
```
Your task: send the user this reminder: Buy milk. Call schedule_deliver with that message.
```

**Conditional delivery pattern** — when the task should only notify if something is found:
```
Check email and report urgent messages. If there are urgent messages, call schedule_deliver with a summary. If nothing requires attention, complete without calling schedule_deliver.
```

---

## Model selection

If `model` is omitted, the task uses `routing.tasks.subagent` from `engine.json` (default: `zai/glm-5`).

Recommendations:
- **Heavy analysis** (weekly reports, complex summaries): `zai/glm-5` or `deepseek-chat`
- **Routine checks** (email scan, weather check): `ollama/qwen3:8b` (local, free)
- **Quick status** (uptime ping, file check): `ollama/qwen3:4b`

---

## See also

- `doc/scheduling/how-scheduling-works.md` — architecture and subagent execution model
- `doc/scheduling/heartbeat.md` — defining tasks in `HEARTBEAT.md`
- `doc/scheduling/cron-format.md` — cron expression syntax and examples
