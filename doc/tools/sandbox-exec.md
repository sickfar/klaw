# sandbox_exec

**Preferred tool for code execution.** Execute Python or bash code in an isolated Docker container with workspace access.

This is the default tool for any code execution task: data processing, file downloads (curl/wget), file transformations, computations, parsing, script testing, and any operation on workspace files. The host workspace directory is automatically mounted at `/workspace` inside the container, allowing the code to read and write project files.

Use `host_exec` only when you need direct host system access (hardware monitoring, system service management, Docker commands, host network diagnostics).

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `language` | string | yes | — | `python` or `bash` |
| `code` | string | yes | — | Code to execute |
| `timeout` | int | no | 30 | Timeout in seconds |

## Configuration

In `engine.json` under `codeExecution`:

```json
{
  "codeExecution": {
    "image": "klaw-sandbox:latest",
    "timeout": 30,
    "keepAlive": true,
    "memoryLimit": "128m",
    "cpuLimit": "0.5",
    "readOnlyRootfs": true,
    "networkDisabled": false,
    "maxExecutions": 5,
    "idleTimeoutMinutes": 10
  }
}
```

| Field | Description |
|-------|-------------|
| `image` | Docker image name for the sandbox container |
| `timeout` | Default execution timeout in seconds |
| `keepAlive` | Reuse container across executions (vs. one-shot per execution) |
| `memoryLimit` | Docker memory limit (e.g., `128m`, `256m`) |
| `cpuLimit` | Docker CPU limit (e.g., `0.5`, `1.0`) |
| `readOnlyRootfs` | Mount root filesystem as read-only |
| `networkDisabled` | Disable container network access |
| `maxExecutions` | Max executions before container is recycled (keep-alive mode) |
| `idleTimeoutMinutes` | Minutes of idle time before container is stopped (keep-alive mode) |

## Workspace Access

The host workspace directory (`$KLAW_WORKSPACE`, default `~/klaw-workspace`) is automatically mounted at `/workspace` inside the container with read-write access. Code running in the sandbox can:

- Read project files: `cat /workspace/data/input.csv`
- Write output files: `python3 -c "open('/workspace/output.json','w').write(...)"`
- Process and transform workspace data
- Download files into the workspace: `curl -o /workspace/data/file.zip https://...`

Only the workspace directory is accessible — the sandbox cannot access other host directories.

## Security

The sandbox enforces several hardcoded security constraints that cannot be overridden:

- `--privileged` is **never** used
- Docker socket (`/var/run/docker.sock`) is **never** mounted
- `--network host` is **never** used
- `--pid host` is **never** used
- Read-only root filesystem is enabled by default
- Container runs as non-root user (`sandbox`, UID 65533)

## Docker Image

Build the sandbox image from `docker/klaw-sandbox/Dockerfile`:

```bash
docker build -t klaw-sandbox:latest docker/klaw-sandbox/
```

The image includes Python 3.12, bash, and curl.

## Keep-Alive Mode

When `keepAlive: true`, the sandbox manager reuses a single container for multiple executions. The container is automatically recreated when:

- `maxExecutions` is reached
- The container has been idle for `idleTimeoutMinutes`
- The container has stopped or been removed

When `keepAlive: false`, each execution creates a fresh container that is removed after completion.

## Output

The tool returns formatted output including:
- stdout (truncated if over 10,000 characters)
- stderr (if non-empty)
- Exit code (if non-zero)
- Timeout indication (if the execution timed out)
