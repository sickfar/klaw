# Where Klaw Data Lives

## engine.sock

**Default path:** `$XDG_STATE_HOME/klaw/engine.sock` (typically `~/.local/state/klaw/engine.sock`)

- Created by Engine on startup, deleted on shutdown
- Only present while Engine is running
- Permissions: `600` (owner read/write only)
- If absent: Engine is not running
- Unix domain socket — Gateway and CLI connect here

## gateway-buffer.jsonl

**Default path:** `$XDG_STATE_HOME/klaw/gateway-buffer.jsonl` (typically `~/.local/state/klaw/gateway-buffer.jsonl`)

- Messages written here when Gateway cannot reach Engine (Engine down or socket unavailable)
- Automatically drained and sent to Engine after Gateway reconnects
- Format: one JSONL line per buffered `SocketMessage`
- Large file means processing backlog — Engine was unreachable for an extended period
- Safe to delete manually when Engine is stopped (messages will be lost)

## How to Check Engine Status

```bash
# Check via CLI
klaw status

# Check via socket file presence
ls ~/.local/state/klaw/engine.sock
```

If `engine.sock` does not exist, Engine is not running. Start it with:

```bash
systemctl --user start klaw-engine
# or directly:
klaw-engine
```

## Logs Directory

**Path:** `$XDG_STATE_HOME/klaw/logs/` (typically `~/.local/state/klaw/logs/`)

Useful for diagnosing startup failures:

```bash
# View recent Engine logs
tail -f ~/.local/state/klaw/logs/engine.log

# View recent Gateway logs
tail -f ~/.local/state/klaw/logs/gateway.log
```
