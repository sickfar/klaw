# How Memory Works

## Two memory tiers

**Archival Memory** — on-demand. Semantic chunks indexed in sqlite-vec. Retrieved with `memory_search`. Use for facts, summaries, and notes that don't need to be in every context window.

**Recall Memory** — automatic. The sliding window of recent messages from the current segment. No action required; it is assembled before every LLM call.

## Context assembly for every LLM call

Four layers are assembled in this order:

1. System prompt (~1000 tokens) — workspace identity files (SOUL.md, IDENTITY.md, USER.md, AGENTS.md, TOOLS.md)
2. Summaries (if enabled) — condensed versions of older messages produced by background compaction. Token budget is `memory.compaction.summaryBudgetFraction` (default 0.25) of the context budget. When summaries exceed that budget, the oldest are evicted and auto-RAG activates to compensate.
3. Sliding window — last N messages from the current segment (fills remaining budget)
4. Tool descriptions (~500 tokens) — available tools and loaded skills

The total budget is determined by the model's `contextLength` from the built-in model registry, or `context.defaultBudgetTokens` (default: 100,000 tokens) for unknown models.

## Context budget

The engine reads context length from `model-registry.json` for known models. If the model is not in the registry, it falls back to `context.defaultBudgetTokens` (default: 100,000). The Engine uses 90% of the budget as a safety margin for approximate token counting. The sliding window shrinks to fit whatever space remains after the fixed layers are placed.

## Why archival memory is on-demand

Pre-loading all archival results on every call costs tokens even for irrelevant queries. The agent calls `memory_search` only when needed, leaving more space for recent messages.

## Daily consolidation

When enabled (`consolidation.enabled: true` in `engine.json`), the engine runs a daily cron job that reviews the last 24 hours of conversations. For each chunk of messages (sized to fit the model's context budget), an LLM session extracts noteworthy facts using the `memory_save` tool. The LLM decides what to save and which categories to use.

Facts saved by consolidation have source `daily-consolidation:YYYY-MM-DD`. Consolidation is idempotent — it will not re-process a date unless forced via `klaw memory consolidate --force`.

See `doc/config/engine-json.md` for configuration options.

## Segments and /new

A segment is the conversation portion since the last `/new`. The sliding window shows only messages from the current segment. `memory_search` can still retrieve facts from any segment, regardless of when `/new` was called.
