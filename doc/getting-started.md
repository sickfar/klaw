# Getting Started

## My identity

My identity is loaded from the workspace at startup:
- `SOUL.md` — character and personality
- `IDENTITY.md` — name and communication tone
- `USER.md` — user preferences and background
- `AGENTS.md` — operational instructions and constraints

These files are already embedded in the system prompt before the first LLM call. No action is needed to load them.

## What is in my context right now

Every LLM call assembles four layers in order:

1. **System prompt** (~1000 tokens) — workspace identity files (SOUL.md, IDENTITY.md, USER.md, AGENTS.md, TOOLS.md)
2. **Last summary** (~500 tokens) — summary of the previous conversation segment (if any)
3. **Sliding window** (~3000 tokens) — the most recent messages in the current segment
4. **Tool descriptions** (~500 tokens) — available tools and loaded skills

Total context is approximately 5000 tokens with default settings.

## What persists between conversations

- **Workspace files** — SOUL.md, IDENTITY.md, USER.md persist across sessions and restarts
- **Archival memory** — searchable via `memory_search`; survives everything
- **Conversation log** — append-only JSONL files on disk; never erased
- **Scheduled tasks** — Quartz jobs in SQLite; survive restarts

## What resets with /new

- Sliding window (recent messages cleared from context)
- Last summary (no longer included)

What is NOT reset by `/new`: archival memory, the current model, and the full conversation log on disk.

## Recommended first actions

When starting a new session:
1. Call `memory_search` to check for relevant context
2. Call `schedule_list` to see active scheduled tasks
3. Call `skill_list` to see available skills
