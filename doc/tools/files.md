# File Tools

File tools provide read/write access to the workspace and read-only access to state, data, config, and cache directories.

## Path Placeholders

The following placeholders are expanded before path resolution:

| Placeholder | Resolves to | Access |
|-------------|-------------|--------|
| `$WORKSPACE` | `$KLAW_WORKSPACE` (default `~/klaw-workspace`) | read-write |
| `$STATE` | `~/.local/state/klaw` (contains `logs/`) | read-only |
| `$DATA` | `~/.local/share/klaw` | read-only |
| `$CONFIG` | `~/.config/klaw` | read-only |
| `$CACHE` | `~/.cache/klaw` | read-only |

Relative paths (without a `$` placeholder) resolve to the workspace only.

Examples:
- `$STATE/logs/engine.log` — read engine logs
- `$CONFIG/engine.json` — read engine config
- `myfile.txt` — resolves to `$WORKSPACE/myfile.txt`

## file_read

Read a file from any accessible directory.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `path` | string | yes | Path — relative (workspace) or with placeholder |
| `startLine` | integer | no | Starting line number (1-based) |
| `maxLines` | integer | no | Maximum number of lines to return |

**Returns:** File contents as text. Use `startLine`/`maxLines` for large files.

## file_write

Write content to a file in the workspace (write access is workspace-only).

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `path` | string | yes | Path relative to workspace |
| `content` | string | yes | Content to write |
| `mode` | string | yes | `overwrite` or `append` |

**Returns:** Confirmation with bytes written.

## file_list

List contents of a directory.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `path` | string | yes | Path — relative (workspace) or with placeholder |
| `recursive` | boolean | no | Recurse into subdirectories (default: false) |

**Returns:** Newline-separated list of entries. Directories are suffixed with `/`.

## file_patch

Replace a text fragment in a file by exact match (workspace-only).

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `path` | string | yes | Path relative to workspace |
| `old_string` | string | yes | Text to replace (exact match) |
| `new_string` | string | yes | Replacement text |
| `force_first` | boolean | no | Replace first occurrence when multiple matches exist |

## Security

- Paths are resolved and canonicalized against allowed directories.
- Any resolved path that falls outside allowed directories is rejected.
- Symlinks pointing outside allowed directories are not followed.
- Path traversal attempts (e.g., `$STATE/../../../etc/passwd`) are blocked.
- Write access is restricted to the workspace directory only.
