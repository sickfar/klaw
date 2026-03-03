# How Scheduling Works

## Architecture

Quartz runs **inside the Engine process** — not as a separate service. Scheduled tasks are stored in `scheduler.db` (SQLite) and survive engine restarts.

```
Engine process
├── MessageProcessor      ← handles user messages
├── KlawSchedulerImpl     ← Quartz wrapper
│   └── QuartzKlawScheduler
│       └── Quartz JobStoreTX → scheduler.db
└── ScheduledMessageJob   ← fired by Quartz, calls MessageProcessor directly
```

No additional processes or daemons are needed. Quartz uses 2 background threads for job execution.

---

## What happens when a task fires

1. Quartz triggers `ScheduledMessageJob` on a background thread.
2. Engine spawns a subagent coroutine with isolated context (separate from the main chat session).
3. The subagent runs the configured `message` as an LLM call with the configured `model`.
4. The result is optionally delivered to a user via `injectInto` on the stored `channel` (e.g. `telegram`, `discord`).

The call is **in-process** — no IPC, no socket, no network hop.

For one-time tasks (created with `at` instead of `cron`), the job fires once and is automatically removed from the scheduler.

---

## Subagent context for scheduled tasks

A scheduled subagent receives:
- Shared system prompt (same as main agent)
- Shared system prompt (including USER.md)
- Last 5 messages from its own scheduler channel log

A scheduled subagent does **not** see:
- The main chat's recent messages
- Other conversation sessions

This is intentional. Heartbeat tasks should not depend on conversation state — they run independently and report their findings.

---

## Delivering results to the user

If `injectInto` is set:
1. The subagent completes its LLM run.
2. Engine checks the result for `{"silent": true}`.
3. If not silent: Engine sends the result to Gateway on the stored `channel` (e.g. `telegram`) → user receives it in their chat.
4. If silent: result is logged only. No notification.

If `injectInto` is `null`: result is logged only.

When tasks are created via the LLM tool interface (`schedule_add`), `injectInto` and `channel` are automatically populated from the current chat context — the LLM does not need to specify them.

---

## Misfire recovery

**Cron tasks:** If Engine was stopped and missed scheduled fires, Quartz applies the **FireAndProceed** policy on startup:
- The task fires **once**, regardless of how many triggers were missed.
- It does not replay all missed triggers (no flooding on long downtime).

**One-time tasks:** The **FireNow** policy applies — if the engine was down when the task was due, it fires immediately on startup.

The misfire threshold is 60 seconds. Tasks missed by less than 60 seconds are not considered misfired.

---

## Managing tasks

| Method | Description |
|--------|-------------|
| `schedule_add` tool | Add a task at runtime |
| `schedule_remove` tool | Remove a task at runtime |
| `schedule_list` tool | List all active tasks |
| `klaw schedule add` CLI | Add a task from the command line |
| `HEARTBEAT.md` | Declare tasks in workspace; imported at startup |

Changes via `schedule_add`/`schedule_remove` take effect immediately. Changes to `HEARTBEAT.md` take effect on next engine restart.

---

## See also

- `doc/scheduling/heartbeat.md` — HEARTBEAT.md format
- `doc/scheduling/cron-format.md` — cron expression syntax
- `doc/tools/schedule.md` — schedule tool reference
