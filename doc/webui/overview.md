# Web UI

Klaw includes a browser-based dashboard for managing the agent. The Web UI is a single-page application (SPA) built with Nuxt 4 and served directly by the Gateway — no separate web server needed.

## Accessing the Web UI

When the Gateway is running with `webui.enabled: true`, open a browser and navigate to:

```
http://localhost:37474
```

The port is the same as `channels.localWs.port` in `gateway.json` (default: `37474`). If running in Docker, use the host-mapped port.

## Pages

### Chat

Interactive chat with the agent via WebSocket. Messages appear in real time. Features:
- Send messages and receive AI responses
- Slash command autocomplete (`/help`, `/memory`, `/schedule`, `/status`, etc.)
- Session and model switchers
- Thinking indicator while the agent processes
- Tool execution approval banners (when pre-validation is enabled)

### Dashboard

System overview with:
- Engine status (ok/error)
- Uptime
- Active session count
- Health checks grid
- LLM token usage table per model

### Memory

Browse and manage the agent's long-term memory:
- Categories sidebar with entry counts
- Click a category to view its facts
- Add new facts (category + content)
- Delete facts
- Search across all memory

### Schedule

Manage scheduled tasks:
- View all scheduled jobs with cron expressions
- Create new jobs (name, cron expression, prompt)
- Enable/disable jobs
- Trigger immediate execution
- View execution history
- Delete jobs

### Sessions

View and manage active chat sessions:
- Session list with chat ID, model, and last update time
- Click a session to open it in Chat
- Cleanup old sessions

### Skills

Browse loaded agent skills:
- Skill name, description, and source
- Validate individual skills

### Config

View and edit engine and gateway configuration:
- Tabbed interface (Engine / Gateway)
- Dynamic form generated from JSON schema
- Save changes directly from the browser

## Configuration

Add to `gateway.json`:

```json
{
  "webui": {
    "enabled": true,
    "apiToken": ""
  }
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `true` | Enable or disable the Web UI |
| `apiToken` | string | `""` | Bearer token for API authentication. Empty = no auth required |

### Authentication

When `apiToken` is set, all REST API requests must include the `Authorization: Bearer <token>` header. The Web UI stores the token in `localStorage` under the key `klaw_token`.

To set a token:

```json
{
  "webui": {
    "enabled": true,
    "apiToken": "${KLAW_WEBUI_TOKEN}"
  }
}
```

Environment variable substitution (`${VAR}`) is supported. Define the variable in `.env`:

```bash
KLAW_WEBUI_TOKEN=your-secret-token
```

When no token is configured, the API is accessible without authentication. This is fine for local-only deployments where the gateway binds to `127.0.0.1`.

### Security note

The Web UI has full access to the agent's configuration, memory, and scheduled tasks. In production, either:
- Set an `apiToken` and keep it secret
- Bind the gateway to `127.0.0.1` only (default) — not accessible from the network
- Use a reverse proxy with authentication in front of the gateway
