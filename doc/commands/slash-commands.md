# Slash Commands

## How commands work

Slash commands are handled entirely by the Engine — the LLM is not invoked. The agent only sees the resulting state change (e.g. a new session segment after `/new`), not the command itself.

### Architecture

The Engine is the single source of truth for all slash commands. Each built-in command is a Micronaut bean implementing `EngineSlashCommand`. Commands are collected into `EngineCommandRegistry`, which provides lookup by name and introspection of all available commands.

Commands arrive as `type: "command"` messages from the Gateway. The `CommandHandler` dispatches them to the appropriate `EngineSlashCommand` bean via the registry.

### Command registration in channels

The Gateway periodically syncs the command list from the Engine (every 60 seconds) and registers them natively in each channel:
- **Telegram** — `setMyCommands` API call (commands appear in the bot menu)
- **Discord** — application commands registration

Sync uses change detection by command name — commands are only re-registered when the list changes. This ensures commands are available even if the Engine starts after the Gateway.

### Custom commands

Additional commands can be defined in `engine.json` under `commands[]`:

```json
{
  "commands": [
    { "name": "mycommand", "description": "My custom command" }
  ]
}
```

Custom commands are forwarded to the LLM as user messages (unlike built-in commands which are handled directly). They appear in channel menus alongside built-in commands.

## /new

Resets the sliding window and last summary. Starts a new conversation segment.

What is preserved: archival memory, the current model, and the full conversation log on disk.
What changes: the segment boundary moves to now; the LLM no longer sees messages before this point.

## /model

- Without argument: shows the current session model
- With argument `provider/model-id` (e.g. `/model deepseek/deepseek-chat`): switches the model for this session

The model must be defined in `engine.json` under `models:`. Use `/models` to list available options.

## /models

Lists all models configured in `engine.json` with their context length from the model registry. Useful before recommending a model switch.

## /memory

Shows the Memory Map — top categories from long-term memory with entry counts. Use this to see what topics are stored in memory, then use `memory_search` to retrieve details.

## /context

Shows detailed context window diagnostics for the current session: budget breakdown (system prompt, summaries, tools, pending, overhead, message budget), message window statistics, summary coverage, Auto-RAG status, tool list, skill configuration, and compaction state.

This is the in-chat equivalent of `klaw context` CLI command.

## /status

Shows: uptime, current chat model, segment start timestamp, and LLM queue depth.

## /skills list

Shows all currently loaded skills with their source (bundled, data, workspace). Forces a fresh re-scan of skill directories before displaying results, so newly added skills appear immediately.

Output example:

```
Loaded skills (5):
- configuration: Runtime configuration management (bundled)
- data-skill: Data analysis helper (data)
- memory-management: Memory category management (bundled)
- scheduling: Schedule management (bundled)
- ws-alpha: Alpha workspace skill (workspace)
```

## /skills validate

Validates all skill directories and reports their status. Checks each skill directory in both global (`~/.local/share/klaw/skills/`) and workspace (`$KLAW_WORKSPACE/skills/`) locations for:

- Presence of `SKILL.md` file
- Valid YAML frontmatter (opening `---` delimiter)
- Required `name` field
- Required `description` field

Output example:

```
✓ graph-rag: valid (workspace)
✓ weather: valid (data)
✗ broken-skill: missing frontmatter (workspace)
✗ incomplete: missing required field 'description' (data)

4 skills checked, 2 errors
```

## /heartbeat

Sets the current chat as the delivery target for heartbeat results. The command persists the `channel` and `injectInto` fields to `engine.json` so the setting survives restarts.

Requires heartbeat to be enabled (`interval` set to an ISO-8601 duration, not `"off"`). If heartbeat is disabled, the command returns an error with instructions to enable it first.

## /help

Lists all configured commands with descriptions, as defined in `engine.json` under `commands:`.
