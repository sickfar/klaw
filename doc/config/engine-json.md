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

## Provider Types

Only `openai-compatible` is currently supported. `anthropic-compatible` is planned.

| Type                   | Providers                       |
|------------------------|---------------------------------|
| `openai-compatible`    | Z.ai (GLM-5), DeepSeek, Qwen, Ollama |
| `anthropic-compatible` | Z.ai Anthropic endpoint (P1)    |

## Example engine.json

```json
{
  "providers": {
    "zai": {
      "type": "openai-compatible",
      "endpoint": "https://api.z.ai/api/paas/v4",
      "apiKey": "${ZAI_API_KEY}"
    },
    "deepseek": {
      "type": "openai-compatible",
      "endpoint": "https://api.deepseek.com/v1",
      "apiKey": "${DEEPSEEK_API_KEY}"
    },
    "ollama": {
      "type": "openai-compatible",
      "endpoint": "http://localhost:11434/v1"
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
  "llm": {
    "maxRetries": 3,
    "requestTimeoutMs": 90000,
    "initialBackoffMs": 500,
    "backoffMultiplier": 2.0
  },
  "memory": {
    "embedding": {
      "type": "onnx",
      "model": "all-MiniLM-L6-v2"
    },
    "chunking": {
      "size": 512,
      "overlap": 64
    },
    "search": {
      "topK": 10,
      "mmr": {
        "enabled": false,
        "lambda": 0.7
      },
      "temporalDecay": {
        "enabled": false,
        "halfLifeDays": 30
      }
    },
    "injectSummary": false,
    "mapMaxCategories": 10
  },
  "context": {
    "defaultBudgetTokens": 100000,
    "subagentHistory": 10
  },
  "processing": {
    "debounceMs": 500,
    "maxConcurrentLlm": 2,
    "maxToolCallRounds": 50,
    "maxToolOutputChars": 8000,
    "maxDebounceEntries": 1000
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
    "keepAlive": false,
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
  "consolidation": {
    "enabled": true,
    "cron": "0 0 0 * * ?",
    "model": "zai/glm-5",
    "minMessages": 5,
    "category": "daily-summary"
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

## memory

Configures the memory system: embedding backend, chunking, search, categories, and memory map injection.

Memory facts are stored in the database with categories. On first start, MEMORY.md and daily memory logs are parsed (markdown headers become categories, lines become facts), then archived. The database is the sole source of truth for memory after initial indexation.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `embedding.type` | string | — | Embedding backend: `"onnx"` (local) or `"ollama"`. |
| `embedding.model` | string | — | Embedding model name (e.g. `"all-MiniLM-L6-v2"`). |
| `chunking.size` | int | — | Maximum chunk size in approximate tokens (used by docs indexing). |
| `chunking.overlap` | int | — | Overlap between consecutive chunks in approximate tokens. |
| `search.topK` | int | — | Number of top results from hybrid search. |
| `search.mmr.enabled` | bool | `false` | Enable MMR (Maximal Marginal Relevance) diversity reranking. Reduces redundant results by penalizing candidates too similar to already-selected ones. |
| `search.mmr.lambda` | double | `0.7` | Relevance vs diversity tradeoff. `1.0` = pure relevance (no diversity), `0.0` = max diversity. |
| `search.temporalDecay.enabled` | bool | `false` | Enable temporal decay — recent memories score higher than old ones. |
| `search.temporalDecay.halfLifeDays` | int | `30` | Half-life in days. After this many days, a memory's score is halved. |
| `injectSummary` | bool | `false` | Inject a Memory Map into the system prompt showing top categories by popularity. |
| `mapMaxCategories` | int | `10` | Maximum number of categories displayed in the Memory Map. Remaining categories shown as "...and N more". |

## heartbeat

Configures periodic autonomous LLM monitoring. The engine reads `HEARTBEAT.md` from the workspace at each interval and runs an LLM session with full tool access. The LLM decides whether to deliver results to the user via the `heartbeat_deliver` tool.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `interval` | string | `"off"` | ISO-8601 duration (`"PT1H"`, `"PT30M"`) or `"off"` to disable. |
| `model` | string | `null` | LLM model for heartbeat. `null` uses `routing.default`. |
| `injectInto` | string | `null` | chatId for delivering results (e.g. `"telegram_123456"`). |
| `channel` | string | `null` | Channel for delivery (e.g. `"telegram"`). |

Both `injectInto` and `channel` must be set for delivery to work. If either is missing, heartbeat runs are skipped entirely (no LLM tokens consumed).

`channel` is set automatically by `klaw init` based on the first configured messaging channel. `injectInto` is set at runtime via the `/use-for-heartbeat` command — send it in any chat to pair that chat for heartbeat delivery. The command persists the change to `engine.json`.

See `doc/scheduling/heartbeat.md` for details.

## docs

Controls the built-in documentation service. Documentation is embedded in the engine JAR and indexed at startup.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `true` | Enable or disable the docs service. When `false`, `docs_search`, `docs_read`, and `docs_list` tools return a disabled message. |

## autoRag

Automatic RAG retrieval injects relevant earlier messages into the context window when the conversation is long enough.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `true` | Enable automatic RAG retrieval. |
| `topK` | int | `3` | Number of top relevant messages to retrieve. |
| `maxTokens` | int | `400` | Maximum tokens of auto-RAG context to inject. |
| `relevanceThreshold` | double | `0.5` | Minimum relevance score threshold for including results. |
| `minMessageTokens` | int | `10` | Minimum token count in a message to trigger auto-RAG. |

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
| `preValidation.timeoutMs` | long | `5000` | Timeout in milliseconds for the pre-validation LLM call. |
| `askTimeoutMin` | int | `0` | Timeout in minutes for user confirmation prompts (0 = no timeout). |

## skills

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `maxInlineSkills` | int | `5` | Maximum number of skills included inline in the system prompt. |

## compatibility

Third-party compatibility settings.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `openclaw.enabled` | bool | `false` | Enable OpenClaw compatibility mode. |
| `openclaw.sync.memoryMd` | bool | `false` | Sync MEMORY.md file with OpenClaw. |
| `openclaw.sync.dailyLogs` | bool | `false` | Sync daily log files with OpenClaw. |
| `openclaw.sync.userMd` | bool | `false` | Sync USER.md file with OpenClaw. |

## consolidation

Daily memory consolidation — automatically reviews conversation history and extracts important facts into long-term memory. The engine collects all messages from the past 24 hours, splits them into chunks that fit the model's context budget, and for each chunk runs an LLM session with the `memory_save` tool. The LLM decides which facts are worth saving and calls `memory_save` with appropriate categories.

Consolidation is idempotent per day — if it has already run for a given date, subsequent cron triggers are skipped unless forced via CLI.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `false` | Enable daily consolidation. |
| `cron` | string | `"0 0 0 * * ?"` | Cron expression for the consolidation schedule. |
| `model` | string | `""` | Model for consolidation. Empty falls back to `routing.tasks.consolidation`, then `routing.tasks.summarization`. |
| `excludeChannels` | string[] | `[]` | Channels to exclude from consolidation (e.g. `["internal"]`). |
| `category` | string | `"daily-summary"` | Default memory category hint for extracted facts. |
| `minMessages` | int | `5` | Minimum messages required to trigger consolidation. |

Manual trigger: `klaw memory consolidate [--date YYYY-MM-DD] [--force]`

## summarization

Background compaction of old conversation messages. Uses a fraction-based trigger instead of a fixed token threshold. Compaction creates summaries of older messages; originals are never deleted from the conversation log.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `false` | Enable background compaction. |
| `summaryBudgetFraction` | double | `0.25` | Fraction of context budget allocated to summaries (exclusive range `(0.0, 1.0)`). |
| `compactionThresholdFraction` | double | `0.5` | Fraction of context budget that defines the compaction zone (exclusive range `(0.0, 1.0)`). Compaction triggers when `messageTokens > budget * (summaryBudgetFraction + compactionThresholdFraction)`. |

**Validation**: `summaryBudgetFraction + compactionThresholdFraction` must be less than `1.0`.

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
| `keepAlive` | bool | `false` | Reuse the container between executions for faster startup. |
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

## webFetch

Web page fetching tool. Converts HTML to readable markdown for LLM consumption.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `true` | Enable the `web_fetch` tool. |
| `requestTimeoutMs` | long | `30000` | HTTP request timeout in milliseconds. |
| `maxResponseSizeBytes` | long | `1048576` | Maximum response body size (default 1MB). |
| `userAgent` | string | `"Klaw/1.0 (AI Agent)"` | User-Agent header sent with requests. |

## webSearch

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

**How it works:**
- **Vision-capable models** (as indicated in `model-registry.json`) receive images inline as multimodal content.
- **Text-only models** automatically use the configured vision model to generate text descriptions of images via `image_analyze` when `file_read` is called on an image.
- `image_analyze` is always available when `vision.enabled` is true, regardless of the active model.

See [vision.md](../tools/vision.md) for tool documentation.

## Notes

- `routing.fallback` lists model IDs tried in order if the primary model fails.
- `ContextLengthExceededError` is never retried and skips the fallback chain.
- Ollama does not require an `apiKey` — omit the field or set to `null`.
- Model IDs may contain colons (e.g. `qwen3:8b`) — use `provider/modelId` as the full ID for routing.
