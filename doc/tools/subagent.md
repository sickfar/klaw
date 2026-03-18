# Subagent Tools

## subagent_spawn

Spawn a tracked subagent to execute a task asynchronously.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `name` | string | yes | Subagent name (used for tracking and logging) |
| `message` | string | yes | Task description for the subagent |
| `model` | string | no | LLM model override |
| `injectInto` | string | no | chatId to send the result to |

**Returns:** JSON with run tracking info:
```json
{"id": "<run-id>", "name": "<name>", "status": "RUNNING"}
```

Use the returned `id` with `subagent_status` or `subagent_cancel`.

## subagent_status

Get the status of a subagent run by ID. Only returns runs spawned from the current session.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | string | yes | Run ID (returned by `subagent_spawn`) |

**Returns:** JSON with run details:
```json
{
  "id": "<run-id>",
  "name": "task-name",
  "status": "COMPLETED",
  "model": "test/model",
  "start_time": "2026-03-18T12:00:00Z",
  "end_time": "2026-03-18T12:02:34Z",
  "duration_ms": 154000,
  "result": "Delivered message text",
  "last_response": "Final LLM response",
  "error": null
}
```

Or `"Subagent run not found: <id>"` if the ID doesn't exist or belongs to another session.

## subagent_list

List recent subagent runs spawned from the current session.

**Parameters:** None.

**Returns:** JSON array of recent runs (up to 20), ordered by start time descending.

## subagent_cancel

Cancel a running subagent by ID. Only works for subagents spawned from the current session.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | string | yes | Run ID to cancel |

**Returns:** Confirmation or error message.

## Status Lifecycle

```
RUNNING → COMPLETED  (successful execution)
RUNNING → FAILED     (error, timeout, or LLM failure)
RUNNING → CANCELLED  (cancelled via subagent_cancel)
```

## Behavior

- Each subagent runs in a separate coroutine with its own LLM session.
- It has access to the same tools as the parent agent.
- If `injectInto` is set, the `schedule_deliver` tool is available. The subagent must call it explicitly to send a result — silence is the default.
- If `injectInto` is `null`, no delivery tool is available and the subagent's output is logged only.
- **Session scoping:** Each session can only see and cancel its own subagents. Session A cannot query or cancel session B's subagents.
- **Unique sessions:** Each spawn creates a unique session (`subagent:<name>:<runId>`) to prevent conflicts between parallel runs with the same name.

## Push Notifications

When a subagent completes or fails, the engine sends a notification to the spawning session:
- `[Subagent 'task-name' completed]`
- `[Subagent 'task-name' failed: ProviderError(status=429)]`

No notification is sent on cancel (intentional action).

## Timeout

Subagents have a configurable execution timeout (default: 5 minutes). Configure via `processing.subagentTimeoutMs` in `engine.json`. When a subagent exceeds the timeout, it is marked as FAILED with error `"Timeout"`.

## Result Fields

- `result` — the message delivered via `schedule_deliver` (if the subagent called it)
- `last_response` — the final LLM assistant response (always present on completion)

Both fields are truncated to 2000 characters.

## Error Info

The `error` field contains structured, safe information (no user content):
- `ProviderError(status=429)` — LLM API error with HTTP status
- `AllProvidersFailedError` — all LLM providers unreachable
- `ContextLengthExceededError` — message too long for model
- `Timeout` — execution exceeded time limit
- `EngineCrash` — engine restarted while subagent was running

## Retention

The engine retains the last 200 subagent runs. Older runs are automatically pruned.

On engine startup, any runs left in RUNNING state (from a previous crash) are marked as FAILED with error `"EngineCrash"`.

## Comparison with schedule_add

| Aspect | subagent_spawn | schedule_add |
|--------|---------------|--------------|
| Execution | Immediate, one-shot | Recurring via cron |
| Persistence | Tracked in `subagent_runs` table | Stored in scheduler.db |
| Status tracking | Yes (`subagent_status`/`subagent_list`) | No |
| Cancellation | Yes (`subagent_cancel`) | `schedule_remove` |
| Use case | Delegating a subtask now | Repeating tasks on schedule |
