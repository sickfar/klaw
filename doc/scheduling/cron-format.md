# Cron Format

Klaw uses **Quartz cron** syntax, which has **7 fields** — different from standard Unix cron (5 fields).

## Field order

```
seconds  minutes  hours  day-of-month  month  day-of-week  [year]
```

`year` is optional. All other fields are required.

## Field ranges

| Field | Required | Range | Special values |
|-------|----------|-------|----------------|
| Seconds | yes | 0–59 | `, - * /` |
| Minutes | yes | 0–59 | `, - * /` |
| Hours | yes | 0–23 | `, - * /` |
| Day-of-month | yes | 1–31 | `, - * ? / L W` |
| Month | yes | 1–12 or JAN–DEC | `, - * /` |
| Day-of-week | yes | 1–7 or SUN–SAT (1=Sunday) | `, - * ? / L #` |
| Year | no | 1970–2099 | `, - * /` |

## Special characters

| Character | Meaning | Example |
|-----------|---------|---------|
| `*` | Any value | `* * * * * ?` — every second |
| `?` | No specific value | Use in day-of-month OR day-of-week (not both) |
| `/` | Step | `0/30` — every 30 starting at 0 |
| `,` | List | `MON,WED,FRI` |
| `-` | Range | `MON-FRI` |
| `L` | Last | `L` in day-of-month = last day of month |
| `W` | Nearest weekday | `15W` = nearest weekday to the 15th |
| `#` | Nth occurrence | `2#1` = first Monday |

**Important:** Use `?` in either `day-of-month` or `day-of-week` (not both, not neither) when specifying the other field.

## Common examples

| Description | Cron expression |
|-------------|-----------------|
| Every minute | `0 * * * * ?` |
| Every hour (on the hour) | `0 0 * * * ?` |
| Every 30 minutes | `0 0/30 * * * ?` |
| Every 15 minutes | `0 0/15 * * * ?` |
| Daily at 9 AM | `0 0 9 * * ?` |
| Daily at 9 AM and 6 PM | `0 0 9,18 * * ?` |
| Weekdays at 8 AM | `0 0 8 ? * MON-FRI` |
| Every Monday at 9 AM | `0 0 9 ? * MON` |
| Every Sunday at midnight | `0 0 0 ? * SUN` |
| First day of month at noon | `0 0 12 1 * ?` |
| Last day of month at 11 PM | `0 0 23 L * ?` |
| Every 2 hours | `0 0 0/2 * * ?` |

## Validation

Invalid cron expressions cause `schedule_add` to return an error. After adding a task, verify the next fire time with `schedule_list`.

## Difference from Unix cron

Unix cron has 5 fields: `minute hour day-of-month month day-of-week`.

Quartz cron has 7 fields starting with **seconds**: `second minute hour day-of-month month day-of-week [year]`.

Do not use Unix cron expressions directly — they will be misinterpreted.

## See also

- `doc/scheduling/heartbeat.md` — using cron in HEARTBEAT.md
- `doc/tools/schedule.md` — schedule_add tool reference
