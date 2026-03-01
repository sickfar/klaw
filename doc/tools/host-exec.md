# host_exec

Execute a shell command **directly on the host system** with a 4-step access control cascade.

**Use only when `sandbox_exec` is not suitable.** Appropriate use cases:
- Hardware monitoring: `sensors`, `lsblk`, `smartctl`, `vcgencmd`
- System service management: `systemctl status/restart/stop`
- Docker management: `docker ps`, `docker logs`, `docker compose`
- Host network diagnostics: `ip addr`, `ss`, `ping`, `traceroute`
- Actions outside the workspace directory

**Do not use for:** data processing, file downloads, computations, file transformations, script testing, or any task that can run inside a Docker container. Use `sandbox_exec` for those — it provides an isolated environment with workspace access at `/workspace`.

## Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `command` | string | yes | Shell command to execute |

## Access Control Cascade

Commands pass through a 4-step validation cascade. The first matching step determines the action:

1. **allowList** — If the command matches any glob pattern in `allowList`, it executes immediately without notification.

2. **notifyList** — If the command matches any glob pattern in `notifyList`, it executes immediately and sends a notification to the user.

3. **LLM pre-validation** — If enabled, an LLM evaluates the command's risk score (0-10). Commands scoring below `riskThreshold` execute without asking. Commands at or above the threshold proceed to step 4.

4. **User approval** — An inline-keyboard approval request is sent to the user in the originating chat. The user can approve or reject. If no response is received within `askTimeoutMin` minutes, the command is rejected.

## Configuration

In `engine.json` under `hostExecution`:

```json
{
  "hostExecution": {
    "enabled": false,
    "allowList": ["df -h", "free -m", "uptime"],
    "notifyList": ["systemctl restart *"],
    "preValidation": {
      "enabled": true,
      "model": "deepseek/chat",
      "riskThreshold": 5,
      "timeoutMs": 5000
    },
    "askTimeoutMin": 5
  }
}
```

| Field | Description |
|-------|-------------|
| `enabled` | Master switch — must be `true` for host_exec to work |
| `allowList` | Glob patterns for commands that execute without approval |
| `notifyList` | Glob patterns for commands that execute with notification |
| `preValidation.enabled` | Enable LLM-based risk assessment |
| `preValidation.model` | LLM model ID for risk assessment |
| `preValidation.riskThreshold` | Risk score threshold (0-10); below = auto-approve |
| `preValidation.timeoutMs` | Timeout for LLM risk assessment call |
| `askTimeoutMin` | Minutes to wait for user approval before timeout |

## Glob Pattern Matching

Both `allowList` and `notifyList` use glob-style pattern matching:

- `*` matches any sequence of characters
- `?` matches any single character
- Matching is case-sensitive
- The entire command must match the pattern

Examples:
- `df -h` — exact match only
- `systemctl status *` — matches `systemctl status nginx`, `systemctl status klaw-engine`, etc.
- `docker restart *` — matches any `docker restart` command

## Risk Assessment

When LLM pre-validation is enabled, the engine sends the command to a fast LLM model for risk scoring:

- **0** — Read-only, no side effects (cat, grep, ls, head, uptime, whoami, date)
- **1-3** — Low risk, reads data or harmless operations
- **4-5** — Moderate risk, writes to temporary or non-critical files (5 is the default threshold)
- **6-7** — High risk, modifies system state (service restart, package install, config change)
- **8-9** — Very high risk, destructive or broad-scope changes (rm -r, chmod -R, kill)
- **10** — Catastrophic, irreversible data loss (rm -rf /, mkfs, dd if=/dev/zero)

The prompt instructs the LLM to respond with only a single integer. If the LLM responds with text instead of a plain number (e.g. "confidence level 9" or "Risk: 7"), the engine extracts the first valid integer in range 0-10 from the response. If no number can be extracted, the engine retries once with a stricter prompt demanding only a number. If the retry also fails, the system falls back to user approval (step 4).

If the LLM call fails or times out entirely, the system also falls back to user approval.

## Approval Flow

When user approval is required:

1. Engine sends `ApprovalRequestMessage` to the gateway via TCP socket
2. Gateway renders an inline keyboard in Telegram with Approve/Reject buttons
3. User clicks a button
4. Gateway sends `ApprovalResponseMessage` back to the engine
5. Engine executes or rejects the command based on the response

## Execution

Commands are executed via `ProcessBuilder("sh", "-c", command)` with a 60-second timeout. Output includes stdout, stderr (if non-empty), and exit code (if non-zero).

## Security Notes

- `host_exec` is **disabled by default** — must be explicitly enabled in config
- The `chatId` context is threaded through the coroutine context to route approval requests
- Commands are never logged at any log level (logging constraint)
