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
5. **Model ID** — default: `glm/glm-4-plus`
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
└── gateway-buffer.jsonl
```

---

## Service management

Service commands route automatically based on the mode stored in `deploy.conf`.

### Native mode — Linux (systemd)

```bash
# Start / stop / restart individual services
klaw engine start
klaw engine stop
klaw engine restart
klaw gateway start
klaw gateway stop
klaw gateway restart

# Stop both at once
klaw stop
```

Under the hood, these run `systemctl --user start/stop/restart klaw-engine` etc.

Enable on login (if not already done by `klaw init`):

```bash
systemctl --user enable klaw-engine klaw-gateway
loginctl enable-linger $USER
```

### Native mode — macOS (launchd)

```bash
klaw engine start
klaw engine stop
klaw engine restart
klaw stop
```

Under the hood, these run `launchctl start/stop io.github.klaw.klaw-engine` etc.

Plist files are in `~/Library/LaunchAgents/` — they load automatically on login.

### Hybrid mode (Docker services)

If you chose "Docker services" during `klaw init`, the same `klaw engine start/stop/restart` commands route through Docker Compose using `~/.config/klaw/docker-compose.json`:

```bash
klaw engine start    # docker compose -f ~/.config/klaw/docker-compose.json up -d engine
klaw engine stop     # docker compose -f ~/.config/klaw/docker-compose.json stop engine
klaw stop            # stops both engine and gateway containers
```

Data stays on the host filesystem (bind mounts to XDG paths), so you can access config, logs, and databases directly without entering a container.

---

## Daily commands

```bash
klaw status              # check engine status
klaw doctor              # diagnose installation issues
klaw logs                # recent conversation messages
klaw logs --follow       # stream messages live
klaw engine restart      # restart the engine
klaw gateway restart     # restart the gateway
klaw stop                # stop both
klaw memory show         # show MEMORY.md
klaw sessions            # list active sessions
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
klaw engine restart
klaw gateway restart
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
klaw engine restart
```
