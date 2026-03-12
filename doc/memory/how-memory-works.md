# How Memory Works

## Two memory tiers

**Archival Memory** — on-demand. Semantic chunks indexed in sqlite-vec. Retrieved with `memory_search`. Use for facts, summaries, and notes that don't need to be in every context window.

**Recall Memory** — automatic. The sliding window of recent messages from the current segment. No action required; it is assembled before every LLM call.

## Context assembly for every LLM call

Four layers are assembled in this order:

1. System prompt (~1000 tokens) — workspace identity files (SOUL.md, IDENTITY.md, USER.md, AGENTS.md, TOOLS.md)
2. Summaries (if enabled) — condensed versions of older messages that fell out of the sliding window. Token budget is a configurable fraction of the total context budget (`summarization.summaryBudgetFraction`, default 0.5). Only present when background summarization is enabled.
3. Sliding window — last N messages from the current segment (fills remaining budget)
4. Tool descriptions (~500 tokens) — available tools and loaded skills

The total budget is controlled by `context.defaultBudgetTokens` (default: 100,000 tokens) or per-model `contextBudget` overrides.

## Context budget

Each model can have a `contextBudget` in `engine.json` (under `models`). If not set, the engine uses `context.defaultBudgetTokens` (default: 100,000). The Engine uses 90% of the budget as a safety margin for approximate token counting. The sliding window shrinks to fit whatever space remains after the fixed layers are placed.

## Why archival memory is on-demand

Pre-loading all archival results on every call costs tokens even for irrelevant queries. The agent calls `memory_search` only when needed, leaving more space for recent messages.

## Segments and /new

A segment is the conversation portion since the last `/new`. The sliding window shows only messages from the current segment. `memory_search` can still retrieve facts from any segment, regardless of when `/new` was called.
