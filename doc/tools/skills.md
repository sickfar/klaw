# Skills

Skills are reusable instruction sets that extend the agent's capabilities. Each skill is a directory containing a `SKILL.md` file with metadata and instructions that the agent can load on demand.

## SKILL.md Format

Each skill directory must contain a `SKILL.md` file with YAML frontmatter:

```markdown
---
name: my-skill
description: Short description of what this skill does
---

# My Skill

Full skill content: instructions, examples, tool usage, etc.
```

**Required frontmatter fields:**

| Field | Description |
|-------|-------------|
| `name` | Unique skill identifier (used for `skill_load`) |
| `description` | One-line summary shown in skill listings |

Skills with missing or incomplete frontmatter are silently skipped during discovery.

## Skill Directories

Skills are loaded from two locations:

| Location | Path | Priority |
|----------|------|----------|
| Global (data) | `~/.local/share/klaw/skills/<skill-name>/SKILL.md` | Lower |
| Workspace | `$KLAW_WORKSPACE/skills/<skill-name>/SKILL.md` | Higher (overrides) |

Global skills are scanned first, then workspace skills. If both locations contain a skill with the same `name`, the workspace version wins.

## Environment Variables

Skill content supports variable interpolation. Variables are resolved when the skill is loaded via `skill_load`.

| Variable | Resolves to |
|----------|-------------|
| `${KLAW_WORKSPACE}` or `$KLAW_WORKSPACE` | Workspace directory path |
| `${KLAW_SKILL_DIR}` or `$KLAW_SKILL_DIR` | Directory containing the current SKILL.md |
| `${KLAW_DATA}` or `$KLAW_DATA` | Data directory (`~/.local/share/klaw`) |
| `${KLAW_CONFIG}` or `$KLAW_CONFIG` | Config directory (`~/.config/klaw`) |

Both `${VAR}` and `$VAR` syntax are supported. Only the four variables above are resolved — all other `$` references remain unchanged.

**Example:**

```markdown
---
name: graph-rag
description: Semantic search through knowledge graph
---

## Usage

```bash
source ${KLAW_WORKSPACE}/projects/omnimemory/venv/bin/activate
python $KLAW_SKILL_DIR/scripts/search.py "query"
```

Data stored in: ${KLAW_DATA}/graph.db
Config at: $KLAW_CONFIG/graph-rag.json
```

## Context Integration

How skills appear in the LLM context depends on how many exist:

**Inline mode** (skill count ≤ `maxInlineSkills`, default 5): All skills listed in the system prompt under `## Available Skills` as `- name: description`. The `skill_load` tool is available for loading full content.

**Tool mode** (skill count > `maxInlineSkills`): Skills are NOT inlined. Both `skill_list` and `skill_load` tools are available. The agent must call `skill_list` to discover available skills.

When any skills exist, the capabilities section mentions "extensible skills".

## Tools

### skill_list

List all available skills with their names and descriptions.

**Parameters:** None.

**Returns:** List of skill names and short descriptions, one per line.

### skill_load

Load a skill's full content. Variables are resolved before returning.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `name` | string | yes | Skill name from frontmatter |

**Returns:** Full SKILL.md file content with resolved environment variables, or an error message if the skill is not found.
