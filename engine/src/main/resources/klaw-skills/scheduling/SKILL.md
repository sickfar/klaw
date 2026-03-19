---
name: scheduling
description: Manage scheduled and one-time tasks — create cron schedules, one-time triggers, list and remove tasks
---

# Scheduling

You have access to the following tools for managing scheduled tasks:

## schedule_list
List scheduled tasks.

**Parameters:** none

## schedule_add
Add a scheduled or one-time task. The `message` field must be an explicit instruction
for the subagent, not just content to deliver.

**Parameters:**
- `name` (string, required): Unique task name
- `message` (string, required): Explicit instruction for the subagent when the task fires.
  Must be an actionable task, not just content.
  For reminder delivery: `Your task: send the user this reminder: <text>`.
  For data collection: `Check <source>, extract <info>, summarize for user`.
- `cron` (string, optional): Cron schedule expression (mutually exclusive with `at`)
- `at` (string, optional): ISO-8601 datetime for one-time trigger (mutually exclusive with `cron`)
- `model` (string, optional): LLM model

## schedule_remove
Remove a scheduled task.

**Parameters:**
- `name` (string, required): Task name to remove

## Usage Guidelines
- Use `schedule_add` with `cron` for recurring tasks (daily summaries, periodic checks)
- Use `schedule_add` with `at` for one-time reminders or delayed actions
- `cron` and `at` are mutually exclusive — provide exactly one
- Check `schedule_list` before adding to avoid duplicate task names
- Task names should be descriptive (e.g. `morning-weather-report`, `birthday-reminder-alice`)
- The `message` is NOT delivered verbatim — it becomes the instruction for a subagent spawned when the task fires
- The subagent has access to all tools, including `schedule_deliver` for delivering results to the user
- If you need to modify a task, remove it first with `schedule_remove` then re-add
