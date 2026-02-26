# Skills Tools

## skill_list

List all available skills with their names and summaries.

**Parameters:** None.

**Returns:** List of skill names and short descriptions.

## skill_load

Load a skill's full content into the conversation context. The loaded text is included in the next LLM prompt as additional system instructions.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `name` | string | yes | Skill name |

**Returns:** Full skill content text.

## When to Load Skills

Skills are loaded on demand by the agent when a task requires specialized knowledge or behavior not covered by the base system prompt. The agent decides which skill to load based on the user's request and the skill list summaries.

## Creating Skills

Skills are plain-text files placed in the `skills/` directory under `$KLAW_DATA`. Each skill file should contain a concise set of instructions or domain knowledge. The file name (without extension) becomes the skill name.
