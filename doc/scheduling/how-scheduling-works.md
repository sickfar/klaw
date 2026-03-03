# How Scheduling Works

## Architecture

The engine has two scheduling mechanisms:

1. **Quartz scheduler** — for cron-based and one-time tasks created via `schedule_add` or CLI. Stored in `scheduler.db` (SQLite), survives restarts.
2. **Heartbeat runner** — for periodic autonomous LLM monitoring via `HEARTBEAT.md`. Configured in `engine.json`, uses Micronaut's built-in `TaskScheduler`.

```
Engine process
├── MessageProcessor       ← handles user messages
├── KlawSchedulerImpl      ← Quartz wrapper (cron/one-time tasks)
│   └── QuartzKlawScheduler
│       └── Quartz JobStoreTX → scheduler.db
├── ScheduledMessageJob    ← fired by Quartz, calls MessageProcessor directly
└── HeartbeatRunnerFactory ← periodic autonomous LLM run (reads HEARTBEAT.md)
    └── HeartbeatRunner
```

No additional processes or daemons are needed. Quartz uses 2 background threads for job execution. The heartbeat runs on Micronaut's scheduled executor.

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
- Shared system prompt (same as main agent, including USER.md)
- A `## Scheduled Task Execution` notice appended to the system prompt — informs the LLM that it is running as a named scheduled task and should call `schedule_deliver` to send results to the user, or complete silently if nothing needs to be delivered
- Last 5 messages from its own scheduler channel log
- The `schedule_deliver` tool (only if `injectInto` is set on the task)

A scheduled subagent does **not** see:
- The main chat's recent messages
- Other conversation sessions

This is intentional. Scheduled tasks should not depend on conversation state — they run independently and report their findings.

---

## Delivering results to the user

If `injectInto` is set:
1. The subagent runs with `schedule_deliver` available in its tool list.
2. If the LLM calls `schedule_deliver(message=...)`: Engine sends that message to Gateway on the stored `channel` (e.g. `telegram`) → user receives it in their chat. The message is also saved as an `assistant` message in the interactive session (`injectInto` chatId) so the user can follow up naturally.
3. If the LLM does **not** call `schedule_deliver`: nothing is sent. Silence is the default — no escape-hatch JSON required.

If `injectInto` is `null`: `schedule_deliver` is not included in the tool list and the result is logged only.

When tasks are created via the LLM tool interface (`schedule_add`), `injectInto` and `channel` are automatically populated from the current chat context — the LLM does not need to specify them.

---

## Misfire recovery

**Cron tasks:** If Engine was stopped and missed scheduled fires, Quartz applies the **FireAndProceed** policy on startup:
- The task fires **once**, regardless of how many triggers were missed.
- It does not replay all missed triggers (no flooding on long downtime).

**One-time tasks:** The **FireNow** policy applies — if the engine was down when the task was due, it fires immediately on startup.

The misfire threshold is 60 seconds. Tasks missed by less than 60 seconds are not considered misfired.

---

## Heartbeat

The heartbeat is a separate scheduling mechanism from Quartz. See `doc/scheduling/heartbeat.md` for full details.

Key differences from Quartz-scheduled tasks:
- Runs at a fixed interval (ISO-8601 duration), not cron
- Has full tool access (all standard tools + `heartbeat_deliver`)
- The LLM autonomously decides whether to deliver results
- Configured in `engine.json` (`heartbeat` section), not `scheduler.db`
- Skipped entirely if `HEARTBEAT.md` is missing/blank or no delivery target is configured
- Delivery target is set via `/use-for-heartbeat` command (pairs current chat for delivery)

---

## Managing tasks

| Method | Description |
|--------|-------------|
| `schedule_add` tool | Add a cron or one-time task at runtime |
| `schedule_remove` tool | Remove a task at runtime |
| `schedule_list` tool | List all active Quartz tasks |
| `klaw schedule add` CLI | Add a task from the command line |
| `engine.json` `heartbeat` | Configure periodic autonomous monitoring |

Changes via `schedule_add`/`schedule_remove` take effect immediately. Changes to `engine.json` `heartbeat.interval` and `heartbeat.model` take effect on next engine restart. The `/use-for-heartbeat` command updates the delivery target at runtime (no restart needed).

---

## See also

- `doc/scheduling/heartbeat.md` — HEARTBEAT.md format
- `doc/scheduling/cron-format.md` — cron expression syntax
- `doc/tools/schedule.md` — schedule tool reference
