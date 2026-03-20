---
name: documents
description: Read PDF files, extract text, and convert Markdown to PDF
---

# Document Tools

You have access to the following tools for working with documents:

## pdf_read
Read a PDF file and extract text content with page markers.
Same accessible dirs as file_read. Relative paths resolve to workspace only.

**Parameters:**
- `path` (string, required): PDF file path — relative (workspace) or absolute
- `start_page` (integer, optional): First page to extract (1-based, default: first page)
- `end_page` (integer, optional): Last page to extract (1-based, default: last page)

## md_to_pdf
Convert a Markdown file to PDF format.

**Parameters:**
- `input_path` (string, required): Path to the Markdown file to convert
- `output_path` (string, required): Path for the output PDF file (workspace only)
- `title` (string, optional): Document title for PDF metadata

## Usage Guidelines
- Use `pdf_read` to extract text from PDF documents for analysis or summarization
- Use page ranges for large PDFs to avoid overwhelming context
- Use `md_to_pdf` to generate PDF reports, export notes, or create printable documents
- Output PDFs are written to workspace only (write access required)
