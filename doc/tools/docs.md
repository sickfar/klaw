# Documentation Tools

## docs_search

Search project documentation by query.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `query` | string | yes | Search query |
| `topK` | integer | no | Number of results (default: 5) |

**Returns:** Matching document excerpts ranked by relevance.

## docs_read

Read a specific document by path.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `path` | string | yes | Document path |

**Returns:** Full document content.

## docs_list

List all available project documents.

**Parameters:** None.

**Returns:** List of document paths.
