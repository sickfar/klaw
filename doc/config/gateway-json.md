# Gateway Configuration: gateway.json

**Location:** `~/.config/klaw/gateway.json`

This file is read by the Gateway process on startup. It is read-only from the agent's perspective — the agent cannot modify it. The user must edit this file manually or via `klaw config edit gateway`.

> **Interactive editor:** Run `klaw config edit gateway` for a TUI that shows all properties with descriptions, validates changes, and saves directly.
>
> **Generated reference:** See [`gateway-config-reference.md`](gateway-config-reference.md) for the most up-to-date property descriptions (auto-generated from `@ConfigDoc` annotations in source code).

## JSON Schema

A JSON Schema (draft-07) is available at [`gateway.schema.json`](gateway.schema.json). Add it to your config file for IDE autocompletion:

```json
{
  "$schema": "./gateway.schema.json",
  "channels": { ... }
}
```

To generate the latest schema from your installed version:

```bash
klaw doctor --dump-schema gateway > gateway.schema.json
```

---

## Channel Architecture

The `channels` map uses a two-level structure: `channels.<type>.<name>`. Each named channel instance has its own config and an `agentId` field that routes its messages to a specific engine agent.

This lets you run multiple bots of the same type (e.g. two Telegram bots) pointing to different agents:

```json
{
  "channels": {
    "telegram": {
      "main": {
        "agentId": "default",
        "token": "${TELEGRAM_BOT_TOKEN}",
        "allowedChats": [
          {"chatId": "telegram_123456789", "allowedUserIds": ["12345"]}
        ]
      },
      "assistant": {
        "agentId": "assistant",
        "token": "${TELEGRAM_ASSISTANT_TOKEN}",
        "allowedChats": [
          {"chatId": "telegram_987654321", "allowedUserIds": ["12345"]}
        ]
      }
    },
    "websocket": {
      "local": {
        "agentId": "default",
        "port": 37474
      }
    }
  }
}
```

## Full Example

```json
{
  "channels": {
    "telegram": {
      "main": {
        "agentId": "default",
        "token": "your-bot-token",
        "allowedChats": [
          {"chatId": "telegram_123456789", "allowedUserIds": ["12345"]},
          {"chatId": "telegram_987654321"}
        ]
      }
    },
    "websocket": {
      "local": {
        "agentId": "default",
        "port": 37474
      }
    },
    "discord": {
      "main": {
        "agentId": "default",
        "token": "${KLAW_DISCORD_TOKEN}",
        "allowedGuilds": [
          {
            "guildId": "123456789012345678",
            "allowedChannelIds": [],
            "allowedUserIds": ["987654321098765432"]
          }
        ]
      }
    }
  },
  "attachments": {
    "directory": ""
  },
  "delivery": {
    "maxReconnectAttempts": 0,
    "drainBudgetSeconds": 30,
    "channelDrainBudgetSeconds": 30
  },
  "webui": {
    "enabled": true,
    "apiToken": ""
  }
}
```

---

## channels.telegram.\<name\>

Each named Telegram channel instance routes to one agent.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `agentId` | string | required | Agent ID in `engine.json` that this channel routes messages to |
| `token` | string | required | Telegram Bot API token from @BotFather |
| `allowedChats` | list of objects | `[]` | Paired chats allowed to interact with the bot |

### allowedChats — Inbound & Outbound Allowlist

The `allowedChats` list controls which chats and users can interact with the bot. This is the primary access control mechanism — **both inbound and outbound** messages are blocked for unpaired chats.

Each entry is an object:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `chatId` | string | required | Chat ID in `{channel}_{platformId}` format |
| `allowedUserIds` | list of strings | `[]` | User IDs allowed in this chat (empty = deny all users) |

**Restrictive by default:**
- **Empty `allowedChats`** — denies all inbound and outbound messages. No chat can interact with the bot.
- **Empty `allowedUserIds`** — denies all users in that chat, even if the chat is listed.

### Pairing Flow

New users pair with the bot using the `/start` command:

1. User sends `/start` to the bot in Telegram
2. Bot replies with a 6-character pairing code and instructions
3. Operator runs `klaw channels pair telegram <code>` on the server
4. The chat and user are added to `allowedChats` in `gateway.json`
5. Gateway detects the config change and reloads the allowlist

To unpair a chat: `klaw channels unpair telegram <chatId>`

### chatId Format

Chat IDs use the format `{channel}_{platformId}`:

- Telegram chat `123456` → `"telegram_123456"`

Use this exact format in:
- `send_message(chatId="telegram_123456")`
- `schedule_add(injectInto="telegram_123456")`

### What to do when messages are blocked

If a chat is not paired, the user will receive "Not paired. Send /start to get a pairing code." The operator must complete the pairing flow to grant access.

---

## channels.websocket.\<name\>

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `agentId` | string | required | Agent ID this channel routes messages to |
| `port` | integer | `37474` | Port the Gateway WebSocket server listens on |

The WebSocket channel enables `klaw chat` — an interactive split-screen TUI that routes messages through the Gateway's WebSocket endpoint at `ws://localhost:<port>/ws/chat`.

**Session:** All WebSocket messages use the fixed chatId `local_ws_default`. This session persists across `klaw chat` invocations like any other channel — history is JSONL-logged and searchable.

**Allow policy:** `local_ws_default` is implicitly allowed — no pairing needed.

To enable via `klaw init`, answer `y` at the "WebSocket chat setup" phase. To enable manually, add to `gateway.json`:

```json
{
  "channels": {
    "websocket": {
      "local": {
        "agentId": "default",
        "port": 37474
      }
    }
  }
}
```

Then restart the gateway: `klaw service restart gateway`

---

## channels.discord.\<name\>

Each named Discord channel instance routes to one agent.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `agentId` | string | required | Agent ID this channel routes messages to |
| `token` | string | required | Discord bot token (env var `${KLAW_DISCORD_TOKEN}`) |
| `allowedGuilds` | list of objects | `[]` | Guild-level access control list |
| `apiBaseUrl` | string | `null` | Custom API base URL (testing only, omit in production) |

### allowedGuilds — Guild & User Access Control

The `allowedGuilds` list controls which Discord servers, channels, and users can interact with the bot. This is the primary access control mechanism — messages from unlisted guilds or users are silently dropped.

Each entry is an object:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `guildId` | string | required | Discord guild (server) ID |
| `allowedChannelIds` | list of strings | `[]` | Restrict to specific channel IDs (empty = all channels in guild) |
| `allowedUserIds` | list of strings | `[]` | Allowed user IDs (empty = deny all users) |

**Restrictive by default:**
- **Empty `allowedGuilds`** — denies all messages. No guild can interact with the bot.
- **Empty `allowedUserIds`** within a guild — denies all users in that guild, even if the guild is listed.
- **Empty `allowedChannelIds`** — allows all channels within the guild.

**Direct messages (DMs):** For DMs (no guild context), the bot checks if the sender's user ID appears in any guild's `allowedUserIds`. If not found in any guild, the DM is rejected.

### Discord Example

```json
{
  "channels": {
    "discord": {
      "main": {
        "agentId": "default",
        "token": "${KLAW_DISCORD_TOKEN}",
        "allowedGuilds": [
          {
            "guildId": "123456789012345678",
            "allowedChannelIds": [],
            "allowedUserIds": ["987654321098765432"]
          },
          {
            "guildId": "111222333444555666",
            "allowedChannelIds": ["777888999000111222"],
            "allowedUserIds": ["987654321098765432", "112233445566778899"]
          }
        ]
      }
    }
  }
}
```

### Pairing Flow

New users pair with the bot using the `/start` command:

1. User sends `/start` in a Discord channel where the bot is present
2. Bot replies with a 6-character pairing code and instructions
3. Operator runs `klaw channels pair discord <code>` on the server
4. The guild, channel, and user are added to `allowedGuilds` in `gateway.json`
5. Gateway detects the config change and reloads the allowlist

To unpair a guild: `klaw channels unpair discord <guildId>`

### chatId Format

Discord chat IDs use the format `discord_{channelId}`:

- Discord channel `123456789012345678` -> `"discord_123456789012345678"`

Use this format in:
- `send_message(chatId="discord_123456789012345678")`
- `schedule_add(injectInto="discord_123456789012345678")`

### Message Limits

Discord has a 2000-character limit per message. Long responses are automatically split into multiple messages at paragraph or line boundaries.

---

## attachments

Image attachment handling for Telegram and Discord.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `directory` | string | `""` | Directory for storing received image attachments. Empty string disables attachment storage. |

When `directory` is set to a valid path (e.g. `/home/klaw/attachments`), images sent in Telegram and Discord messages are saved locally. Images are then available via `file_read` and flow inline to vision-capable models or are auto-described via the vision model for text-only models.

**Disabled by default.** To enable, set `directory` to an absolute or expandable path:

```json
{
  "attachments": {
    "directory": "~/.local/share/klaw/attachments"
  }
}
```

The gateway creates the directory if it doesn't exist. Received images are organized by chat/channel and timestamp.

---

## commands

List of bot commands registered with Telegram via `setMyCommands`. These appear in the command menu shown to users in the Telegram client.

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Command name without `/` prefix (e.g., `"new"`) |
| `description` | string | Short description shown in Telegram's command menu |

Commands are registered on Gateway startup. If registration fails (e.g., network error), a warning is logged and the bot continues without the command menu.

---

## delivery

Delivery reliability settings controlling reconnection behavior, buffer drain budgets, and permanent error handling.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `maxReconnectAttempts` | integer | `0` | Max consecutive connection failures before giving up (`0` = unlimited) |
| `drainBudgetSeconds` | integer | `30` | Max seconds for draining inbound buffer on reconnect (`0` = unlimited) |
| `channelDrainBudgetSeconds` | integer | `30` | Max seconds for draining per-channel outbound buffer (`0` = unlimited) |

**`maxReconnectAttempts`** — Controls how many times the gateway retries connecting to the engine after consecutive failures. The counter resets on successful connection. Set to `0` (default) for unlimited retries — recommended for production since the engine may restart during updates.

**`drainBudgetSeconds`** — When the gateway reconnects to the engine, it drains all buffered inbound messages. With a large buffer, this could block the connection. The budget limits drain time; remaining messages stay buffered for the next drain cycle. Note: the engine-side outbound drain budget is fixed at 30 seconds and is not configurable via this field.

**`channelDrainBudgetSeconds`** — Same as above but for per-channel outbound buffers (e.g., when a Telegram channel comes back online after being unreachable).

**Permanent error detection** — The gateway automatically detects permanent delivery errors from Telegram (bot blocked, chat not found, insufficient permissions) and drops those messages instead of retrying indefinitely. This requires no configuration.

---

## webui

Browser-based dashboard and REST API.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `true` | Enable the Web UI and REST API |
| `apiToken` | string | `""` | Bearer token for API authentication. Empty = no auth |

When enabled, the Gateway serves the Web UI at `http://localhost:<port>` (same port as `channels.localWs.port`). The REST API is available at `/api/v1`.

When `apiToken` is set, all API requests must include `Authorization: Bearer <token>`. Environment variable substitution is supported: `"apiToken": "${KLAW_WEBUI_TOKEN}"`.

See `doc/webui/overview.md` for page descriptions and `doc/webui/rest-api.md` for the full API reference.

```json
{
  "webui": {
    "enabled": true,
    "apiToken": "${KLAW_WEBUI_TOKEN}"
  }
}
```
