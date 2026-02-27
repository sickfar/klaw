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

1. **LLM provider base URL** — default: `https://open.bigmodel.cn/api/paas/v4` (GLM)
2. **LLM API key**
3. **Model ID** — default: `glm/glm-4-plus`
4. **Telegram bot token**
5. **Allowed chat IDs** — comma-separated, or leave blank to allow all
6. **Agent name** — default: `Klaw`
7. **Personality traits** — e.g. "curious, analytical, warm"
8. **Primary role** — e.g. "personal assistant"
9. **User description** — tell the agent about yourself
10. **Specialized domains** — optional

After answering, it writes config files to `~/.config/klaw/`, starts the engine, generates identity files, and installs service units.

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
├── engine.yaml     # engine config (LLM settings, memory, etc.)
├── gateway.yaml    # gateway config (Telegram token, allowed chats)
└── .env            # API keys (0600 permissions)

~/.local/state/klaw/
├── engine.sock     # Unix socket for CLI ↔ engine communication
└── gateway-buffer.jsonl
```

---

## Service management

Services are managed via your platform's init system:

### Linux (systemd)

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

### macOS (launchd)

```bash
klaw engine start
klaw engine stop
klaw engine restart
klaw stop
```

Under the hood, these run `launchctl start/stop io.github.klaw.klaw-engine` etc.

Plist files are in `~/Library/LaunchAgents/` — they load automatically on login.

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
klaw memory show         # show core memory
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
$EDITOR ~/.config/klaw/engine.yaml
```

Restart engine after config changes:

```bash
klaw engine restart
```
