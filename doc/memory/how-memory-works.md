# How Memory Works

## Two memory tiers

**Archival Memory** — on-demand. Semantic chunks indexed in sqlite-vec. Retrieved with `memory_search`. Use for facts, summaries, and notes that don't need to be in every context window.

**Recall Memory** — automatic. The sliding window of recent messages from the current segment. No action required; it is assembled before every LLM call.

## Context assembly for every LLM call

Four layers are assembled in this order:

1. System prompt (~1000 tokens) — workspace identity files (SOUL.md, IDENTITY.md, USER.md, AGENTS.md, TOOLS.md)
2. Last summary (~500 tokens)
3. Sliding window — last N messages (~3000 tokens)
4. Tool descriptions (~500 tokens) — available tools and loaded skills

Default total: approximately 5000 tokens.

## Context budget

Each model has a `contextBudget` in `engine.json`. The Engine uses 90% of the budget as a safety margin for approximate token counting. The sliding window shrinks to fit whatever space remains after the fixed layers are placed.

## Why archival memory is on-demand

Pre-loading all archival results on every call costs tokens even for irrelevant queries. The agent calls `memory_search` only when needed, leaving more space for recent messages.

## Segments and /new

A segment is the conversation portion since the last `/new`. The sliding window shows only messages from the current segment. `memory_search` can still retrieve facts from any segment, regardless of when `/new` was called.
