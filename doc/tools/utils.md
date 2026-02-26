# Utility Tools

## current_time

Get the current date and time in ISO-8601 format with the system timezone.

**Parameters:** None.

**Returns:** Current timestamp string (e.g., `2026-02-26T14:30:00+03:00`).

## send_message

Send a message to a specific channel and chat. The message is forwarded through the Engine's outbound handler to the appropriate Gateway.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `channel` | string | yes | Channel type (e.g., `telegram`, `discord`) |
| `chatId` | string | yes | Chat identifier in the target channel |
| `text` | string | yes | Message text to send |

**Returns:** Confirmation that the message was queued for delivery.
