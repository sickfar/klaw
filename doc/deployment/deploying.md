# Deploying to Raspberry Pi

## Prerequisites

- Artifacts built in `build/dist/` (run `./scripts/build.sh` first)
- SSH access to the Pi
- Pi hostname: `sickfar-pi.local` (default) or set `KLAW_PI_HOST`

## Deploy artifacts

```bash
./scripts/deploy.sh
```

With a custom host or user:

```bash
KLAW_PI_HOST=192.168.1.42 KLAW_PI_USER=pi ./scripts/deploy.sh
```

### What deploy.sh does

1. Resolves `build/dist/klaw-gateway-*.jar` and `build/dist/klaw-engine-*.jar`
2. SSH: creates `~/.local/share/klaw/bin/` on the Pi
3. SSH: stops `klaw-gateway` and `klaw-engine` services gracefully
4. rsync: uploads `klaw-gateway.jar` and `klaw-engine.jar` to `~/.local/share/klaw/bin/`
5. rsync: uploads `klaw-linuxArm64` to `~/.local/bin/klaw` (if built)
6. SSH: starts both services

## First-time Pi setup

Run once from the repo directory **on the Pi** (after cloning or copying the repo):

```bash
./scripts/install.sh
```

This copies `deploy/klaw-gateway.service` and `deploy/klaw-engine.service` to `~/.config/systemd/user/`, runs `systemctl --user daemon-reload`, and enables both services.

Then initialize Klaw's data directories:

```bash
klaw init
```

`klaw init` creates the XDG data directories and writes config templates.

## Config on Pi

```bash
# Templates are created by klaw init, or copy manually:
cp ~/.config/klaw/engine.yaml.example ~/.config/klaw/engine.yaml
cp ~/.config/klaw/gateway.yaml.example ~/.config/klaw/gateway.yaml

# Edit with your LLM provider and Telegram credentials:
nano ~/.config/klaw/engine.yaml
nano ~/.config/klaw/gateway.yaml
```

## Start services

```bash
systemctl --user start klaw-engine
systemctl --user start klaw-gateway

# Check status:
systemctl --user status klaw-engine klaw-gateway

# Follow logs:
journalctl --user -u klaw-engine -f
journalctl --user -u klaw-gateway -f
```

## Service management

```bash
# Stop all:
systemctl --user stop klaw-gateway klaw-engine

# Restart engine only (gateway reconnects automatically):
systemctl --user restart klaw-engine

# Disable autostart:
systemctl --user disable klaw-gateway klaw-engine
```

## Systemd unit details

Services are installed as **user** units (`~/.config/systemd/user/`), not system units. They start when the user session starts and run without root.

| Unit | Memory limit | Log identifier |
|------|-------------|----------------|
| `klaw-engine.service` | `-Xmx512m` | `klaw-engine` |
| `klaw-gateway.service` | `-Xmx128m` | `klaw-gateway` |

The engine must start before the gateway. `Wants=klaw-engine.service` in the gateway unit ensures systemd attempts to start the engine first; the gateway's reconnect loop handles any startup race.

## Checking logs on Pi

```bash
# System journal (real-time):
journalctl --user -u klaw-engine -f
journalctl --user -u klaw-gateway -f

# Log files:
tail -f ~/.local/state/klaw/logs/engine.log
tail -f ~/.local/state/klaw/logs/gateway.log
```

## Verifying the socket

```bash
# engine.sock exists while Engine is running:
ls -la ~/.local/state/klaw/engine.sock
# Expected: srw------- (socket file, permissions 600)

# If socket is missing, engine is not running:
systemctl --user start klaw-engine
```

> **Hybrid mode note:** When running engine and gateway in Docker with a native CLI, the socket is located in `~/.local/state/klaw/run/engine.sock` (the `run/` subdirectory) and uses `666` permissions so all containers can access it.

## Updating to a new version

```bash
# On dev machine:
./scripts/build.sh
./scripts/deploy.sh    # stops services, uploads, restarts automatically
```
