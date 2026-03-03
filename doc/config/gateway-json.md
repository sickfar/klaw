# Gateway Configuration: gateway.json

**Location:** `~/.config/klaw/gateway.json`

This file is read by the Gateway process on startup. It is read-only from the agent's perspective — the agent cannot modify it. The user must edit this file directly to change channel settings.

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

## Full Example

```json
{
  "channels": {
    "telegram": {
      "token": "your-bot-token",
      "allowedChats": [
        {"chatId": "telegram_123456789", "allowedUserIds": ["12345"]},
        {"chatId": "telegram_987654321"}
      ]
    },
    "console": {
      "enabled": true,
      "port": 37474
    },
    "discord": {
      "enabled": false
    }
  },
  "commands": [
    {"name": "new", "description": "Start a new conversation"},
    {"name": "model", "description": "Show or change the active model"},
    {"name": "status", "description": "Show agent status"},
    {"name": "help", "description": "List available commands"}
  ]
}
```

---

## channels.telegram

| Field | Type | Default | Description |
|-------|------|---------|-------------|
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
3. Operator runs `klaw pair telegram <code>` on the server
4. The chat and user are added to `allowedChats` in `gateway.json`
5. Gateway detects the config change and reloads the allowlist

To unpair a chat: `klaw unpair telegram <chatId>`

### chatId Format

Chat IDs use the format `{channel}_{platformId}`:

- Telegram chat `123456` → `"telegram_123456"`

Use this exact format in:
- `send_message(chatId="telegram_123456")`
- `schedule_add(injectInto="telegram_123456")`

### What to do when messages are blocked

If a chat is not paired, the user will receive "Not paired. Send /start to get a pairing code." The operator must complete the pairing flow to grant access.

---

## channels.console

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable the terminal console channel (`klaw chat`) |
| `port` | integer | `37474` | Port the Gateway WebSocket server listens on |

The console channel enables `klaw chat` — an interactive split-screen TUI that routes messages through the Gateway's `/chat` WebSocket endpoint at `ws://localhost:<port>/chat`.

**Disabled by default.** The Gateway always registers the `/chat` endpoint, but rejects all connections unless `enabled` is `true`.

**Session:** All console messages use the fixed chatId `console_default`. This session persists across `klaw chat` invocations like any other channel — history is JSONL-logged and searchable.

**Allow policy:** `console_default` is implicitly allowed — no pairing needed. Other chatIds are blocked on the console channel.

To enable via `klaw init`, answer `y` at the "WebSocket chat setup" phase. To enable manually, add to `gateway.json`:

```json
{
  "channels": {
    "console": {
      "enabled": true,
      "port": 37474
    }
  }
}
```

Then restart the gateway: `klaw gateway restart`

---

## channels.discord

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable Discord channel |
| `token` | string | `null` | Discord bot token (post-MVP) |

Set `enabled` to `true` to activate Discord support (post-MVP feature).

---

## commands

List of bot commands registered with Telegram via `setMyCommands`. These appear in the command menu shown to users in the Telegram client.

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Command name without `/` prefix (e.g., `"new"`) |
| `description` | string | Short description shown in Telegram's command menu |

Commands are registered on Gateway startup. If registration fails (e.g., network error), a warning is logged and the bot continues without the command menu.
