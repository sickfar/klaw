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
The engine resolves placeholders via `EnvVarResolver` at startup (see TASK-005).

```
~/.config/klaw/.env:
  ZAI_API_KEY=sk-...
  DEEPSEEK_API_KEY=sk-...
```

## Provider Types

Only `openai-compatible` is supported in TASK-003. `anthropic-compatible` is Post-MVP P1.

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
    "zai/glm-5": {
      "maxTokens": 8192
    },
    "deepseek/deepseek-chat": {
      "maxTokens": 32768
    },
    "ollama/qwen3:8b": {
      "maxTokens": 32768
    }
  },
  "routing": {
    "default": "zai/glm-5",
    "fallback": ["deepseek/deepseek-chat", "ollama/qwen3:8b"],
    "tasks": {
      "summarization": "zai/glm-5",
      "subagent": "deepseek/deepseek-chat"
    }
  },
  "llm": {
    "maxRetries": 3,
    "requestTimeoutMs": 30000,
    "initialBackoffMs": 1000,
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
      "topK": 10
    }
  },
  "context": {
    "defaultBudgetTokens": 6144,
    "slidingWindow": 20,
    "subagentHistory": 10
  },
  "processing": {
    "debounceMs": 500,
    "maxConcurrentLlm": 2,
    "maxToolCallRounds": 10
  },
  "logging": {
    "subagentConversations": true
  },
  "codeExecution": {
    "dockerImage": "python:3.12-slim-bookworm",
    "timeout": 30,
    "allowNetwork": false,
    "maxMemory": "256m",
    "maxCpus": "1.0",
    "readOnlyRootfs": true,
    "keepAlive": false,
    "keepAliveIdleTimeoutMin": 5,
    "keepAliveMaxExecutions": 100
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
  }
}
```

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

## Notes

- `routing.fallback` lists model IDs tried in order if the primary model fails.
- `ContextLengthExceededError` is never retried and skips the fallback chain.
- Ollama does not require an `apiKey` — omit the field or set to `null`.
- Model IDs may contain colons (e.g. `qwen3:8b`) — use `provider/modelId` as the full ID for routing.
