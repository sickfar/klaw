# engine.json Configuration Reference

This file documents the structure of `~/.config/klaw/engine.json`, the primary configuration file for the Klaw engine.

> **Interactive editor:** Run `klaw config edit engine` for a TUI that shows all properties with descriptions, validates changes, and saves directly.
>
> **Generated reference:** See [`engine-config-reference.md`](engine-config-reference.md) for the most up-to-date property descriptions (auto-generated from `@ConfigDoc` annotations in source code).

## JSON Schema

A JSON Schema (draft-07) is available at [`engine.schema.json`](engine.schema.json). Add it to your config file for IDE autocompletion:

```json
{
  "$schema": "./engine.schema.json",
  "providers": { ... }
}
```

To generate the latest schema from your installed version:

```bash
klaw doctor --dump-schema engine > engine.schema.json
```

## ENV Variable Substitution

API keys are stored in `~/.config/klaw/.env` and referenced in `engine.json` using `${VAR_NAME}` placeholders.
The engine resolves placeholders via `EnvVarResolver` at startup.

```
~/.config/klaw/.env:
  ZAI_API_KEY=sk-...
  DEEPSEEK_API_KEY=sk-...
```

## Built-in Provider Registry

Known providers have their `type` and `endpoint` built into the engine. For these providers, you only need to specify `apiKey`:

| Alias        | Type                | Default Endpoint                            |
|--------------|---------------------|---------------------------------------------|
| `anthropic`  | `anthropic`         | `https://api.anthropic.com`                 |
| `zai`        | `openai-compatible` | `https://api.z.ai/api/coding/paas/v4`       |
| `kimi-code`  | `openai-compatible` | `https://api.kimi.com/coding/v1`            |
| `openai`     | `openai-compatible` | `https://api.openai.com/v1`                 |
| `deepseek`   | `openai-compatible` | `https://api.deepseek.com/v1`               |
| `ollama`     | `openai-compatible` | `http://localhost:11434/v1`                 |

You can override `type` or `endpoint` for any known provider. For providers not in the registry, both `type` and `endpoint` are required.

## Provider Types

| Type                | Providers                                  |
|---------------------|--------------------------------------------|
| `openai-compatible` | Z.ai (GLM-5), Kimi Code (k2.5), DeepSeek, Qwen, Ollama, OpenAI |
| `anthropic`         | Anthropic Claude (api.anthropic.com)       |

The `anthropic` type uses the official Anthropic Java SDK. It handles the differences in auth headers (`x-api-key`), request format (top-level `system` param), and response format (content blocks) automatically.

## Top-Level Structure

The config has 20 top-level fields organized into logical groups:

**LLM providers & routing:** `providers`, `models`, `routing`

**Workspace:** `workspace`

**Memory system:** `memory` (embedding, chunking, search, autoRag, compaction, consolidation)

**Agent behavior:** `context`, `processing`, `skills`, `commands`, `heartbeat`

**Tool limits:** `files`, `codeExecution`, `hostExecution`

**Web tools:** `web` (fetch, search)

**Content processing:** `documents`, `vision`

**Infrastructure:** `httpRetry`, `database`, `logging`, `docs`

## Example engine.json

Minimal config for a known provider (recommended):

```json
{
  "providers": {
    "anthropic": {
      "apiKey": "${ANTHROPIC_API_KEY}"
    }
  },
  "routing": {
    "default": "anthropic/claude-sonnet-4-6"
  }
}
```

Full config with multiple providers and overrides:

```json
{
  "workspace": null,
  "providers": {
    "anthropic": {
      "apiKey": "${ANTHROPIC_API_KEY}"
    },
    "zai": {
      "apiKey": "${ZAI_API_KEY}"
    },
    "deepseek": {
      "apiKey": "${DEEPSEEK_API_KEY}"
    },
    "ollama": {},
    "my-proxy": {
      "type": "openai-compatible",
      "endpoint": "https://my-proxy.example.com/v1",
      "apiKey": "${PROXY_API_KEY}"
    }
  },
  "models": {
    "zai/glm-5": {},
    "deepseek/deepseek-chat": {},
    "ollama/qwen3:8b": {}
  },
  "routing": {
    "default": "zai/glm-5",
    "fallback": ["deepseek/deepseek-chat", "ollama/qwen3:8b"],
    "tasks": {
      "summarization": "zai/glm-5",
      "subagent": "deepseek/deepseek-chat",
      "consolidation": "zai/glm-5"
    }
  },
  "httpRetry": {
    "maxRetries": 3,
    "requestTimeoutMs": 90000,
    "initialBackoffMs": 500,
    "backoffMultiplier": 2.0
  },
  "memory": {
    "embedding": {
      "type": "onnx",
      "model": "multilingual-e5-small"
    },
    "chunking": {
      "size": 512,
      "overlap": 64
    },
    "search": {
      "topK": 10,
      "mmr": {
        "enabled": true,
        "lambda": 0.7
      },
      "temporalDecay": {
        "enabled": true,
        "halfLifeDays": 30
      }
    },
    "injectMemoryMap": false,
    "mapMaxCategories": 10,
    "autoRag": {
      "enabled": true,
      "topK": 5,
      "maxTokens": 1500,
      "relevanceThreshold": 0.5,
      "minMessageTokens": 10
    },
    "compaction": {
      "enabled": false,
      "summaryBudgetFraction": 0.25,
      "compactionThresholdFraction": 0.5
    },
    "consolidation": {
      "enabled": true,
      "cron": "0 0 0 * * ?",
      "model": "zai/glm-5",
      "minMessages": 5,
      "category": "daily-summary"
    }
  },
  "context": {
    "tokenBudget": 100000,
    "subagentHistory": 10
  },
  "processing": {
    "debounceMs": 500,
    "maxConcurrentLlm": 2,
    "maxToolCallRounds": 50,
    "maxToolOutputChars": 8000,
    "maxDebounceEntries": 1000,
    "streaming": {
      "enabled": false,
      "throttleMs": 50
    }
  },
  "logging": {
    "subagentConversations": false
  },
  "codeExecution": {
    "dockerImage": "ghcr.io/sickfar/klaw-sandbox:latest",
    "timeout": 30,
    "allowNetwork": false,
    "maxMemory": "256m",
    "maxCpus": "1.0",
    "readOnlyRootfs": true,
    "keepAlive": true,
    "keepAliveIdleTimeoutMin": 5,
    "keepAliveMaxExecutions": 100,
    "runAsUser": "1000:1000"
  },
  "files": {
    "maxFileSizeBytes": 10485760
  },
  "docs": {
    "enabled": true
  },
  "heartbeat": {
    "interval": "PT1H",
    "model": "zai/glm-5",
    "injectInto": "telegram_123456",
    "channel": "telegram"
  },
  "web": {
    "fetch": {
      "enabled": true,
      "requestTimeoutMs": 30000,
      "maxResponseSizeBytes": 1048576,
      "userAgent": "Klaw/1.0 (AI Agent)"
    },
    "search": {
      "enabled": false,
      "provider": "brave",
      "apiKey": "${BRAVE_SEARCH_API_KEY}",
      "maxResults": 5,
      "requestTimeoutMs": 10000,
      "braveEndpoint": "https://api.search.brave.com",
      "tavilyEndpoint": "https://api.tavily.com"
    }
  },
  "vision": {
    "enabled": false,
    "model": "glm/glm-4.6v",
    "maxImageSizeBytes": 10485760,
    "maxImagesPerMessage": 5,
    "supportedFormats": ["image/jpeg", "image/png", "image/gif", "image/webp"],
    "attachmentsDirectory": "/data/attachments"
  }
}
```

## routing

Configures model selection and fallback behavior for all LLM calls.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `default` | string | — | Default model for interactive chat (`provider/modelId`). |
| `fallback` | string[] | `[]` | Fallback models tried in order if the requested model fails. |
| `tasks.summarization` | string | — | Model for compaction summarization tasks. |
| `tasks.subagent` | string | — | Model for subagent tasks. |
| `tasks.consolidation` | string | `""` | Model for daily consolidation. Empty falls back (see cascade below). |

### Fallback chain

When any LLM call is made, the engine builds a fallback chain: `[requested model] + routing.fallback` (duplicates filtered out). Models are tried in order until one succeeds.

The fallback list is **global** — it applies to all model calls, including task-specific models (`summarization`, `subagent`, `consolidation`), not just `routing.default`. Task models do not have their own fallback lists.

Example: if `routing.tasks.summarization = "zai/glm-5"` and `routing.fallback = ["deepseek/deepseek-chat"]`, a failed summarization call retries with `deepseek/deepseek-chat`.

### Error behavior

- **`ContextLengthExceededError`** — skips the fallback chain entirely and is thrown immediately. Retrying with a different model would not help since the context is too large.
- **`AllProvidersFailedError`** — thrown when all models in the chain fail. The user sees "All LLM providers are unreachable. Please try again later."

### Stop reason notices

When an LLM response finishes with an abnormal stop reason, a notice is automatically appended to the delivered message. This is always-on and requires no configuration.

| Stop reason | Notice shown to user |
|-------------|---------------------|
| `content_filter` | `[Response stopped: content filter triggered]` |
| `length` / `max_tokens` | `[Response stopped: output token limit reached]` |
| `stop_sequence` | `[Response stopped: stop sequence]` (with stop reason value if available) |
| Anthropic `stop_reason` | `[Response stopped: stop_reason=<value>]` (when `stop_reason` is present and raw reason is not `end_turn`/`stop_sequence`) |
| Other (custom/vLLM) | `[Response stopped: <raw_value>]` |

Normal stop reasons (`stop`, `end_turn`, `tool_use`, `tool_calls`) produce no notice. The notice is appended to the delivered content only — the database content is not modified.

### Consolidation model cascade

The consolidation task resolves its model via a cascade:
`memory.consolidation.model` → `routing.tasks.consolidation` → `routing.tasks.summarization`.
The first non-empty value is used.

## memory

Configures the memory system: embedding backend, chunking, search, categories, memory map injection, auto-RAG, compaction, and consolidation.

Memory facts are stored in the database with categories. On first start, MEMORY.md and daily memory logs are parsed (markdown headers become categories, lines become facts), then archived. The database is the sole source of truth for memory after initial indexation.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `embedding.type` | string | — | Embedding backend: `"onnx"` (local) or `"ollama"`. |
| `embedding.model` | string | — | Embedding model name. Default: `"multilingual-e5-small"` (50+ languages, cross-lingual retrieval, 384d, ~120 MB). For English-only use: `"all-MiniLM-L6-v2"`. |
| `chunking.size` | int | — | Maximum chunk size in approximate tokens (used by docs indexing). |
| `chunking.overlap` | int | — | Overlap between consecutive chunks in approximate tokens. |
| `search.topK` | int | — | Number of top results from hybrid search. |
| `search.mmr.enabled` | bool | `true` | Enable MMR (Maximal Marginal Relevance) diversity reranking. Reduces redundant results by penalizing candidates too similar to already-selected ones. |
| `search.mmr.lambda` | double | `0.7` | Relevance vs diversity tradeoff. `1.0` = pure relevance (no diversity), `0.0` = max diversity. |
| `search.temporalDecay.enabled` | bool | `true` | Enable temporal decay — recent memories score higher than old ones. |
| `search.temporalDecay.halfLifeDays` | int | `30` | Half-life in days. After this many days, a memory's score is halved. |
| `injectMemoryMap` | bool | `false` | Inject a Memory Map into the system prompt showing top categories by popularity. |
| `mapMaxCategories` | int | `10` | Maximum number of categories displayed in the Memory Map. Remaining categories shown as "...and N more". |
| `autoRag.enabled` | bool | `true` | Enable automatic RAG retrieval. |
| `autoRag.topK` | int | `5` | Number of top relevant messages to retrieve. |
| `autoRag.maxTokens` | int | `1500` | Maximum tokens of auto-RAG context to inject. |
| `autoRag.relevanceThreshold` | double | `0.5` | Minimum relevance score threshold for including results. |
| `autoRag.minMessageTokens` | int | `10` | Minimum token count in a message to trigger auto-RAG. |
| `compaction.enabled` | bool | `false` | Enable background compaction. |
| `compaction.summaryBudgetFraction` | double | `0.25` | Fraction of context budget allocated to summaries (exclusive range `(0.0, 1.0)`). |
| `compaction.compactionThresholdFraction` | double | `0.5` | Fraction of context budget that defines the compaction zone (exclusive range `(0.0, 1.0)`). Compaction triggers when `messageTokens > budget * (summaryBudgetFraction + compactionThresholdFraction)`. |
| `consolidation.enabled` | bool | `false` | Enable daily consolidation. |
| `consolidation.cron` | string | `"0 0 0 * * ?"` | Cron expression for the consolidation schedule. |
| `consolidation.model` | string | `""` | Model for consolidation. Empty falls back to `routing.tasks.consolidation`, then `routing.tasks.summarization`. |
| `consolidation.excludeChannels` | string[] | `[]` | Channels to exclude from consolidation (e.g. `["internal"]`). |
| `consolidation.category` | string | `"daily-summary"` | Default memory category hint for extracted facts. |
| `consolidation.minMessages` | int | `5` | Minimum messages required to trigger consolidation. |

**Compaction validation**: `compaction.summaryBudgetFraction + compaction.compactionThresholdFraction` must be less than `1.0`.

**Consolidation** — daily memory consolidation automatically reviews conversation history and extracts important facts into long-term memory. The engine collects all messages from the past 24 hours, splits them into chunks that fit the model's context budget, and for each chunk runs an LLM session with the `memory_save` tool. The LLM decides which facts are worth saving and calls `memory_save` with appropriate categories. Consolidation is idempotent per day — if it has already run for a given date, subsequent cron triggers are skipped unless forced via CLI.

Manual trigger: `klaw memory consolidate [--date YYYY-MM-DD] [--force]`

## heartbeat

Configures periodic autonomous LLM monitoring. The engine reads `HEARTBEAT.md` from the workspace at each interval and runs an LLM session with full tool access. The LLM decides whether to deliver results to the user via the `heartbeat_deliver` tool.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `interval` | string | `"off"` | ISO-8601 duration (`"PT1H"`, `"PT30M"`) or `"off"` to disable. |
| `model` | string | `null` | LLM model for heartbeat. `null` uses `routing.default`. |
| `injectInto` | string | `null` | chatId for delivering results (e.g. `"telegram_123456"`). |
| `channel` | string | `null` | Channel for delivery (e.g. `"telegram"`). |

Both `injectInto` and `channel` must be set for delivery to work. If either is missing, heartbeat runs are skipped entirely (no LLM tokens consumed).

`channel` is set automatically by `klaw init` based on the first configured messaging channel. `injectInto` is set at runtime via the `/heartbeat` command — send it in any chat to pair that chat for heartbeat delivery. The command persists the change to `engine.json`.

See `doc/scheduling/heartbeat.md` for details.

## workspace

Optional path to the workspace directory. Overrides the default `$KLAW_WORKSPACE` location.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `workspace` | string | `null` | Absolute path to workspace directory. When set, overrides `$KLAW_WORKSPACE` env var. Used for OpenClaw migration to point to an existing workspace. |

## processing.streaming

Configures real-time token-by-token streaming of LLM responses to channels.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `streaming.enabled` | bool | `false` | Enable streaming for interactive responses. When enabled, tokens are sent progressively to the user instead of waiting for the full response. |
| `streaming.throttleMs` | long | `50` | Minimum interval in milliseconds between stream deltas sent to the gateway. Lower values give smoother output but higher message frequency. |

Streaming works across all channels: WebSocket (local chat), Telegram (via message drafts), and Discord. The engine uses SSE (Server-Sent Events) for OpenAI-compatible providers and native streaming for Anthropic.

## docs

Controls the built-in documentation service. Documentation is embedded in the engine JAR and indexed at startup.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `true` | Enable or disable the docs service. When `false`, `docs_search`, `docs_read`, and `docs_list` tools return a disabled message. |

## httpRetry

Configures HTTP retry behavior for LLM API calls and other outbound HTTP requests. Previously named `llm` (class renamed from `LlmRetryConfig` to `HttpRetryConfig`).

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `maxRetries` | int | `3` | Maximum number of retry attempts. |
| `requestTimeoutMs` | long | `90000` | HTTP request timeout in milliseconds. |
| `initialBackoffMs` | long | `500` | Initial backoff delay in milliseconds before first retry. |
| `backoffMultiplier` | double | `2.0` | Multiplier applied to backoff delay after each retry. |

## hostExecution

Controls the `host_exec` tool — running shell commands directly on the host outside the Docker sandbox.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `false` | Enable host command execution. |
| `allowList` | string[] | `[]` | Commands allowed without user confirmation. |
| `notifyList` | string[] | `[]` | Commands that trigger a notification to the user. |
| `preValidation.enabled` | bool | `true` | Enable LLM-based pre-validation of host commands. |
| `preValidation.model` | string | `""` | Model used for pre-validation checks. |
| `preValidation.riskThreshold` | int | `5` | Risk score threshold above which commands are blocked. |
| `preValidation.timeoutMs` | long | `60000` | Timeout in milliseconds for the pre-validation LLM call. |
| `askTimeoutMin` | int | `0` | Timeout in minutes for user confirmation prompts (0 = no timeout). |

## skills

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `maxInlineSkills` | int | `5` | Maximum number of skills included inline in the system prompt. |

## codeExecution

Configures the Docker-based sandbox for code execution by agents.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `dockerImage` | string | `"ghcr.io/sickfar/klaw-sandbox:latest"` | Docker image used for the sandbox container. |
| `timeout` | int | `30` | Maximum execution timeout in seconds. |
| `allowNetwork` | bool | `false` | Allow network access inside the sandbox. |
| `maxMemory` | string | `"256m"` | Maximum memory limit for the container. |
| `maxCpus` | string | `"1.0"` | Maximum CPU cores for the container. |
| `readOnlyRootfs` | bool | `true` | Mount the container root filesystem as read-only. |
| `keepAlive` | bool | `true` | Reuse the container between executions for faster startup. |
| `keepAliveIdleTimeoutMin` | int | `5` | Idle timeout in minutes before stopping a kept-alive container. |
| `keepAliveMaxExecutions` | int | `100` | Maximum executions before recycling a kept-alive container. |
| `volumeMounts` | string[] | `[]` | Additional Docker volume mounts. Dangerous paths are blocked (see below). |
| `runAsUser` | string | `"1000:1000"` | User:group ID for the sandbox container process. |

### Hardcoded Security Defaults

The following security measures are always enforced and cannot be disabled:

- **`--privileged=false`** — privileged mode is hardcoded forbidden
- **`--cap-drop=ALL`** — all Linux capabilities are dropped
- **`--security-opt=no-new-privileges`** — prevents privilege escalation via setuid/setgid binaries
- **`--pids-limit=64`** — limits the number of processes (fork bomb protection)
- **`--read-only`** — root filesystem is read-only (writable `/tmp` via tmpfs)
- **`--network=none`** — network is disabled by default (configurable via `allowNetwork`)
- **Volume blocklist** — sensitive host paths are automatically filtered: `/etc/passwd`, `/etc/shadow`, `/etc/gshadow`, `/etc/ssh`, `/root/.ssh`, `/root/.gnupg`, `/root/.aws`, `/home`, `/proc`, `/sys`, `/var/run/docker`

In keep-alive mode, `/tmp` is cleared between executions to prevent state leakage. Orphaned `klaw-sandbox-*` containers are automatically cleaned up on engine startup.

### Custom Docker Image

The sandbox runs with a read-only filesystem, non-root user, and dropped capabilities. Installing packages at runtime is impossible by design.

To add languages or tools (Node.js, Go, etc.), create a custom Docker image:

```dockerfile
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    nodejs npm golang-go curl jq \
    && rm -rf /var/lib/apt/lists/*
RUN useradd -u 1000 -m sandbox
```

Then configure it in `engine.json`:

```json
{
  "codeExecution": {
    "dockerImage": "my-custom-sandbox:latest"
  }
}
```

## web

Groups web-related tool configurations under a single section.

### web.fetch

Web page fetching tool. Converts HTML to readable markdown for LLM consumption.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `true` | Enable the `web_fetch` tool. |
| `requestTimeoutMs` | long | `30000` | HTTP request timeout in milliseconds. |
| `maxResponseSizeBytes` | long | `1048576` | Maximum response body size (default 1MB). |
| `userAgent` | string | `"Klaw/1.0 (AI Agent)"` | User-Agent header sent with requests. |

### web.search

Internet search tool with configurable provider. Disabled by default — requires an API key.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `false` | Enable the `web_search` tool. |
| `provider` | string | `"brave"` | Search provider: `"brave"` or `"tavily"`. |
| `apiKey` | string | `null` | API key. Use env var reference: `"${BRAVE_SEARCH_API_KEY}"`. |
| `maxResults` | int | `5` | Default number of search results. |
| `requestTimeoutMs` | long | `10000` | HTTP request timeout in milliseconds. |
| `braveEndpoint` | string | `"https://api.search.brave.com"` | Brave Search API base URL. |
| `tavilyEndpoint` | string | `"https://api.tavily.com"` | Tavily Search API base URL. |

See [web-fetch.md](../tools/web-fetch.md) and [web-search.md](../tools/web-search.md) for tool documentation.

## documents

Document tools for reading PDFs and converting Markdown to PDF. Available via the `documents` bundled skill.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `maxPdfSizeBytes` | long | `52428800` (50MB) | Maximum PDF file size for `pdf_read`. |
| `maxPages` | int | `100` | Maximum number of pages to extract in `pdf_read` (0 = unlimited). |
| `maxOutputChars` | int | `100000` | Maximum output text length in characters before truncation. |
| `pdfFontSize` | float | `12` | Default font size for `md_to_pdf` output. |

See [documents.md](../tools/documents.md) for tool documentation.

## vision

Image analysis and inline vision support. Enables the `image_analyze` tool and automatic image description for text-only models.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `false` | Enable vision capabilities. |
| `model` | string | `""` | Vision model ID (e.g. `"glm/glm-4.6v"`). Empty falls back to `routing.default`. |
| `maxTokens` | int | — | Maximum output tokens for vision model responses. If not set, uses `maxOutput` from model registry, or 1024. |
| `maxImageSizeBytes` | long | `10485760` (10MB) | Maximum image file size. |
| `maxImagesPerMessage` | int | `5` | Maximum images per message for inline vision. |
| `supportedFormats` | string[] | `["image/jpeg", "image/png", "image/gif", "image/webp"]` | Allowed image MIME types. |
| `attachmentsDirectory` | string | `""` | Path to gateway attachments directory. Must match `attachments.directory` in `gateway.json`. Required for Telegram/Discord image support. |

**How it works:**
- **Vision-capable models** (as indicated in `model-registry.json`) receive images inline as multimodal content.
- **Text-only models** automatically use the configured vision model to generate text descriptions of images via `image_analyze` when `file_read` is called on an image.
- `image_analyze` is always available when `vision.enabled` is true, regardless of the active model.

See [vision.md](../tools/vision.md) for tool documentation.

## Notes

- Ollama does not require an `apiKey` — omit the field or set to `null`.
- Model IDs may contain colons (e.g. `qwen3:8b`) — use `provider/modelId` as the full ID for routing.
