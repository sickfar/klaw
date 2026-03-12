# Utility Tools

## send_message

Send a message to a specific channel and chat. The message is forwarded through the Engine's outbound handler to the appropriate Gateway.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `channel` | string | yes | Channel type (e.g., `telegram`, `discord`) |
| `chatId` | string | yes | Chat identifier in the target channel |
| `text` | string | yes | Message text to send |

**Returns:** Confirmation that the message was queued for delivery.
