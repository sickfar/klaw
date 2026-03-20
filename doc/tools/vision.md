# Vision Tools

Image analysis and multimodal support. The `image_analyze` tool sends images to a vision-capable model for text description. Vision is automatically integrated into the `file_read` tool.

## Enabling Vision

Vision is disabled by default. To enable, set `vision.enabled` to `true` in `engine.json` and configure a vision model:

```json
{
  "vision": {
    "enabled": true,
    "model": "glm/glm-4.6v"
  }
}
```

The model must be configured in `model-registry.json` with vision support enabled.

## How It Works

**For vision-capable models:**
- When you use `file_read` on an image, it is loaded inline as multimodal content
- The model can see the image directly
- No automatic description is needed

**For text-only models:**
- When you use `file_read` on an image, the engine automatically calls `image_analyze`
- The vision model generates a text description
- The description is returned to you as text
- This allows text-only models to understand image content

**Direct vision analysis:**
- Use `image_analyze` to send an image to the configured vision model
- Useful for getting a second opinion or using a specialized vision model
- Always available when vision is enabled

## image_analyze

Send an image to the configured vision model for text description.

### Parameters

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `path` | string | yes | Image file path — relative (workspace) or with placeholder |
| `prompt` | string | no | Custom prompt for the vision model (default: "Describe this image in detail") |

### Returns

Text description of the image from the vision model.

### Supported Formats

By default: JPEG, PNG, GIF, WebP. Configurable via `vision.supportedFormats` in `engine.json`.

### File Size Limits

Maximum file size is controlled by `vision.maxImageSizeBytes` (default: 10MB).

### Errors

- Vision not enabled: `Error: vision capabilities are not enabled (set vision.enabled to true in engine.json)`
- File not found: `Error: file not found: <path>`
- Unsupported format: `Error: image format <format> is not supported`
- File too large: `Error: file size (<N> bytes) exceeds maximum allowed (<limit> bytes)`
- Vision model error: `Error: vision model request failed: <reason>`
- Access denied: path traversal outside workspace is rejected

## Configuration

In `engine.json`, under the `vision` key:

```json
{
  "vision": {
    "enabled": false,
    "model": "glm/glm-4.6v",
    "maxTokens": 1024,
    "maxImageSizeBytes": 10485760,
    "maxImagesPerMessage": 5,
    "supportedFormats": ["image/jpeg", "image/png", "image/gif", "image/webp"]
  }
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | bool | `false` | Enable vision capabilities |
| `model` | string | `""` | Vision model ID. Empty falls back to `routing.default` |
| `maxTokens` | int | `1024` | Maximum output tokens for vision model responses |
| `maxImageSizeBytes` | long | `10485760` | Maximum image file size in bytes |
| `maxImagesPerMessage` | int | `5` | Maximum images per message for inline vision |
| `supportedFormats` | string[] | `["image/jpeg", "image/png", "image/gif", "image/webp"]` | Allowed image MIME types |

## Model Registry

Vision capability is determined by `model-registry.json`. Models can declare support for vision with feature flags. The engine respects these declarations:

```json
{
  "glm/glm-4.6v": {
    "contextWindow": 8192,
    "features": ["vision"]
  }
}
```

When a model has the `"vision"` feature, images are loaded inline as multimodal content instead of being auto-described.

## From Messaging Channels

Images received from Telegram or Discord (when `attachments.directory` is configured in `gateway.json`) are automatically saved locally and become available via `file_read`. They flow through vision the same way as workspace images.

## Performance Considerations

- Vision model calls consume tokens and may introduce latency
- For batch image analysis, consider using `image_analyze` explicitly with concurrency control
- Very large images are rejected by default (10MB limit configurable)
- Multiple images per message are supported up to the `maxImagesPerMessage` limit
