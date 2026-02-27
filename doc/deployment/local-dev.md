# Local Development with Docker

## Which path is right for you?

| Goal | Go to |
|------|-------|
| **Use Klaw (no git, no JDK)** | [Docker Quick Start](docker-quickstart.md) |
| **Use Klaw (Java 21+, no Docker)** | [Native Quick Start](native-quickstart.md) |
| **Use Klaw (native CLI + Docker services)** | [Native Quick Start — Hybrid mode](native-quickstart.md#hybrid-mode-docker-services) |
| **Develop Klaw (build from source)** | Continue reading below |

---

## Developing Klaw from source

`docker compose up` runs Engine + Gateway locally. Both communicate over a shared Docker volume (`klaw-state`) that holds `engine.sock`, mirroring the production Pi setup.

## Setup

### 1. Build artifacts

```bash
./scripts/build.sh
# or: ./gradlew assembleDist
```

The Dockerfiles COPY from `build/dist/` — artifacts must exist before building images.

### 2. Create config

```bash
cp config/engine.yaml.example config/engine.yaml
cp config/gateway.yaml.example config/gateway.yaml
# Fill in API keys in both files
```

`config/` is gitignored — credentials are never committed.

### 3. Start services

```bash
docker compose up -d
```

Starts engine and gateway. Engine starts first (JVM + ONNX model load takes ~5–10s). The gateway's reconnect loop handles the startup race automatically.

### 4. Use the CLI

```bash
./klaw status          # send a command to the engine
./klaw memory show     # show memory
./klaw --help          # list available commands
```

The `./klaw` wrapper runs `docker compose run --rm cli "$@"` — each invocation creates a fresh container and removes it on exit.

## Docker services

| Service | Base image | Purpose |
|---------|-----------|---------|
| `engine` | `eclipse-temurin:21-jre-jammy` | LLM orchestration, memory, tools |
| `gateway` | `eclipse-temurin:21-jre-alpine` | Telegram/Discord message transport |
| `cli` | `debian:bookworm-slim` | CLI commands via Unix socket |

`engine` uses Ubuntu Jammy (glibc) because ONNX Runtime and DJL HuggingFace Tokenizers require glibc. `gateway` uses Alpine (musl is fine for pure JVM). `cli` uses Debian bookworm-slim because Kotlin/Native `linuxX64` binaries link against glibc.

## Named volumes

| Volume | Mount path | Contents |
|--------|-----------|----------|
| `klaw-state` | `/root/.local/state/klaw` | `engine.sock`, `gateway-buffer.jsonl` |
| `klaw-data` | `/root/.local/share/klaw` | `klaw.db`, `scheduler.db`, conversations, memory |
| `klaw-workspace` | `/workspace` | `SOUL.md`, `IDENTITY.md`, `memory/`, `skills/` |

Config (`./config/`) is bind-mounted read-only for engine and gateway; writable for cli (so `klaw init` can write templates).

## Common commands

```bash
# Tail logs:
docker compose logs -f engine
docker compose logs -f gateway

# Status:
docker compose ps

# Stop and remove containers (volumes preserved):
docker compose down

# Stop and remove everything including volumes (destructive!):
docker compose down -v

# Rebuild images after code change:
./scripts/build.sh && docker compose build

# Restart engine:
docker compose restart engine
```

## Verifying socket is up

```bash
# After ~10s from startup, engine.sock should exist:
docker compose exec gateway ls /root/.local/state/klaw/
# Expected: engine.sock  gateway-buffer.jsonl
```

## klaw init in Docker

`klaw init` detects it is running inside a container via `/.dockerenv` and automatically sets deployment mode to Docker — no mode selection prompt is shown. It prompts for a Docker image tag (default: `latest`) and routes service management through Docker Compose instead of systemd/launchd.

```bash
./klaw init
```

**What happens in Docker mode:**

- **Phase 2 (Deployment mode):** auto-set to Docker; prompts for docker image tag only
- **Phase 7 (Config generation):** writes `deploy.conf` with `mode=docker` and the chosen tag
- **Phase 8 (Engine auto-start):** runs `docker compose -f /app/docker-compose.yml up -d engine` then polls `engine.sock` as usual
- **Phase 11 (Service setup):** runs `docker compose -f /app/docker-compose.yml up -d engine gateway` — no systemd unit files are written

The compose file is mounted read-only into the CLI container at `/app/docker-compose.yml` (see `docker-compose.yml` → cli volumes). The Docker socket `/var/run/docker.sock` is also mounted so the CLI can issue compose commands to the host daemon.

After `klaw init` completes, both containers will be running and the Telegram gateway will be connected.

> **Note:** Data directories are created inside the `klaw-data` Docker volume, not on the host filesystem.

## Differences from production (Pi)

| Aspect | Docker (local dev) | Pi (production) |
|--------|-------------------|-----------------|
| CLI binary | `klaw-linuxX64` (x86-64 container) | `klaw-linuxArm64` (native) |
| Services | `docker compose up/down` | `systemctl --user start/stop` |
| Config | `./config/*.yaml` (bind-mount) | `~/.config/klaw/*.yaml` |
| Data | Docker named volumes | `~/.local/share/klaw/` |
| Logs | `docker compose logs` | journald + log files |
