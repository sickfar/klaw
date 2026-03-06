# sandbox_exec

Execute bash scripts in an isolated Docker container with workspace access.

Use for: data processing, file downloads (curl/wget), file transformations, computations, parsing, script testing, and any operation on workspace files. The host workspace directory is automatically mounted at `/workspace` inside the container, allowing the code to read and write project files. `python3` is available in the container and can be called from bash scripts.

Use `host_exec` only when you need direct host system access (hardware monitoring, system service management, Docker commands, host network diagnostics).

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `code` | string | yes | — | Bash script to execute |
| `timeout` | int | no | 30 | Timeout in seconds |

## Configuration

In `engine.json` under `codeExecution`:

```json
{
  "codeExecution": {
    "dockerImage": "python:3.12-slim-bookworm",
    "timeout": 30,
    "allowNetwork": false,
    "maxMemory": "256m",
    "maxCpus": "1.0",
    "readOnlyRootfs": true,
    "keepAlive": false,
    "keepAliveIdleTimeoutMin": 5,
    "keepAliveMaxExecutions": 100
  }
}
```

| Field | Description |
|-------|-------------|
| `dockerImage` | Docker image name for the sandbox container |
| `timeout` | Default execution timeout in seconds |
| `allowNetwork` | Allow network access inside the sandbox container |
| `maxMemory` | Docker memory limit (e.g., `128m`, `256m`) |
| `maxCpus` | Docker CPU limit (e.g., `0.5`, `1.0`) |
| `readOnlyRootfs` | Mount root filesystem as read-only |
| `keepAlive` | Reuse container across executions (vs. one-shot per execution) |
| `keepAliveIdleTimeoutMin` | Minutes of idle time before container is stopped (keep-alive mode) |
| `keepAliveMaxExecutions` | Max executions before container is recycled (keep-alive mode) |

## Workspace Access

The host workspace directory (`$KLAW_WORKSPACE`, default `~/klaw-workspace`) is automatically mounted at `/workspace` inside the container with read-write access. Code running in the sandbox can:

- Read project files: `cat /workspace/data/input.csv`
- Write output files: `python3 -c "open('/workspace/output.json','w').write(...)"`
- Process and transform workspace data
- Download files into the workspace: `curl -o /workspace/data/file.zip https://...`

Only the workspace directory is accessible — the sandbox cannot access other host directories.

### Docker Mode (Sibling Containers)

When the engine runs inside a Docker container, the workspace mount uses `KLAW_HOST_WORKSPACE` (the host-side path) for the `-v` source, while `KLAW_WORKSPACE` (`/workspace`) remains the in-container path. This is set automatically by the `klaw init` docker-compose template.

## Security

The sandbox enforces several hardcoded security constraints that cannot be overridden:

- `--privileged` is **never** used
- Docker socket (`/var/run/docker.sock`) is **never** mounted
- `--network host` is **never** used
- `--pid host` is **never** used
- Read-only root filesystem is enabled by default
- Container runs as non-root user (`sandbox`, UID 65533)

## Keep-Alive Mode

When `keepAlive: true`, the sandbox manager reuses a single container for multiple executions. The container is automatically recreated when:

- `keepAliveMaxExecutions` is reached
- The container has been idle for `keepAliveIdleTimeoutMin`
- The container has stopped or been removed

When `keepAlive: false`, each execution creates a fresh container that is removed after completion.

## Output

The tool returns formatted output including:
- stdout (truncated if over 10,000 characters)
- stderr (if non-empty)
- Exit code (if non-zero)
- Timeout indication (if the execution timed out)
