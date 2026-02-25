# engine.yaml Configuration Reference

This file documents the structure of `~/.config/klaw/engine.yaml`, the primary configuration file for the Klaw engine.

## ENV Variable Substitution

API keys are stored in `~/.config/klaw/.env` and referenced in `engine.yaml` using `${VAR_NAME}` placeholders.
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

## Example engine.yaml

```yaml
providers:
  zai:
    type: openai-compatible
    endpoint: https://api.z.ai/api/paas/v4
    apiKey: ${ZAI_API_KEY}
  deepseek:
    type: openai-compatible
    endpoint: https://api.deepseek.com/v1
    apiKey: ${DEEPSEEK_API_KEY}
  ollama:
    type: openai-compatible
    endpoint: http://localhost:11434/v1
    # no apiKey needed for local Ollama

models:
  zai/glm-5:
    provider: zai
    modelId: glm-5
    maxTokens: 8192
  deepseek/deepseek-chat:
    provider: deepseek
    modelId: deepseek-chat
    maxTokens: 32768
  ollama/qwen3:8b:
    provider: ollama
    modelId: "qwen3:8b"
    maxTokens: 32768

routing:
  default: zai/glm-5
  fallback:
    - deepseek/deepseek-chat
    - ollama/qwen3:8b
  tasks:
    summarization: zai/glm-5
    subagent: deepseek/deepseek-chat

llm:
  maxRetries: 3
  requestTimeoutMs: 30000
  initialBackoffMs: 1000
  backoffMultiplier: 2.0

memory:
  embedding:
    type: onnx
    model: all-MiniLM-L6-v2
  chunking:
    size: 512
    overlap: 64
  search:
    topK: 10

context:
  defaultBudgetTokens: 6144
  slidingWindow: 20
  subagentWindow: 10

processing:
  debounceMs: 500
  maxConcurrentLlm: 2
  maxToolCallRounds: 10

logging:
  subagentConversations: true

codeExecution:
  dockerImage: python:3.12-slim
  timeout: 30
  allowNetwork: false
  maxMemory: 256m
  maxCpus: "1.0"
  readOnlyRootfs: true
  keepAlive: false
  keepAliveIdleTimeoutMin: 5
  keepAliveMaxExecutions: 100

files:
  maxFileSizeBytes: 10485760  # 10 MB
```

## Notes

- `routing.fallback` lists model IDs tried in order if the primary model fails.
- `ContextLengthExceededError` is never retried and skips the fallback chain.
- Ollama does not require an `apiKey` — omit the field or set to `null`.
- Model IDs may contain colons (e.g. `qwen3:8b`) — use `provider/modelId` as the full ID for routing.
