# File Tools

All file tools operate within `$KLAW_WORKSPACE` only. Path traversal (e.g., `../`) is rejected with an error.

## file_read

Read a file from the workspace directory.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `path` | string | yes | Path relative to workspace |
| `startLine` | integer | no | Starting line number (1-based) |
| `maxLines` | integer | no | Maximum number of lines to return |

**Returns:** File contents as text. Use `startLine`/`maxLines` for large files.

## file_write

Write content to a file in the workspace.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `path` | string | yes | Path relative to workspace |
| `content` | string | yes | Content to write |
| `mode` | string | yes | `overwrite` or `append` |

**Returns:** Confirmation with bytes written.

## file_list

List contents of a directory in the workspace.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `path` | string | yes | Path relative to workspace |
| `recursive` | boolean | no | Recurse into subdirectories (default: false) |

**Returns:** Newline-separated list of entries. Directories are suffixed with `/`.

## Security

- Paths are resolved against `$KLAW_WORKSPACE` and canonicalized.
- Any resolved path that falls outside the workspace root is rejected.
- Symlinks pointing outside the workspace are not followed.
