#!/usr/bin/env bash
# migrate-to-multiagent.sh — Migrate a single-agent Klaw installation to multi-agent format.
#
# Usage:
#   ./scripts/migrate-to-multiagent.sh [OPTIONS]
#
# Options:
#   --apply              Actually perform the migration (default: dry-run only)
#   --config-dir DIR     Path to config directory (default: ~/.config/klaw)
#   --data-dir DIR       Path to data directory   (default: ~/.local/share/klaw)
#   --state-dir DIR      Path to state directory  (default: ~/.local/state/klaw)
#   -h, --help           Show this help message
#
# Dependencies: bash, jq

set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
APPLY=false
CONFIG_DIR="${HOME}/.config/klaw"
DATA_DIR="${HOME}/.local/share/klaw"
STATE_DIR="${HOME}/.local/state/klaw"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply)       APPLY=true; shift ;;
    --config-dir)  CONFIG_DIR="$2"; shift 2 ;;
    --data-dir)    DATA_DIR="$2"; shift 2 ;;
    --state-dir)   STATE_DIR="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
DRY_PREFIX=""
if [[ "$APPLY" == "false" ]]; then
  DRY_PREFIX="[DRY-RUN] "
fi

log()  { echo "${DRY_PREFIX}$*"; }
info() { echo "  $*"; }

run_cmd() {
  # In dry-run mode just print the command; otherwise execute it.
  if [[ "$APPLY" == "true" ]]; then
    "$@"
  else
    echo "  would run: $*"
  fi
}

backup_file() {
  local src="$1"
  local bak="${src}.pre-multiagent-backup"
  if [[ -f "$src" ]]; then
    log "Backup: $src → $bak"
    run_cmd cp "$src" "$bak"
  fi
}

move_file() {
  local src="$1"
  local dst="$2"
  if [[ -f "$src" ]]; then
    log "Move: $src → $dst"
    if [[ "$APPLY" == "true" ]]; then
      mkdir -p "$(dirname "$dst")"
      mv "$src" "$dst"
    else
      echo "  would move: $src → $dst"
    fi
  else
    info "Skip (not found): $src"
  fi
}

# ---------------------------------------------------------------------------
# Verify prerequisites
# ---------------------------------------------------------------------------
if ! command -v jq &>/dev/null; then
  echo "ERROR: jq is required but not found in PATH." >&2
  exit 1
fi

echo "=== Klaw multi-agent migration ==="
echo "Config dir : $CONFIG_DIR"
echo "Data dir   : $DATA_DIR"
echo "State dir  : $STATE_DIR"
echo "Mode       : $(if [[ $APPLY == true ]]; then echo APPLY; else echo DRY-RUN; fi)"
echo ""

# ---------------------------------------------------------------------------
# 1. engine.json migration
# ---------------------------------------------------------------------------
ENGINE_JSON="${CONFIG_DIR}/engine.json"

if [[ -f "$ENGINE_JSON" ]]; then
  echo "--- engine.json ---"
  backup_file "$ENGINE_JSON"

  # Fields to keep at top level (not moved to agents.default)
  TOP_LEVEL_KEYS=(providers models routing processing memory context web httpRetry database documents logging)

  # New default values that should be stripped (set to null means "keep if different")
  # We strip a field when its value matches the new default.
  STRIP_DEFAULTS=$(cat <<'EOF'
{
  "processing": {
    "debounceMs": 800,
    "maxConcurrentLlm": 3,
    "maxToolCallRounds": 50
  },
  "memory": {
    "chunking": { "size": 512, "overlap": 64 },
    "search": { "topK": 10 },
    "embedding": { "type": "onnx" }
  },
  "context": {
    "subagentHistory": 10
  }
}
EOF
)

  NEW_ENGINE=$(jq \
    --argjson defaults "$STRIP_DEFAULTS" \
    '
    # Fields that move under agents.default
    def agent_fields: ["workspace","heartbeat","vision","hostExecution","codeExecution"];

    # Build agents.default object from per-agent top-level fields
    def build_agent:
      {}
      | if env.W != "null" then . + { workspace: env.W | fromjson } else . end
      | if env.H != "null" then . + { heartbeat: env.H | fromjson } else . end
      | if (env.V != "null" and (env.V | fromjson | .enabled? // false)) then
          . + { vision: (env.V | fromjson) }
        else . end
      | if (env.HE != "null" and (env.HE | fromjson | .enabled? // false)) then
          . + { tools: (.tools // {} | . + { hostExec: (env.HE | fromjson) }) }
        else . end
      | if (env.CE != "null") then
          . + { tools: (.tools // {} | . + { sandbox: (env.CE | fromjson) }) }
        else . end
    ;

    # Remove a key from an object if its value equals the default
    def strip_default(obj; path_):
      if path_ | length == 0 then obj
      else obj end
    ;

    # Strip processing defaults
    def clean_processing(p):
      if p == null then null
      else
        p
        | if .debounceMs == ($defaults.processing.debounceMs) then del(.debounceMs) else . end
        | if .maxConcurrentLlm == ($defaults.processing.maxConcurrentLlm) then del(.maxConcurrentLlm) else . end
        | if .maxToolCallRounds == ($defaults.processing.maxToolCallRounds) then del(.maxToolCallRounds) else . end
        | if . == {} then empty else . end
      end
    ;

    # Strip memory defaults
    def clean_memory(m):
      if m == null then null
      else
        m
        | if (.chunking.size == $defaults.memory.chunking.size and .chunking.overlap == $defaults.memory.chunking.overlap)
          then del(.chunking) else . end
        | if .search.topK == $defaults.memory.search.topK then del(.search) else . end
        | if .search == {} then del(.search) else . end
        | if .embedding.type == $defaults.memory.embedding.type then .embedding |= del(.type) else . end
        | if .embedding == {} then del(.embedding) else . end
      end
    ;

    # Strip context defaults
    def clean_context(c):
      if c == null then null
      else
        c
        | if .subagentHistory == $defaults.context.subagentHistory then del(.subagentHistory) else . end
        | if . == {} then empty else . end
      end
    ;

    . as $orig |

    # Extract per-agent values as JSON strings (null if absent)
    ($orig.workspace | if . then tojson else "null" end) as $ws |
    ($orig.heartbeat | if . then tojson else "null" end) as $hb |
    ($orig.vision | if . then tojson else "null" end) as $vis |
    ($orig.hostExecution | if . then tojson else "null" end) as $he |
    ($orig.codeExecution | if . then tojson else "null" end) as $ce |

    # Build agent object inline (without env vars — pure jq)
    ({}
      | if $orig.workspace then . + { workspace: $orig.workspace } else . end
      | if $orig.heartbeat then . + { heartbeat: $orig.heartbeat } else . end
      | if ($orig.vision != null and ($orig.vision.enabled // false))
        then . + { vision: $orig.vision } else . end
      | if ($orig.hostExecution != null and ($orig.hostExecution.enabled // false))
        then . + { tools: (.tools // {} | . + { hostExec: $orig.hostExecution }) } else . end
      | if $orig.codeExecution != null
        then . + { tools: (.tools // {} | . + { sandbox: $orig.codeExecution }) } else . end
    ) as $agent_obj |

    # Build new top-level: keep only top-level keys, add agents
    {
      providers:    $orig.providers,
      models:       $orig.models,
      routing:      $orig.routing,
      processing:   ($orig.processing | if . then clean_processing(.) else . end),
      memory:       ($orig.memory | if . then clean_memory(.) else . end),
      context:      ($orig.context | if . then clean_context(.) else . end),
      web:          $orig.web,
      httpRetry:    $orig.httpRetry,
      database:     $orig.database,
      documents:    $orig.documents,
      logging:      $orig.logging,
      agents: {
        default: $agent_obj
      }
    }
    | del(.[] | nulls)
    # Remove empty processing/memory/context after stripping
    | if .processing == {} then del(.processing) else . end
    | if .memory == {} then del(.memory) else . end
    | if .context == {} then del(.context) else . end
    ' "$ENGINE_JSON")

  log "Write: $ENGINE_JSON (migrated)"
  info "agents.default fields extracted from top-level"
  if [[ "$APPLY" == "true" ]]; then
    echo "$NEW_ENGINE" > "$ENGINE_JSON"
  else
    echo ""
    echo "  Preview of new engine.json:"
    echo "$NEW_ENGINE" | sed 's/^/    /'
    echo ""
  fi
else
  echo "--- engine.json not found at $ENGINE_JSON, skipping ---"
fi

# ---------------------------------------------------------------------------
# 2. gateway.json migration
# ---------------------------------------------------------------------------
GATEWAY_JSON="${CONFIG_DIR}/gateway.json"

if [[ -f "$GATEWAY_JSON" ]]; then
  echo "--- gateway.json ---"
  backup_file "$GATEWAY_JSON"

  NEW_GATEWAY=$(jq '
    # Map old channel names to new canonical names
    def channel_name(k):
      if k == "console" or k == "localWs" then "websocket"
      elif k == "telegram" then "telegram"
      elif k == "discord" then "discord"
      else k
      end
    ;

    # Transform flat channel config to named-instance map
    def transform_channel(cfg):
      # Remove enabled flag (presence in map = enabled)
      cfg | del(.enabled)
      | . + { agentId: "default" }
    ;

    .channels as $ch |
    {
      channels: (
        $ch | to_entries | map(
          {
            key: (.key | channel_name(.)),
            value: {
              "default": transform_channel(.value)
            }
          }
        ) | from_entries
      )
    }
    # Carry over any other top-level keys (allowedUsers, etc.)
    + (. | del(.channels))
  ' "$GATEWAY_JSON")

  log "Write: $GATEWAY_JSON (migrated)"
  info "Flat channels wrapped in named-instance maps"
  info "Old 'console'/'localWs' renamed to 'websocket'"
  info "'enabled' fields removed (presence in map = enabled)"
  if [[ "$APPLY" == "true" ]]; then
    echo "$NEW_GATEWAY" > "$GATEWAY_JSON"
  else
    echo ""
    echo "  Preview of new gateway.json:"
    echo "$NEW_GATEWAY" | sed 's/^/    /'
    echo ""
  fi
else
  echo "--- gateway.json not found at $GATEWAY_JSON, skipping ---"
fi

# ---------------------------------------------------------------------------
# 3. Database file migration
# ---------------------------------------------------------------------------
echo "--- Database files ---"

# klaw.db (+ WAL + SHM)
KLAW_DB="${DATA_DIR}/klaw.db"
KLAW_DB_NEW="${DATA_DIR}/klaw-default.db"
backup_file "$KLAW_DB"
move_file "$KLAW_DB" "$KLAW_DB_NEW"
move_file "${KLAW_DB}-wal" "${KLAW_DB_NEW}-wal"
move_file "${KLAW_DB}-shm" "${KLAW_DB_NEW}-shm"

# scheduler.db (+ WAL + SHM)
SCHED_DB="${DATA_DIR}/scheduler.db"
SCHED_DB_NEW="${DATA_DIR}/scheduler-default.db"
backup_file "$SCHED_DB"
move_file "$SCHED_DB" "$SCHED_DB_NEW"
move_file "${SCHED_DB}-wal" "${SCHED_DB_NEW}-wal"
move_file "${SCHED_DB}-shm" "${SCHED_DB_NEW}-shm"

# ---------------------------------------------------------------------------
# 4. Conversation file migration
# ---------------------------------------------------------------------------
echo "--- Conversation files ---"

CONV_DIR="${DATA_DIR}/conversations"

if [[ -d "$CONV_DIR" ]]; then
  # Find direct subdirectories that are NOT already "default" (i.e. old chatId dirs)
  OLD_DIRS=$(find "$CONV_DIR" -mindepth 1 -maxdepth 1 -type d ! -name "default" 2>/dev/null || true)

  if [[ -z "$OLD_DIRS" ]]; then
    info "No legacy conversation directories found under $CONV_DIR"
  else
    TARGET_CONV="${CONV_DIR}/default"
    log "Create: $TARGET_CONV/"
    run_cmd mkdir -p "$TARGET_CONV"

    while IFS= read -r chat_dir; do
      chat_name=$(basename "$chat_dir")
      dst="${TARGET_CONV}/${chat_name}"
      log "Move: $chat_dir → $dst"
      if [[ "$APPLY" == "true" ]]; then
        mv "$chat_dir" "$dst"
      else
        echo "  would move: $chat_dir → $dst"
      fi
    done <<< "$OLD_DIRS"
  fi
else
  info "No conversations directory found at $CONV_DIR, skipping"
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo ""
if [[ "$APPLY" == "true" ]]; then
  echo "Migration complete. Backup files have suffix .pre-multiagent-backup"
  echo "Review the migrated configs before starting the engine/gateway."
else
  echo "Dry-run complete. No files were changed."
  echo "Re-run with --apply to execute the migration."
fi
