# Slash Commands

## How commands work

Slash commands arrive as `type: "command"` messages from the Gateway. The Engine handles them directly — the LLM is not invoked. The agent only sees the resulting state change (e.g. a new session segment after `/new`), not the command itself.

## /new

Resets the sliding window and last summary. Starts a new conversation segment.

What is preserved: archival memory, the current model, and the full conversation log on disk.
What changes: the segment boundary moves to now; the LLM no longer sees messages before this point.

## /model

- Without argument: shows the current session model
- With argument `provider/model-id` (e.g. `/model deepseek/deepseek-chat`): switches the model for this session

The model must be defined in `engine.json` under `models:`. Use `/models` to list available options.

## /models

Lists all models configured in `engine.json` with their `contextBudget` values. Useful before recommending a model switch.

## /memory

Displays the contents of `MEMORY.md` from the workspace.

## /status

Shows: uptime, current chat model, segment start timestamp, and LLM queue depth.

## /use-for-heartbeat

Sets the current chat as the delivery target for heartbeat results. The command persists the `channel` and `injectInto` fields to `engine.json` so the setting survives restarts.

Requires heartbeat to be enabled (`interval` set to an ISO-8601 duration, not `"off"`). If heartbeat is disabled, the command returns an error with instructions to enable it first.

## /help

Lists all configured commands with descriptions, as defined in `engine.json` under `commands:`.
