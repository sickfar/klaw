# Klaw Documentation

## What is Klaw

Klaw is a lightweight AI agent running on Raspberry Pi 5. It uses a two-process architecture: the **Gateway** handles messaging (Telegram, Discord), while the **Engine** handles LLM orchestration, memory, scheduling, and tool execution. The agent operates through tool calls — it reads files, executes code, searches memory, and schedules tasks via structured function calls.

## How to use these docs

- `docs_search "query"` — semantic search across all documentation
- `docs_list` — browse all available topics
- `docs_read "path/file.md"` — read a specific file in full

Natural language queries work well. Example: `docs_search "how do I schedule a task"`.

## What I can do

1. Remember facts across sessions via memory tools (`memory_core_set`, `memory_save`, `memory_search`)
2. Read and write files in the workspace (`file_read`, `file_write`, `file_list`)
3. Run code in a Docker sandbox (`code_exec`)
4. Schedule recurring tasks with cron expressions (`schedule_add`, `schedule_list`, `schedule_remove`)
5. Load skill documentation on demand (`skill_load`, `skill_list`)
6. Spawn subagents for parallel or background work (`subagent_spawn`)
7. Send messages to other channels or users (`message_send`)

## Deployment docs (for setup and maintenance questions)

- `doc/deployment/docker-quickstart.md` — first-time Docker setup (no JDK needed)
- `doc/deployment/native-quickstart.md` — first-time native setup (Java 21+, no Docker)
- `doc/deployment/building.md` — how to build artifacts (`assembleDist`, `scripts/build.sh`)
- `doc/deployment/deploying.md` — deploy to Raspberry Pi, systemd services, `scripts/deploy.sh`
- `doc/deployment/local-dev.md` — Docker Compose local dev, `./klaw` wrapper, config setup
- `doc/deployment/releasing.md` — cut a release via git tags, GitHub Actions workflow, Docker image publishing

## When to consult docs

- Before using a tool with unfamiliar parameters
- When a tool call returns an unexpected error
- When the user asks about a specific capability
- When setting up a scheduled task or subagent
- When the user asks how to build, deploy, or run Klaw
