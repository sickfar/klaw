# Configuration Tools

## config_get

Read current engine or gateway configuration. Sensitive values (API keys) are masked.

### Parameters

| Name   | Type   | Required | Description                                                         |
|--------|--------|----------|---------------------------------------------------------------------|
| target | string | yes      | Config target: `engine` or `gateway`                                |
| path   | string | no       | Dot-notation path (e.g. `routing.default`, `providers.zai.baseUrl`) |

Omit `path` to get the full config.

### Example

```json
{
  "name": "config_get",
  "arguments": {
    "target": "engine",
    "path": "routing.default"
  }
}
```

## config_set

Update a configuration field. Engine config changes trigger an automatic restart (~2s downtime). Gateway channel config changes restart the gateway. Other gateway changes apply immediately.

### Parameters

| Name   | Type   | Required | Description                                                           |
|--------|--------|----------|-----------------------------------------------------------------------|
| target | string | yes      | Config target: `engine` or `gateway`                                  |
| path   | string | yes      | Dot-notation path (e.g. `routing.default`, `providers.zai.apiKey`)    |
| value  | string | yes      | New value as string (booleans: `true`/`false`, numbers as digits)     |

### Example

```json
{
  "name": "config_set",
  "arguments": {
    "target": "engine",
    "path": "routing.default",
    "value": "deepseek/deepseek-chat"
  }
}
```
