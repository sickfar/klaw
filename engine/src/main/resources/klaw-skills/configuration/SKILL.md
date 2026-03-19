---
name: configuration
description: Read and modify engine or gateway configuration at runtime — view settings, change providers, models, and features
---

# Configuration

You have access to the following tools for runtime configuration management:

## config_get
Read current engine or gateway configuration. Sensitive values (API keys) are masked.
Omit `path` to get the full config. Use dot notation for specific fields,
e.g. `routing.default`, `providers.zai.baseUrl`.

**Parameters:**
- `target` (string, required): Config target: `engine` or `gateway`
- `path` (string, optional): Dot-notation path, e.g. `routing.default`, `providers.zai.baseUrl`

## config_set
Update a configuration field. Engine config changes trigger automatic restart (~2s downtime).
Gateway channel config changes restart the gateway.
Other gateway changes apply immediately.

**Parameters:**
- `target` (string, required): Config target: `engine` or `gateway`
- `path` (string, required): Dot-notation path, e.g. `routing.default`, `providers.zai.apiKey`
- `value` (string, required): New value as string (booleans: `true`/`false`, numbers as digits)

## Usage Guidelines
- Always use `config_get` first to understand current state before making changes
- Warn the user that engine config changes cause a brief restart (~2s downtime)
- API keys are masked in `config_get` output — the user must provide the actual key for `config_set`
- Use dot-notation paths consistently (e.g. `providers.openai.apiKey`, not nested objects)
- Gateway channel config changes (adding/removing Telegram/Discord channels) require gateway restart
- Omitting `path` in `config_get` returns the full configuration — useful for an overview
