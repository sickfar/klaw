# How Memory Works

## Three memory tiers

**Core Memory** — always in context. Structured JSON stored in `core_memory.json`. Updated with `memory_core_set`. Limited to ~500 tokens. Use for facts that must always be available (user name, preferences, current projects).

**Archival Memory** — on-demand. Semantic chunks indexed in sqlite-vec. Retrieved with `memory_search`. Use for facts, summaries, and notes that don't need to be in every context window.

**Recall Memory** — automatic. The sliding window of recent messages from the current segment. No action required; it is assembled before every LLM call.

## Context assembly for every LLM call

Five layers are assembled in this order:

1. System prompt (~500 tokens)
2. Core memory (~500 tokens)
3. Last summary (~500 tokens)
4. Sliding window — last N messages (~3000 tokens)
5. Tool descriptions (~500 tokens)

Default total: approximately 5000 tokens.

## Context budget

Each model has a `contextBudget` in `engine.yaml`. The Engine uses 90% of the budget as a safety margin for approximate token counting. The sliding window shrinks to fit whatever space remains after the fixed layers are placed.

## Why archival memory is on-demand

Pre-loading all archival results on every call costs tokens even for irrelevant queries. The agent calls `memory_search` only when needed, leaving more space for recent messages.

## Segments and /new

A segment is the conversation portion since the last `/new`. The sliding window shows only messages from the current segment. `memory_search` can still retrieve facts from any segment, regardless of when `/new` was called.
