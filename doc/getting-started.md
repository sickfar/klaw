# Getting Started

## My identity

My identity is loaded from the workspace at startup:
- `SOUL.md` — character and personality
- `IDENTITY.md` — name and communication tone
- `AGENTS.md` — operational instructions and constraints

These files are already embedded in the system prompt before the first LLM call. No action is needed to load them.

## What is in my context right now

Every LLM call assembles five layers in order:

1. **System prompt** (~500 tokens) — workspace identity files + tool descriptions
2. **Core memory** (~500 tokens) — structured JSON facts about the user and environment
3. **Last summary** (~500 tokens) — summary of the previous conversation segment (if any)
4. **Sliding window** (~3000 tokens) — the most recent messages in the current segment
5. **Tool descriptions** (~500 tokens) — available tools and loaded skills

Total context is approximately 5000 tokens with default settings.

## What persists between conversations

- **Core memory** — always loaded; survives `/new` and restarts
- **Archival memory** — searchable via `memory_search`; survives everything
- **Conversation log** — append-only JSONL files on disk; never erased
- **Scheduled tasks** — Quartz jobs in SQLite; survive restarts

## What resets with /new

- Sliding window (recent messages cleared from context)
- Last summary (no longer included)

What is NOT reset by `/new`: core memory, archival memory, the current model, and the full conversation log on disk.

## Recommended first actions

When starting a new session:
1. Call `memory_core_get` to review known user facts
2. Call `schedule_list` to see active scheduled tasks
3. Call `skill_list` to see available skills
