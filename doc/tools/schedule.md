# Schedule Tools

Schedule tools manage persistent cron tasks via the Quartz scheduler. Tasks survive engine restarts.

---

## `schedule_list`

No parameters. Returns all active scheduled tasks.

**Returns:** For each task: name, cron expression, message, model (if set), inject_into (if set), next fire time, previous fire time.

**Example output:**
```
- Morning Check
  Cron: 0 0 9 * * ?
  Message: Check email and report important messages
  Model: glm/glm-4-plus
  InjectInto: telegram_123456
  Next: Mon Feb 27 09:00:00 UTC 2026
```

---

## `schedule_add`

Adds a new persistent cron task.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Unique task name. Duplicate names return an error. |
| `cron` | string | yes | Quartz 7-field cron expression. See `doc/scheduling/cron-format.md`. |
| `message` | string | yes | Instruction sent to the subagent when the task fires. |
| `model` | string | no | LLM model to use. Defaults to `routing.tasks.subagent` from `engine.json`. |
| `injectInto` | string | no | chatId (e.g. `"telegram_123456"`). If set, delivers result to the user via Gateway. |

**Returns:** `"OK: 'name' scheduled with cron 'expression'"` on success, or an error message.

**Notes:**
- Tasks persist in `scheduler.db` across engine restarts.
- Duplicate names are rejected. Remove the existing task first.
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

## `injectInto` explained

- If `injectInto` is set to a chatId (e.g. `"telegram_123456"`), the subagent result is sent to that user via Gateway.
- If the subagent result JSON contains `{"silent": true}`, it is logged but **not** sent to the user.
- If `injectInto` is `null`, the result is only logged — no user notification.

**Silent pattern** — include in the `message` field when the task should only notify if something is found:
```
Check email and report urgent messages. If nothing requires attention, respond with JSON: {"silent": true, "reason": "what was checked"}
```

---

## Model selection

If `model` is omitted, the task uses `routing.tasks.subagent` from `engine.json` (default: `glm/glm-4-plus`).

Recommendations:
- **Heavy analysis** (weekly reports, complex summaries): `glm/glm-4-plus` or `deepseek-chat`
- **Routine checks** (email scan, weather check): `ollama/qwen3:8b` (local, free)
- **Quick status** (uptime ping, file check): `ollama/qwen3:4b`

---

## See also

- `doc/scheduling/how-scheduling-works.md` — architecture and subagent execution model
- `doc/scheduling/heartbeat.md` — defining tasks in `HEARTBEAT.md`
- `doc/scheduling/cron-format.md` — cron expression syntax and examples
