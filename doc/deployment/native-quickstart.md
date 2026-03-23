# Native Quick Start

Run Klaw without Docker — downloads JARs and CLI binary from GitHub Releases. Requires only Java 21+.

## Prerequisites

- Java 21 or later
- `curl` and `bash`
- A Telegram bot token — create one via [@BotFather](https://t.me/BotFather)
- An LLM API key (GLM-4, DeepSeek, Qwen, or any OpenAI-compatible provider)

Check Java version:

```bash
java -version
# Expected: openjdk version "21" or higher
```

---

## Install

```bash
bash <(curl -sSL https://raw.githubusercontent.com/sickfar/klaw/main/scripts/install-klaw.sh)
```

This script:
1. Detects your platform (Linux x86-64/arm64, macOS Intel/Apple Silicon)
2. Downloads the latest `klaw` CLI binary to `~/.local/bin/klaw`
3. Downloads `klaw-engine.jar` and `klaw-gateway.jar` to `~/.local/share/klaw/bin/`
4. Creates `klaw-engine` and `klaw-gateway` wrapper scripts in `~/.local/bin/`
5. Runs `klaw init` to complete setup

Make sure `~/.local/bin` is in your PATH:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

Add the above line to `~/.bashrc` or `~/.zshrc` to make it permanent.

---

## What `klaw init` will ask

1. **Deployment mode** — "Fully native (systemd/launchd)" or "Docker services" (hybrid). Choose native for a pure systemd/launchd setup; choose Docker services to run Engine and Gateway in Docker containers while keeping the CLI native on the host.
2. **Docker image tag** (hybrid mode only) — default: `latest`
3. **LLM provider base URL** — default: `https://api.z.ai/api/coding/paas/v4` (GLM)
4. **LLM API key**
5. **Model ID** — default: `zai/glm-5`
6. **Telegram bot token**
7. **Allowed chat IDs** — comma-separated, or leave blank to allow all
8. **Agent name** — default: `Klaw`
9. **Personality traits** — e.g. "curious, analytical, warm"
10. **Primary role** — e.g. "personal assistant"
11. **User description** — tell the agent about yourself
12. **Specialized domains** — optional

After answering, it writes config files to `~/.config/klaw/` (including `deploy.conf` with the chosen mode), starts the engine, generates identity files, and installs service units (native) or runs `docker compose up` (hybrid).

---

## File layout after install

```
~/.local/bin/
├── klaw            # CLI binary (Kotlin/Native)
├── klaw-engine     # wrapper: java -jar klaw-engine.jar
└── klaw-gateway    # wrapper: java -jar klaw-gateway.jar

~/.local/share/klaw/bin/
├── klaw-engine.jar
└── klaw-gateway.jar

~/.config/klaw/
├── engine.json         # engine config (LLM settings, memory, etc.)
├── gateway.json        # gateway config (Telegram token, allowed chats)
├── .env                # API keys (0600 permissions)
├── deploy.conf         # deployment mode and docker tag
└── docker-compose.json  # (hybrid mode only) compose file with bind mounts

~/.local/state/klaw/
├── gateway-buffer.jsonl
└── engine-outbound-buffer.jsonl
```

---

## Service management

Service commands route automatically based on the mode stored in `deploy.conf`.

### Native mode — Linux (systemd)

```bash
# Start / stop / restart individual services
klaw service start engine
klaw service stop engine
klaw service restart engine
klaw service start gateway
klaw service stop gateway
klaw service restart gateway

# Stop both at once
klaw service stop all
```

Under the hood, these run `systemctl --user start/stop/restart klaw-engine` etc.

Enable on login (if not already done by `klaw init`):

```bash
systemctl --user enable klaw-engine klaw-gateway
loginctl enable-linger $USER
```

### Native mode — macOS (launchd)

```bash
klaw service start engine
klaw service stop engine
klaw service restart engine
klaw service stop all
```

Under the hood, these run `launchctl start/stop io.github.klaw.klaw-engine` etc.

Plist files are in `~/Library/LaunchAgents/` — they load automatically on login.

### Hybrid mode (Docker services)

If you chose "Docker services" during `klaw init`, the same `klaw service start/stop/restart` commands route through Docker Compose using `~/.config/klaw/docker-compose.json`:

```bash
klaw service start engine    # docker compose -f ~/.config/klaw/docker-compose.json up -d engine
klaw service stop engine     # docker compose -f ~/.config/klaw/docker-compose.json stop engine
klaw service stop all        # stops both engine and gateway containers
```

Data stays on the host filesystem (bind mounts to XDG paths), so you can access config, logs, and databases directly without entering a container.

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

```bash
klaw status              # check engine status
klaw doctor              # diagnose installation issues
klaw logs                # recent conversation messages
klaw logs --follow       # stream messages live
klaw service restart engine  # restart the engine
klaw service restart gateway # restart the gateway
klaw service stop all        # stop both
klaw memory show         # show MEMORY.md
klaw status --sessions   # include active sessions
```

---

## Updating

Re-run the install script to download the latest release:

```bash
bash <(curl -sSL https://raw.githubusercontent.com/sickfar/klaw/main/scripts/install-klaw.sh)
```

The script overwrites the existing binaries and JARs. Your config and data in `~/.config/klaw/` and `~/.local/share/klaw/` are untouched.

After updating, restart services:

```bash
klaw service restart engine
klaw service restart gateway
```

---

## Troubleshooting

**`klaw` command not found:**

```bash
export PATH="$HOME/.local/bin:$PATH"
```

**Engine doesn't start:**

Check the logs:

```bash
journalctl --user -u klaw-engine -n 50   # Linux
# or:
cat ~/Library/Logs/klaw-engine.log        # macOS
```

**Java version too old:**

```bash
# Ubuntu/Debian:
sudo apt install openjdk-21-jre

# Fedora/RHEL:
sudo dnf install java-21-openjdk

# macOS with Homebrew:
brew install openjdk@21
```

**Config needs updating:**

```bash
klaw config set routing.default glm/glm-5
# or edit directly:
$EDITOR ~/.config/klaw/engine.json
```

Restart engine after config changes:

```bash
klaw service restart engine
```
