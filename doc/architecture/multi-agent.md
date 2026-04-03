# Multi-Agent Architecture

Klaw supports running multiple named agents within a single Engine process. Each agent has its own isolated workspace, database, scheduler, memory, and identity — but shares the same LLM providers, embedding backend, and infrastructure.

## Overview

An **agent** is a named, independently configured AI persona. Each agent:

- Has its own workspace directory (SOUL.md, IDENTITY.md, AGENTS.md, memory, skills)
- Owns a separate SQLite database (`klaw-{id}.db`, `scheduler-{id}.db`)
- Can override routing, processing, memory, heartbeat, tools, vision, and limits
- Is reachable from the gateway via named channel instances (each channel has an `agentId`)

The agent named `default` is used by CLI commands when no `--agent` flag is given.

## Minimal Config — Single Agent

Most installations run a single agent named `default`:

**engine.json:**
```json
{
  "providers": {
    "anthropic": { "apiKey": "${ANTHROPIC_API_KEY}" }
  },
  "routing": { "default": "anthropic/claude-sonnet-4-6" },
  "agents": {
    "default": {
      "workspace": "/home/klaw/workspace"
    }
  }
}
```

**gateway.json:**
```json
{
  "channels": {
    "telegram": {
      "main": {
        "agentId": "default",
        "token": "${TELEGRAM_TOKEN}",
        "allowedChats": [
          {"chatId": "telegram_123456", "allowedUserIds": ["12345"]}
        ]
      }
    }
  }
}
```

## Two-Agent Config

Two agents with separate Telegram bots and different models:

**engine.json:**
```json
{
  "providers": {
    "zai": { "apiKey": "${ZAI_API_KEY}" },
    "anthropic": { "apiKey": "${ANTHROPIC_API_KEY}" }
  },
  "routing": { "default": "zai/glm-5" },
  "agents": {
    "_defaults": {
      "processing": { "slidingWindow": 20 }
    },
    "default": {
      "workspace": "/home/klaw/workspace"
    },
    "assistant": {
      "workspace": "/home/klaw/workspace-assistant",
      "routing": { "default": "anthropic/claude-sonnet-4-6" },
      "limits": { "maxMessagesPerMinute": 30 }
    }
  }
}
```

**gateway.json:**
```json
{
  "channels": {
    "telegram": {
      "main": {
        "agentId": "default",
        "token": "${TELEGRAM_MAIN_TOKEN}",
        "allowedChats": [
          {"chatId": "telegram_111", "allowedUserIds": ["12345"]}
        ]
      },
      "assistant-bot": {
        "agentId": "assistant",
        "token": "${TELEGRAM_ASSISTANT_TOKEN}",
        "allowedChats": [
          {"chatId": "telegram_222", "allowedUserIds": ["12345"]}
        ]
      }
    }
  }
}
```

## Shared vs Per-Agent Services

| Service | Shared (all agents) | Per-Agent (isolated) |
|---------|--------------------|-----------------------|
| LLM providers | Yes | — |
| Embedding backend (ONNX/Ollama) | Yes | — |
| HTTP retry config | Yes | — |
| SQLite database | — | `klaw-{id}.db` |
| Scheduler | — | `scheduler-{id}.db` |
| Memory (vector + FTS5) | — | Yes |
| Sessions | — | Yes |
| Workspace (SOUL.md, skills, etc.) | — | Yes |
| Heartbeat runner | — | Yes (per config) |
| MCP tool registry | — | Yes |
| Context builder | — | Yes |

## _defaults Key

The `_defaults` key in the `agents` map is a shared template. Fields in `_defaults` are applied to every non-default agent via deep merge — per-agent values take precedence. The `_defaults` entry is never started as an agent itself.

```json
{
  "agents": {
    "_defaults": {
      "processing": { "slidingWindow": 30 },
      "memory": { "autoRag": { "enabled": true } }
    },
    "default": { "workspace": "/home/klaw/workspace" },
    "assistant": {
      "workspace": "/home/klaw/workspace-assistant",
      "processing": { "slidingWindow": 10 }
    }
  }
}
```

The `assistant` agent gets `slidingWindow=10` (its own value), and `autoRag.enabled=true` from `_defaults`.

## How to Add a New Agent

1. Create a workspace directory for the new agent:
   ```bash
   mkdir -p /home/klaw/workspace-newagent
   ```

2. Add the agent to `engine.json`:
   ```json
   {
     "agents": {
       "default": { "workspace": "/home/klaw/workspace" },
       "newagent": {
         "workspace": "/home/klaw/workspace-newagent"
       }
     }
   }
   ```

3. Add a channel in `gateway.json` pointing to the new agent:
   ```json
   {
     "channels": {
       "telegram": {
         "newbot": {
           "agentId": "newagent",
           "token": "${NEWAGENT_TELEGRAM_TOKEN}",
           "allowedChats": []
         }
       }
     }
   }
   ```

4. Restart the engine and gateway:
   ```bash
   klaw service restart all
   ```

5. Run `klaw init` or place SOUL.md / IDENTITY.md in the new workspace to give the agent its identity.

## How to Disable an Agent

Set `enabled: false` in the agent's config. The agent is ignored at startup — no database, no scheduler, no heartbeat are started for it:

```json
{
  "agents": {
    "default": { "workspace": "/home/klaw/workspace" },
    "old-agent": {
      "enabled": false,
      "workspace": "/home/klaw/workspace-old"
    }
  }
}
```

## Database Isolation

Each agent uses its own SQLite database files in the data directory (`~/.local/share/klaw/` by default):

| File | Purpose |
|------|---------|
| `klaw-{agentId}.db` | Conversations, memory, sessions, message embeddings |
| `scheduler-{agentId}.db` | Quartz scheduler jobs and triggers |

This means agents do not share memory, sessions, or scheduled jobs. JSONL conversation files are also isolated per workspace under `conversations/`.

## CLI --agent Flag

Commands that access agent-specific resources accept `--agent <id>` (short: `-a`):

```bash
# Memory commands
klaw memory --agent assistant search "kotlin coroutines"
klaw memory --agent assistant categories list
klaw memory --agent assistant consolidate

# Sessions
klaw sessions --agent assistant list
klaw sessions --agent assistant cleanup

# Scheduling
klaw schedule --agent assistant list
klaw schedule --agent assistant add daily "0 9 * * *" "Good morning!"
klaw schedule --agent assistant status

# Context diagnostics
klaw context --agent assistant --chat-id telegram_222
```

When `--agent` is omitted, commands target the `default` agent.

## Migration from Single-Agent

Existing single-agent installations must be migrated to the multi-agent config format. The migration script handles this automatically:

```bash
# Dry run — see what will change
./scripts/migrate-to-multiagent.sh

# Apply the migration
./scripts/migrate-to-multiagent.sh --apply
```

The script:
1. Wraps the flat `engine.json` fields in an `agents.default` block with the existing workspace
2. Renames `gateway.json` channel keys from flat type names (`telegram`) to named instances (`telegram.main`)
3. Adds `agentId: "default"` to each channel
4. Renames `localWs` to `websocket.local`
5. Renames `klaw.db` → `klaw-default.db` and `scheduler.db` → `scheduler-default.db`

Custom directories can be specified:
```bash
./scripts/migrate-to-multiagent.sh --apply \
  --config-dir /custom/config \
  --data-dir /custom/data \
  --state-dir /custom/state
```

After migration, restart all services:
```bash
klaw service restart all
```
