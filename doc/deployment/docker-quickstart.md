# Docker Quick Start

Run Klaw with Docker — no git, no JDK, no Gradle needed. Just Docker.

## Prerequisites

- Docker Desktop (or Docker Engine on Linux)
- A Telegram bot token — create one via [@BotFather](https://t.me/BotFather)
- An LLM API key (GLM-4, DeepSeek, Qwen, or any OpenAI-compatible provider)

---

## Option 1: One-liner setup (recommended)

```bash
docker run -it --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v klaw-config:/home/klaw/.config/klaw \
  -v klaw-state:/home/klaw/.local/state/klaw \
  -v klaw-data:/home/klaw/.local/share/klaw \
  -v klaw-cache:/home/klaw/.cache/klaw \
  -v klaw-workspace:/workspace \
  ghcr.io/sickfar/klaw-cli:latest init
```

Containers run as the non-root `klaw` user (UID 10001). The wizard asks for your API keys and agent identity, then starts the engine and gateway containers automatically.

## Option 2: Install the `klaw` wrapper

Add `klaw` to your PATH so you don't need the full `docker run` command every time:

```bash
bash <(curl -sSL https://raw.githubusercontent.com/sickfar/klaw/main/scripts/get-klaw.sh) install
```

This creates `~/.local/bin/klaw` — a thin shell wrapper around the `docker run` command. Make sure `~/.local/bin` is in your PATH:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

Then run:

```bash
klaw init
```

---

## What `klaw init` will ask

1. **Docker image tag** — default: `latest` (Docker mode is auto-detected; no mode selection prompt)
2. **LLM provider base URL** — default: `https://api.z.ai/api/coding/paas/v4` (GLM)
3. **LLM API key**
4. **Model ID** — default: `zai/glm-5`
5. **Telegram bot token**
6. **Allowed chat IDs** — comma-separated, or leave blank to allow all
7. **Agent name** — default: `Klaw`
8. **Personality traits** — e.g. "curious, analytical, warm"
9. **Primary role** — e.g. "personal assistant"
10. **User description** — tell the agent about yourself
11. **Specialized domains** — optional

After answering, it writes config files (including `deploy.conf`) to the `klaw-config` volume, starts the engine, generates identity files, then starts both containers.

---

## Volume layout

| Volume | Mount path | Contents |
|--------|-----------|----------|
| `klaw-config` | `/home/klaw/.config/klaw` | `engine.json`, `gateway.json`, `.env` (API keys) |
| `klaw-state` | `/home/klaw/.local/state/klaw` | `gateway-buffer.jsonl`, `engine-outbound-buffer.jsonl`, logs |
| `klaw-data` | `/home/klaw/.local/share/klaw` | `klaw.db`, `scheduler.db`, conversations, memory |
| `klaw-cache` | `/home/klaw/.cache/klaw` | ONNX embedding model (~80 MB, auto-downloaded) |
| `klaw-workspace` | `/workspace` | `SOUL.md`, `IDENTITY.md`, `skills/` |

Volumes persist across container restarts. Data is never lost when stopping or upgrading.

---

## Web UI

After setup, open the Web UI in a browser:

```
http://localhost:37474
```

The dashboard shows engine status, active sessions, and LLM usage. You can also chat with the agent, manage memory, scheduled tasks, skills, and configuration — all from the browser.

> The Web UI is enabled by default. To disable it, set `"webui": {"enabled": false}` in `gateway.json`. See `doc/webui/overview.md` for authentication and configuration details.

---

## Daily commands

After setup, use the `klaw` wrapper (or the full `docker run` command):

```bash
klaw status              # check engine status
klaw doctor              # diagnose installation issues
klaw logs                # recent conversation messages
klaw logs --follow       # stream messages live
klaw service restart engine  # restart the engine container
klaw service restart gateway # restart the gateway container
klaw service stop all        # stop both engine and gateway
klaw memory categories list  # list memory categories
klaw status --sessions   # include active sessions
```

---

## Service restart behavior

Both engine and gateway containers have `restart: unless-stopped`. They restart automatically after:
- Docker daemon restart
- Host reboot (if Docker is configured to start on boot)

To disable auto-restart for a service:

```bash
docker update --restart=no klaw-engine
```

---

## Updating

```bash
# Pull latest images
docker pull ghcr.io/sickfar/klaw-engine:latest
docker pull ghcr.io/sickfar/klaw-gateway:latest
docker pull ghcr.io/sickfar/klaw-cli:latest

# Restart services to use new images
klaw service restart engine
klaw service restart gateway
```

Or stop and let Docker Compose recreate the containers:

```bash
klaw service stop all
klaw service start all
```

---

## Troubleshooting

**Engine doesn't start:**

```bash
docker logs klaw-engine
```

**Gateway can't reach engine:**

The gateway has a reconnect loop — it retries automatically. Check:

```bash
docker logs klaw-gateway
```

**`klaw` command not found:**

Ensure `~/.local/bin` is in your PATH. Add to `~/.bashrc` or `~/.zshrc`:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

**Config files need updating:**

Edit files inside the volume by running a temporary container:

```bash
docker run -it --rm -v klaw-config:/config alpine sh
# then edit /config/engine.json
```

Or use `klaw config set KEY VALUE` for individual changes:

```bash
klaw config set routing.default glm/glm-5
```
