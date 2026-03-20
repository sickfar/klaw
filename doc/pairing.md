# Pairing: Connecting Users to Klaw

Klaw requires pairing before a chat can interact with the bot. This prevents unauthorized users from accessing your agent. Pairing works the same way across all channels (Telegram and Discord).

## How Pairing Works

1. **User sends `/start`** in a Telegram chat or Discord channel
2. **Gateway replies** with a 6-character pairing code and instructions
3. **Operator runs** `klaw pair <channel> <code>` on the server
4. **Gateway detects** the config change and reloads the allowlist
5. **User can now** send messages to the bot

## Pairing a Chat

### Telegram

```bash
# After user sends /start and receives a code like "A7K3M2"
klaw pair telegram A7K3M2
```

This adds the chat and user to `allowedChats` in `gateway.json`.

### Discord

```bash
# After user sends /start and receives a code like "B9X4P1"
klaw pair discord B9X4P1
```

This adds the guild, channel, and user to `allowedGuilds` in `gateway.json`.

### What happens during pairing

The command:
- Validates the code and checks expiry (5 minutes)
- Adds the chat/guild and user to the appropriate allowlist in `gateway.json`
- Removes the used pairing request

## Unpairing

### Telegram

```bash
klaw unpair telegram telegram_123456789
```

This removes the chat entry from `allowedChats` in `gateway.json`.

### Discord

```bash
klaw unpair discord 123456789012345678
```

This removes the guild entry from `allowedGuilds` in `gateway.json`. The argument is the guild (server) ID.

## Group Chats

In group chats (Telegram groups and Discord guild channels), each user must pair individually. When a new user sends `/start` in an already-paired chat, they get their own pairing code. After pairing, their user ID is added to the existing allowlist entry.

## Local WebSocket Channel

The local WebSocket channel (`klaw chat`) is always allowed when enabled -- no pairing needed. It uses the fixed chatId `local_ws_default`.

## Security Model

- **Telegram:** Empty `allowedChats` = deny all; empty `allowedUserIds` within a chat = deny all users
- **Discord:** Empty `allowedGuilds` = deny all; empty `allowedUserIds` within a guild = deny all users; empty `allowedChannelIds` = allow all channels in guild
- **Discord DMs:** The sender must appear in at least one guild's `allowedUserIds`
- Pairing codes expire after 5 minutes
- Rate limit: 1 pairing code per chat per minute
- The `/start` command is never forwarded to the engine

## Config Format

### Telegram

```json
{
  "channels": {
    "telegram": {
      "token": "${KLAW_TELEGRAM_TOKEN}",
      "allowedChats": [
        {
          "chatId": "telegram_123456789",
          "allowedUserIds": ["12345", "67890"]
        }
      ]
    }
  }
}
```

### Discord

```json
{
  "channels": {
    "discord": {
      "enabled": true,
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
}
```

## Files

| File | Description |
|------|-------------|
| `~/.config/klaw/gateway.json` | Stores allowlists (modified by `klaw pair`/`klaw unpair`) |
| `~/.local/state/klaw/pairing_requests.json` | Pending pairing requests (written by gateway, consumed by `klaw pair`) |
