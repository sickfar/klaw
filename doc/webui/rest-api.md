# REST API Reference

All endpoints are prefixed with `/api/v1`. When `webui.apiToken` is configured, include `Authorization: Bearer <token>` in all requests.

---

## Status & Health

### GET /status

Engine status and health information.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `deep` | boolean | `false` | Include detailed health checks |
| `usage` | boolean | `false` | Include LLM token usage statistics |

**Response:**

```json
{
  "status": "ok",
  "engine": "klaw",
  "uptime": "2h 15m",
  "sessions": 3,
  "health": {
    "database": {"status": "ok"},
    "scheduler": {"status": "ok"}
  },
  "usage": {
    "glm/glm-5": {
      "request_count": 42,
      "prompt_tokens": 15000,
      "completion_tokens": 5000,
      "total_tokens": 20000
    }
  }
}
```

### GET /gateway/health

Gateway health check.

**Response:** `{"status":"ok","channels":2}`

### GET /gateway/channels

List all registered channels and their status.

**Response:** `{"channels":[{"name":"telegram","alive":true},{"name":"localWs","alive":true}]}`

---

## Sessions

### GET /sessions

List active sessions.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `active_minutes` | integer | none | Only sessions active within the last N minutes |
| `verbose` | boolean | `false` | Include token counts |

**Response:**

```json
[
  {
    "chatId": "telegram_123456",
    "model": "glm/glm-5",
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-15T12:30:00Z"
  }
]
```

### DELETE /sessions/cleanup

Remove inactive sessions.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `older_than_minutes` | integer | `1440` | Remove sessions idle longer than N minutes |

**Response:** `{"deleted":2,"message":"Removed 2 inactive sessions"}`

---

## Memory

### GET /memory/categories

List memory categories with entry counts.

**Response:**

```json
{
  "categories": [
    {"id": 1, "name": "personal", "entryCount": 12, "accessCount": 5}
  ],
  "total": 1
}
```

### GET /memory/search

Search across all memory.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | yes | Search query |
| `top_k` | integer | no | Max results (default: 5) |

### POST /memory/facts

Add a new fact to memory.

**Body:**

```json
{
  "category": "personal",
  "content": "User prefers dark mode"
}
```

### POST /memory/consolidate

Trigger memory consolidation.

**Body (optional):**

```json
{
  "date": "2025-01-15",
  "force": false
}
```

### DELETE /memory/categories/{name}

Delete a category and all its facts.

### PUT /memory/categories/{name}/rename

Rename a category.

**Body:** `{"new_name": "new-category-name"}`

### POST /memory/categories/merge

Merge multiple categories into one.

**Body:** `{"sources": ["cat-a", "cat-b"], "target": "merged"}`

---

## Schedule

### GET /schedule/jobs

List all scheduled jobs.

**Response:**

```json
[
  {
    "name": "daily-summary",
    "cron": "0 0 22 * * ?",
    "prompt": "Summarize today's conversations",
    "enabled": true,
    "nextFireTime": "2025-01-15T22:00:00Z",
    "lastFireTime": "2025-01-14T22:00:00Z"
  }
]
```

### POST /schedule/jobs

Create a new scheduled job.

**Body:**

```json
{
  "name": "daily-summary",
  "cron": "0 0 22 * * ?",
  "message": "Summarize today's conversations"
}
```

Optional fields: `model`, `inject_into`, `channel`.

### PUT /schedule/jobs/{name}

Edit an existing job.

**Body:** Same fields as POST (all optional except `name` in path).

### DELETE /schedule/jobs/{name}

Delete a scheduled job.

### POST /schedule/jobs/{name}/enable

Enable a paused job.

### POST /schedule/jobs/{name}/disable

Pause a job (keeps it in the scheduler but prevents firing).

### POST /schedule/jobs/{name}/run

Trigger immediate execution of a job.

### GET /schedule/jobs/{name}/runs

Get execution history for a job.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limit` | integer | `20` | Max number of runs to return |

**Response:**

```json
[
  {
    "name": "daily-summary",
    "status": "completed",
    "startTime": "2025-01-15T22:00:01Z",
    "endTime": "2025-01-15T22:00:15Z",
    "durationMs": 14000
  }
]
```

### GET /schedule/status

Scheduler status (started, job count).

---

## Skills

### GET /skills

List available skills.

**Response:**

```json
{
  "skills": [
    {"name": "web-search", "description": "Search the web", "source": "bundled"}
  ],
  "total": 3
}
```

### GET /skills/validate

Validate all loaded skills.

---

## Configuration

### GET /config/engine

Get the current engine configuration. API keys are sanitized (replaced with `***`).

### GET /config/gateway

Get the current gateway configuration. Tokens are sanitized.

### PUT /config/engine

Update engine configuration. Sends the full config object.

**Body:** Full `engine.json` content as JSON.

### PUT /config/gateway

Update gateway configuration.

**Body:** Full `gateway.json` content as JSON.

### GET /config/schema/engine

Get the JSON Schema for engine configuration. Used by the Web UI to render the config form dynamically.

### GET /config/schema/gateway

Get the JSON Schema for gateway configuration.

---

## Maintenance

### POST /maintenance/reindex

Trigger a full reindex of conversations and memory.

---

## File Upload

### POST /upload

Upload an image file for use in chat messages.

**Headers:**
- `Content-Type`: `image/jpeg`, `image/png`, `image/gif`, or `image/webp`
- `X-Filename`: Original filename

**Response:** `{"id":"upload-abc123"}` — use this ID as an attachment in chat messages.

---

## WebSocket Chat

### WS /ws/chat

Real-time chat via WebSocket. The frontend connects to `ws://host:port/ws/chat`.

**Outgoing frames (client → server):**

```json
{"type": "user", "content": "Hello", "attachments": ["upload-abc123"]}
```

```json
{"type": "approval_response", "approvalId": "req-123", "approved": true}
```

**Incoming frames (server → client):**

```json
{"type": "assistant", "content": "Hi there!"}
```

```json
{"type": "status", "content": "thinking"}
```

```json
{"type": "approval_request", "content": "Run command: ls -la", "approvalId": "req-123", "riskScore": 3}
```

```json
{"type": "error", "content": "Engine connection lost"}
```
