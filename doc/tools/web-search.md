# Web Search Tool

Search the internet and return a list of results with titles, URLs, and snippets.

## web_search

### Parameters
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `query` | string | yes | Search query |
| `max_results` | integer | no | Maximum number of results (default 5, max 20) |

### Returns

Formatted search results:

```
Search results for: "kotlin coroutines tutorial"
Provider: brave
Results: 3

1. [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
   A comprehensive guide to Kotlin coroutines.

2. [Coroutines Basics](https://kotlinlang.org/docs/coroutines-basics.html)
   Learn the basics of Kotlin coroutines.

3. [Advanced Coroutines](https://example.com/advanced)
   Deep dive into coroutine internals.
```

### Providers

The tool supports configurable search providers:

#### Brave Search (`brave`)
- Endpoint: `https://api.search.brave.com/res/v1/web/search`
- Free tier: 2000 requests/month
- Requires API key from [brave.com/search/api](https://brave.com/search/api)

#### Tavily (`tavily`)
- Endpoint: `https://api.tavily.com/search`
- Free tier: 1000 requests/month
- AI-optimized search results
- Requires API key from [tavily.com](https://tavily.com)

### Configuration

In `engine.json`:

```json
{
  "webSearch": {
    "enabled": true,
    "provider": "brave",
    "apiKey": "${BRAVE_SEARCH_API_KEY}",
    "maxResults": 5,
    "requestTimeoutMs": 10000
  }
}
```

| Field | Default | Description |
|-------|---------|-------------|
| `enabled` | `false` | Enable the web_search tool (requires API key) |
| `provider` | `"brave"` | Search provider: `"brave"` or `"tavily"` |
| `apiKey` | `null` | API key (use env var reference `${VAR_NAME}`) |
| `maxResults` | `5` | Default number of results |
| `requestTimeoutMs` | `10000` | HTTP request timeout |
| `braveEndpoint` | `"https://api.search.brave.com"` | Brave API base URL (override for testing) |
| `tavilyEndpoint` | `"https://api.tavily.com"` | Tavily API base URL (override for testing) |

### Setup via `klaw init`

The `klaw init` wizard includes a web search setup step:
1. "Enable web search?" — answer `y` to configure
2. Select provider (Brave or Tavily)
3. Enter API key (validated via test request)
4. API key stored in `.env` file, referenced in config

### Errors

- Missing API key → `Error: IllegalStateException`
- API error (401, 429, 500) → `Error: IllegalStateException`
- Timeout → `Error: HttpTimeoutException`
- No results → `No results found for the given query.`
