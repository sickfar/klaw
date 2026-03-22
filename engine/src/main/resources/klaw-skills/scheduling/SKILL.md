---
name: scheduling
description: Manage scheduled and one-time tasks — create, edit, enable/disable, list and remove tasks
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

## schedule_edit
Edit an existing scheduled task. Updates only the specified fields; unspecified fields remain unchanged.

**Parameters:**
- `name` (string, required): Task name to edit
- `cron` (string, optional): New cron expression
- `message` (string, optional): New subagent instruction
- `model` (string, optional): New LLM model

At least one of `cron`, `message`, or `model` must be provided.

## schedule_enable
Resume a paused (disabled) task.

**Parameters:**
- `name` (string, required): Task name to enable

## schedule_disable
Pause a scheduled task. The task remains in the scheduler but will not fire until re-enabled.

**Parameters:**
- `name` (string, required): Task name to disable

## Usage Guidelines
- Use `schedule_add` with `cron` for recurring tasks (daily summaries, periodic checks)
- Use `schedule_add` with `at` for one-time reminders or delayed actions
- `cron` and `at` are mutually exclusive — provide exactly one
- Check `schedule_list` before adding to avoid duplicate task names
- Task names should be descriptive (e.g. `morning-weather-report`, `birthday-reminder-alice`)
- The `message` is NOT delivered verbatim — it becomes the instruction for a subagent spawned when the task fires
- The subagent has access to all tools, including `schedule_deliver` for delivering results to the user
- Use `schedule_edit` to modify an existing task (cron, message, or model) without removing it
- Use `schedule_disable` / `schedule_enable` to temporarily pause and resume tasks
