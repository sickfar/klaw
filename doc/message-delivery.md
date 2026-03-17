# Message Delivery Recovery

Klaw uses three layers of buffering to ensure messages are not lost when components are temporarily unavailable. All buffering is automatic — no operator intervention is needed.

## Delivery Layers

### 1. Engine to Gateway (file buffer)

When the Gateway is disconnected from the Engine (e.g., gateway restart, network issue), outbound messages from the Engine are buffered to disk:

- **File:** `~/.local/state/klaw/engine-outbound-buffer.jsonl`
- **Capacity:** 10,000 messages (oldest dropped when exceeded)
- **Drain:** Automatic when the Gateway reconnects
- **Durability:** File-based, survives Engine restarts, corruption-tolerant (malformed lines are skipped)

### 2. Gateway to Channel (memory buffer)

When a channel is not alive (e.g., no WebSocket client connected, Telegram API unreachable), outbound messages are buffered per-channel in Gateway memory:

- **Capacity:** 100 messages per channel (oldest dropped when exceeded)
- **Drain:** Automatic when the channel becomes alive again
- **Durability:** In-memory only — lost on Gateway restart

Channel liveness:

| Channel | Alive when |
|---------|-----------|
| Local WebSocket (`local_ws`) | A WebSocket client is connected (`klaw chat` or web UI) |
| Telegram | Bot is started and last send succeeded |

### 3. Channel to Engine (file buffer)

When the Engine is unreachable, inbound messages from users are buffered by the Gateway to disk:

- **File:** `~/.local/state/klaw/gateway-buffer.jsonl`
- **Drain:** Automatic when the Engine becomes reachable again
- **Durability:** File-based, survives Gateway restarts

This is the existing `GatewayBuffer` — it handles the reverse direction (user messages waiting for the Engine).

## End-to-End Flow

```
User ──► Channel ──► Gateway ──► Engine
                        │            │
                   [Layer 3]    [Layer 1]
                   inbound      outbound
                   file buf     file buf

Engine ──► Gateway ──► Channel ──► User
               │           │
          [Layer 1]   [Layer 2]
          outbound    per-channel
          file buf    memory buf
```

## Practical Scenarios

**Gateway restarts:** The Engine buffers outbound messages to `engine-outbound-buffer.jsonl`. When the Gateway reconnects, buffered messages are delivered. Inbound messages from users are also buffered by the Gateway's own file buffer if it restarts while the Engine is running.

**WebSocket client disconnects:** If a `klaw chat` session ends while the agent is mid-response, the Gateway buffers remaining reply chunks in memory. When the user reconnects with `klaw chat`, buffered messages are delivered immediately.

**Telegram API outage:** If Telegram is temporarily unreachable, the Gateway buffers outbound messages for the Telegram channel. When connectivity is restored and a send succeeds, the buffer drains.

**Engine restarts:** The Gateway's inbound file buffer (`gateway-buffer.jsonl`) holds user messages until the Engine is back. The Engine's outbound buffer survives restarts since it is file-based.

## Limitations

- Gateway-to-channel buffers are in-memory (Layer 2). A Gateway restart loses pending per-channel messages. File-based buffers (Layers 1 and 3) survive restarts.
- Buffer capacities are fixed: 10,000 (Engine outbound), 100 per channel (Gateway outbound). Extended outages beyond these limits will drop oldest messages.
