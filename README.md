# Klaw

Lightweight AI agent for Raspberry Pi 5, with Chinese LLM support (GLM, DeepSeek, Qwen).

Two-process architecture: **Gateway** handles Telegram/Discord messaging; **Engine** handles LLM orchestration, memory, scheduling, and tool execution.

## Build

```bash
# Compile (skips tests)
./gradlew build -x test

# Run all tests
./gradlew :common:jvmTest :gateway:test :engine:test

# Code quality
./gradlew ktlintCheck detekt
```

**Build requirements:** JDK 21+, Gradle 8.14.4 (via wrapper)

---

## Deploying to Raspberry Pi

### 1. Build deployable artifacts

```bash
./scripts/build.sh
```

Produces in `build/dist/`:
- `klaw-gateway-*.jar` — fat JAR for Gateway service
- `klaw-engine-*.jar` — fat JAR for Engine service
- `klaw-linuxArm64` — CLI binary for Raspberry Pi 5 (arm64)
- `klaw-linuxX64` — CLI binary for Linux x86-64

Or run directly: `./gradlew assembleDist`

### 2. Deploy to Pi

```bash
./scripts/deploy.sh
# or with explicit host/user:
KLAW_PI_HOST=sickfar-pi.local KLAW_PI_USER=pi ./scripts/deploy.sh
```

Uploads JARs to `~/.local/share/klaw/bin/` and CLI to `~/.local/bin/klaw` on the Pi, then restarts services.

### 3. First-time setup on Pi

Run once from the repo directory on the Pi:

```bash
./scripts/install.sh
```

Installs systemd user service units and enables them.

### 4. Run the setup wizard

```bash
klaw init
```

Asks for your LLM API key, Telegram bot token, and agent identity. Creates config files, starts the engine, and enables both services automatically.

### 5. Verify services are running

```bash
systemctl --user status klaw-engine klaw-gateway
```

---

## Local Development with Docker

### Setup

```bash
# 1. Build artifacts (must run first)
./scripts/build.sh

# 2. Run the setup wizard — creates config and starts containers
./klaw init
```

`./klaw init` runs interactively: it asks for your LLM API key, Telegram bot token, and agent identity, writes `config/engine.yaml` and `config/gateway.yaml`, then starts the engine and gateway containers automatically.

`config/` is gitignored — real API keys are never committed.

```bash
# 3. Run CLI commands
./klaw status
./klaw memory show
```

### Useful Docker commands

```bash
docker compose logs -f engine       # tail engine logs
docker compose logs -f gateway      # tail gateway logs
docker compose ps                   # check running containers
docker compose down                 # stop everything
docker compose restart engine       # restart engine only
```

---

## Releasing

Push a version tag to trigger a GitHub Actions release:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The release workflow builds:
- Linux: fat JARs + `klaw-linuxX64` + `klaw-linuxArm64` (on ubuntu-latest)
- macOS: `klaw-macosArm64` + `klaw-macosX64` (on macos-latest)

Artifacts are uploaded to a GitHub Release automatically with generated release notes.

---

## Architecture

```
┌─────────────┐    Unix socket    ┌─────────────┐
│   Gateway   │◄─────────────────►│   Engine    │
│  (Telegram) │   engine.sock     │  (LLM, DB)  │
└─────────────┘                   └─────────────┘
       │                                 │
  conversations/                    klaw.db
  *.jsonl                           scheduler.db
  (source of truth)                 (cache/index)
```

See `doc/` for full documentation (also searchable by Klaw itself via `docs_search`).
