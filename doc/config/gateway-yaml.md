# Gateway Configuration: gateway.yaml

**Location:** `~/.config/klaw/gateway.yaml`

This file is read by the Gateway process on startup. It is read-only from the agent's perspective — the agent cannot modify it. The user must edit this file directly to change channel settings.

---

## Full Example

```yaml
channels:
  telegram:
    token: "your-bot-token"
    allowedChatIds:
      - "telegram_123456789"
      - "telegram_987654321"
  console:
    enabled: true
    port: 37474
  discord:
    enabled: false
    token: null

commands:
  - name: "new"
    description: "Start a new conversation"
  - name: "model"
    description: "Show or change the active model"
  - name: "status"
    description: "Show agent status"
  - name: "help"
    description: "List available commands"
```

---

## channels.telegram

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `token` | string | required | Telegram Bot API token from @BotFather |
| `allowedChatIds` | list of strings | `[]` | Whitelist for unsolicited outbound delivery |

### allowedChatIds — Outbound Whitelist

The `allowedChatIds` list controls which chats the agent can **proactively message** (via the `send_message` tool or `injectInto` on scheduled tasks):

- **Empty list** — accepts all inbound messages but **rejects all unsolicited outbound**. This is the safe default that prevents the agent from messaging arbitrary people.
- **Non-empty list** — only chats in this list receive proactively-sent messages.

### Implicit Allow for Replies

When a user sends a message, their `chatId` is temporarily allowed for that conversation session even if it is not in the whitelist. This lets the agent reply to any user without requiring the operator to pre-add every chat ID.

### chatId Format

Chat IDs use the format `{channel}_{platformId}`:

- Telegram chat `123456` → `"telegram_123456"`

Use this exact format in:
- `send_message(chatId="telegram_123456")`
- `schedule_add(injectInto="telegram_123456")`

### What to do when send_message is blocked

If the agent reports `"chatId not in allowedChatIds"`, the target chat must be added to `gateway.yaml` by the operator. The agent cannot modify this file.

---

## channels.console

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable the terminal console channel (`klaw chat`) |
| `port` | integer | `37474` | Port the Gateway WebSocket server listens on |

The console channel enables `klaw chat` — an interactive split-screen TUI that routes messages through the Gateway's `/chat` WebSocket endpoint at `ws://localhost:<port>/chat`.

**Disabled by default.** The Gateway always registers the `/chat` endpoint, but rejects all connections unless `enabled: true`.

**Session:** All console messages use the fixed chatId `console_default`. This session persists across `klaw chat` invocations like any other channel — history is JSONL-logged and searchable.

**Allow policy:** `console_default` is implicitly allowed for outbound delivery — no entry in `allowedChatIds` is needed. Other chatIds are blocked on the console channel.

To enable via `klaw init`, answer `y` at the "WebSocket chat setup" phase. To enable manually:

```yaml
channels:
  console:
    enabled: true
    port: 37474   # default; change if port is already in use
```

Then restart the gateway: `klaw gateway restart`

---

## channels.discord

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable Discord channel |
| `token` | string | `null` | Discord bot token (post-MVP) |

Set `enabled: true` to activate Discord support (post-MVP feature).

---

## commands

List of bot commands registered with Telegram via `setMyCommands`. These appear in the command menu shown to users in the Telegram client.

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Command name without `/` prefix (e.g., `"new"`) |
| `description` | string | Short description shown in Telegram's command menu |

Commands are registered on Gateway startup. If registration fails (e.g., network error), a warning is logged and the bot continues without the command menu.
