# Subagent Tools

## subagent_spawn

Spawn a fire-and-forget subagent to execute a task asynchronously.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `name` | string | yes | Subagent name (used for logging) |
| `message` | string | yes | Task description for the subagent |
| `model` | string | no | LLM model override |
| `injectInto` | string | no | chatId to send the result to |

**Returns:** Confirmation that the subagent was spawned (does not wait for completion).

## Behavior

- The subagent runs in a separate coroutine with its own LLM session.
- It has access to the same tools as the parent agent.
- If `injectInto` is set, the `schedule_deliver` tool is available to the subagent. The subagent must call it explicitly to send a result — silence is the default.
- If `injectInto` is `null`, no delivery tool is available and the subagent's output is logged only.

## Comparison with schedule_add

| Aspect | subagent_spawn | schedule_add |
|--------|---------------|--------------|
| Execution | Immediate, one-shot | Recurring via cron |
| Persistence | Not persisted | Stored in scheduler.db |
| Use case | Delegating a subtask now | Repeating tasks on schedule |
