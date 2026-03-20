# Document Tools

Read PDF files, extract text, and convert Markdown to PDF. These tools are available via the bundled `documents` skill — load with `skill_load("documents")`.

## pdf_read

Extract text content from a PDF file with page markers. Same accessible directories as `file_read` — relative paths resolve to the workspace.

### Parameters

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `path` | string | yes | PDF file path — relative (workspace) or absolute |
| `start_page` | integer | no | First page to extract (1-based, default: first page) |
| `end_page` | integer | no | Last page to extract (1-based, default: last page) |

### Returns

Metadata header followed by extracted text, separated by `---`. Each page is delimited by a `--- Page N ---` marker:

```
File: reports/annual.pdf
Pages: 1-3 of 10
---
--- Page 1 ---
First page text content...

--- Page 2 ---
Second page text content...

--- Page 3 ---
Third page text content...
```

If the output exceeds `maxOutputChars`, it is truncated:

```
[Output truncated at 100000 characters]
```

If the number of pages exceeds `maxPages`, a limit notice is appended:

```
[Showing 100 of 250 pages — limit reached]
```

### Errors

- File not found: `Error: file not found: <path>`
- File too large: `Error: file size (<N> bytes) exceeds maximum allowed (<limit> bytes)`
- Encrypted PDF: `Error: PDF is password-protected or encrypted`
- Corrupted file: `Error: invalid or corrupted PDF file`
- Invalid start page: `Error: start_page <N> is out of range (1-<total>)`
- Invalid end page: `Error: end_page <N> is out of range (<start>-<total>)`
- Access denied: path traversal outside workspace is rejected

## md_to_pdf

Convert a Markdown file to PDF format. Supports headings (h1-h3), bullet lists, numbered lists, and paragraph text. Output uses A4 page size with Helvetica font.

### Parameters

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `input_path` | string | yes | Path to the Markdown file to convert |
| `output_path` | string | yes | Path for the output PDF file (workspace only) |
| `title` | string | no | Document title for PDF metadata |

### Returns

On success:

```
OK: PDF generated at reports/output.pdf (3 pages, 12345 bytes)
```

### Markdown Support

The converter handles these elements:

- `# Heading 1` — rendered at 1.8x font size, bold
- `## Heading 2` — rendered at 1.5x font size, bold
- `### Heading 3` — rendered at 1.2x font size, bold
- `- item` or `* item` — bullet list items
- `1. item` — numbered list items (rendered as bullets)
- Plain paragraphs — wrapped to page width
- Blank lines — vertical spacing

Non-ASCII characters are replaced with spaces (Helvetica font limitation).

### Errors

- Input file not found: `Error: file not found: <path>`
- Cannot read input: `Error: failed to read input file`
- Render failure: `Error: PDF generation failed`
- Access denied: path traversal outside workspace is rejected

## Configuration

In `engine.json`, under the `documents` key:

```json
{
  "documents": {
    "maxPdfSizeBytes": 52428800,
    "maxPages": 100,
    "maxOutputChars": 100000,
    "pdfFontSize": 12
  }
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `maxPdfSizeBytes` | long | `52428800` (50MB) | Maximum PDF file size for `pdf_read` |
| `maxPages` | int | `100` | Maximum number of pages to extract in `pdf_read` (0 = unlimited) |
| `maxOutputChars` | int | `100000` | Maximum output text length in characters before truncation |
| `pdfFontSize` | float | `12` | Default font size for `md_to_pdf` output |
