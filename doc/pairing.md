# Pairing: Connecting Users to Klaw

Klaw requires pairing before a chat can interact with the bot. This prevents unauthorized users from accessing your agent.

## How Pairing Works

1. **User sends `/start`** in the Telegram chat
2. **Gateway replies** with a 6-character pairing code and instructions
3. **Operator runs** `klaw pair telegram <code>` on the server
4. **Gateway detects** the config change and reloads the allowlist
5. **User can now** send messages to the bot

## Pairing a Chat

```bash
# After user sends /start and receives a code like "A7K3M2"
klaw pair telegram A7K3M2
```

The command:
- Validates the code and checks expiry (5 minutes)
- Adds the chat and user to `allowedChats` in `gateway.json`
- Removes the used pairing request

## Unpairing a Chat

```bash
klaw unpair telegram telegram_123456789
```

This removes the chat entry from `allowedChats` in `gateway.json`.

## Group Chats

In group chats, each user must pair individually. When a new user sends `/start` in an already-paired group, they get their own pairing code. After pairing, their user ID is added to the existing chat's `allowedUserIds`.

## Local WebSocket Channel

The local WebSocket channel (`klaw chat`) is always allowed when enabled — no pairing needed. It uses the fixed chatId `local_ws_default`.

## Security Model

- **Empty `allowedChats`** = deny all (restrictive by default)
- **Empty `allowedUserIds`** within a chat = deny all users in that chat
- Pairing codes expire after 5 minutes
- Rate limit: 1 pairing code per chat per minute
- The `/start` command is never forwarded to the engine

## Config Format

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

## Files

| File | Description |
|------|-------------|
| `~/.config/klaw/gateway.json` | Stores `allowedChats` (modified by `klaw pair`/`klaw unpair`) |
| `~/.local/state/klaw/pairing_requests.json` | Pending pairing requests (written by gateway, consumed by `klaw pair`) |
