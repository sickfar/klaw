# Web Fetch Tool

Fetch a web page and return its content as readable text or markdown.

## web_fetch

### Parameters
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `url` | string | yes | URL to fetch (http or https only) |
| `timeout_seconds` | integer | no | Request timeout in seconds (default 30, max 120) |

### Returns

Metadata header followed by content, separated by `---`:

```
URL: https://example.com/page
Status: 200
Title: Page Title
Description: Meta description text
Content-Length: 12345
---
# Heading

Paragraph text...
```

### Content Type Handling

| Content-Type | Handling |
|-------------|----------|
| `text/html`, `application/xhtml+xml` | Parsed with Jsoup, converted to markdown (headings, lists, links, code blocks, tables) |
| `text/plain` | Returned as-is |
| `application/json` | Returned as-is |
| `text/xml`, `application/xml` | Returned as-is |
| `text/csv` | Returned as-is |
| Binary types (`image/*`, `application/octet-stream`, etc.) | Returns error |

### HTML to Markdown Conversion

The converter strips non-content elements (`<script>`, `<style>`, `<nav>`, `<footer>`, `<header>`, `<aside>`) and converts:

- Headings (`h1`-`h6`) to `#` syntax
- Paragraphs to text with blank lines
- Lists (`ul`/`ol`) to `-` or `1.` syntax
- Links to `[text](url)`
- Bold/italic to `**bold**`/`*italic*`
- Code blocks to fenced blocks
- Blockquotes to `>` prefix
- Tables to markdown tables
- Images to `![alt](src)`

### SPA Detection

If the page appears to use JavaScript rendering (body content < 500 characters with 3+ `<script>` tags), a warning is included in the output:

```
Warning: This page appears to use JavaScript rendering. Content may be incomplete.
```

### Security

- Only `http://` and `https://` URLs are accepted
- Response size is checked against `maxResponseSizeBytes` (default 1MB) before downloading
- Redirects are followed automatically
- Configurable User-Agent header

### Configuration

In `engine.json`:

```json
{
  "webFetch": {
    "enabled": true,
    "requestTimeoutMs": 30000,
    "maxResponseSizeBytes": 1048576,
    "userAgent": "Klaw/1.0 (AI Agent)"
  }
}
```

| Field | Default | Description |
|-------|---------|-------------|
| `enabled` | `true` | Show/hide the tool from the LLM |
| `requestTimeoutMs` | `30000` | HTTP request timeout |
| `maxResponseSizeBytes` | `1048576` (1MB) | Maximum response body size |
| `userAgent` | `"Klaw/1.0 (AI Agent)"` | User-Agent header |

### Errors

- Invalid URL → `Error: invalid URL`
- Non-http scheme → `Error: only http and https URLs are supported`
- HTTP 4xx/5xx → `Error: HTTP <status>`
- Response too large → `Error: response too large`
- Timeout → `Error: HttpTimeoutException`
- Unsupported content type → `Error: unsupported content type`
