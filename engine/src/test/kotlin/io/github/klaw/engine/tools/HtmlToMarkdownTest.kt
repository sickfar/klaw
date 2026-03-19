package io.github.klaw.engine.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
class HtmlToMarkdownTest {
    @Test
    fun `h1 converts to markdown heading`() {
        val html = "<html><body><h1>Title</h1></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("# Title"))
    }

    @Test
    fun `h2 converts to markdown heading`() {
        val html = "<html><body><h2>Subtitle</h2></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("## Subtitle"))
    }

    @Test
    fun `h3 converts to markdown heading`() {
        val html = "<html><body><h3>Section</h3></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("### Section"))
    }

    @Test
    fun `h4 converts to markdown heading`() {
        val html = "<html><body><h4>Subsection</h4></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("#### Subsection"))
    }

    @Test
    fun `h5 converts to markdown heading`() {
        val html = "<html><body><h5>Minor</h5></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("##### Minor"))
    }

    @Test
    fun `h6 converts to markdown heading`() {
        val html = "<html><body><h6>Smallest</h6></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("###### Smallest"))
    }

    @Test
    fun `paragraph converts with blank line separation`() {
        val html = "<html><body><p>First paragraph</p><p>Second paragraph</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("First paragraph"))
        assertTrue(result.body.contains("Second paragraph"))
        assertTrue(result.body.contains("First paragraph\n\n"))
    }

    @Test
    fun `unordered list converts to dash items`() {
        val html = "<html><body><ul><li>Apple</li><li>Banana</li></ul></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("- Apple"))
        assertTrue(result.body.contains("- Banana"))
    }

    @Test
    fun `ordered list converts to numbered items`() {
        val html = "<html><body><ol><li>First</li><li>Second</li><li>Third</li></ol></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("1. First"))
        assertTrue(result.body.contains("2. Second"))
        assertTrue(result.body.contains("3. Third"))
    }

    @Test
    fun `link converts to markdown link`() {
        val html = """<html><body><a href="https://example.com">Click here</a></body></html>"""
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("[Click here](https://example.com)"))
    }

    @Test
    fun `strong tag converts to bold`() {
        val html = "<html><body><p>This is <strong>bold</strong> text</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("**bold**"))
    }

    @Test
    fun `b tag converts to bold`() {
        val html = "<html><body><p>This is <b>bold</b> text</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("**bold**"))
    }

    @Test
    fun `em tag converts to italic`() {
        val html = "<html><body><p>This is <em>italic</em> text</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("*italic*"))
    }

    @Test
    fun `i tag converts to italic`() {
        val html = "<html><body><p>This is <i>italic</i> text</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("*italic*"))
    }

    @Test
    fun `pre code converts to fenced code block`() {
        val html = "<html><body><pre><code>val x = 1\nval y = 2</code></pre></body></html>"
        val result = HtmlToMarkdown.convert(html)
        val backtickFence = "``" + "`"
        assertTrue(result.body.contains(backtickFence))
        assertTrue(result.body.contains("val x = 1"))
        assertTrue(result.body.contains("val y = 2"))
    }

    @Test
    fun `inline code converts to backtick`() {
        val html = "<html><body><p>Use <code>println</code> to print</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("`println`"))
    }

    @Test
    fun `blockquote converts to quoted text`() {
        val html = "<html><body><blockquote>Quoted text here</blockquote></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("> Quoted text here"))
    }

    @Test
    fun `table converts to markdown table`() {
        val html =
            "<html><body>" +
                "<table>" +
                "<thead><tr><th>Name</th><th>Age</th></tr></thead>" +
                "<tbody><tr><td>Alice</td><td>30</td></tr></tbody>" +
                "</table>" +
                "</body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("| Name | Age |"))
        assertTrue(result.body.contains("| --- | --- |"))
        assertTrue(result.body.contains("| Alice | 30 |"))
    }

    @Test
    fun `script tags are stripped`() {
        val html = "<html><body><p>Content</p><script>alert('xss')</script></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertFalse(result.body.contains("alert"))
        assertTrue(result.body.contains("Content"))
    }

    @Test
    fun `style tags are stripped`() {
        val html = "<html><body><style>body{color:red}</style><p>Content</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertFalse(result.body.contains("color"))
        assertTrue(result.body.contains("Content"))
    }

    @Test
    fun `nav tags are stripped`() {
        val html = "<html><body><nav><a href='/'>Home</a></nav><p>Content</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertFalse(result.body.contains("Home"))
        assertTrue(result.body.contains("Content"))
    }

    @Test
    fun `footer tags are stripped`() {
        val html = "<html><body><p>Content</p><footer>Copyright 2024</footer></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertFalse(result.body.contains("Copyright"))
        assertTrue(result.body.contains("Content"))
    }

    @Test
    fun `header tags are stripped`() {
        val html = "<html><body><header>Site Header</header><p>Content</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertFalse(result.body.contains("Site Header"))
        assertTrue(result.body.contains("Content"))
    }

    @Test
    fun `aside tags are stripped`() {
        val html = "<html><body><aside>Sidebar</aside><p>Content</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertFalse(result.body.contains("Sidebar"))
        assertTrue(result.body.contains("Content"))
    }

    @Test
    fun `title extraction from title tag`() {
        val html = "<html><head><title>My Page Title</title></head><body><p>Hello</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertEquals("My Page Title", result.title)
    }

    @Test
    fun `meta description extraction`() {
        val html =
            "<html><head>" +
                "<meta name=\"description\" content=\"A description of the page\">" +
                "</head><body><p>Hello</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertEquals("A description of the page", result.description)
    }

    @Test
    fun `empty html produces empty body`() {
        val html = "<html><body></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertEquals("", result.body.trim())
    }

    @Test
    fun `whitespace-only html produces empty body`() {
        val html = "<html><body>   \n  \t  </body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertEquals("", result.body.trim())
    }

    @Test
    fun `br tag converts to newline`() {
        val html = "<html><body><p>Line one<br>Line two</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("Line one\nLine two"))
    }

    @Test
    fun `img with alt and src converts to markdown image`() {
        val html = "<html><body><img alt=\"Logo\" src=\"https://example.com/logo.png\"></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("![Logo](https://example.com/logo.png)"))
    }

    @Test
    fun `nested bold inside paragraph`() {
        val html = "<html><body><p>Hello <strong>world</strong> today</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("Hello **world** today"))
    }

    @Test
    fun `link inside list item`() {
        val html =
            "<html><body>" +
                "<ul><li><a href=\"https://example.com\">Example</a></li></ul>" +
                "</body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("- [Example](https://example.com)"))
    }

    @Test
    fun `excessive whitespace is collapsed`() {
        val html = "<html><body><p>First</p><p></p><p></p><p></p><p>Second</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertFalse(result.body.contains("\n\n\n"))
    }

    @Test
    fun `SPA detection with short body and many scripts`() {
        val html =
            "<html><body>" +
                "<div id=\"root\"></div>" +
                "<script src=\"bundle1.js\"></script>" +
                "<script src=\"bundle2.js\"></script>" +
                "<script src=\"bundle3.js\"></script>" +
                "</body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.spaWarning)
    }

    @Test
    fun `non-SPA page with normal content`() {
        val longContent = "A".repeat(600)
        val html = "<html><body><p>$longContent</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertFalse(result.spaWarning)
    }

    @Test
    fun `missing title returns empty string`() {
        val html = "<html><body><p>No title</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertEquals("", result.title)
    }

    @Test
    fun `missing meta description returns empty string`() {
        val html = "<html><body><p>No description</p></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertEquals("", result.description)
    }

    @Test
    fun `table without thead uses first row as header`() {
        val html =
            "<html><body>" +
                "<table>" +
                "<tr><td>Name</td><td>Age</td></tr>" +
                "<tr><td>Bob</td><td>25</td></tr>" +
                "</table>" +
                "</body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("| Name | Age |"))
        assertTrue(result.body.contains("| --- | --- |"))
        assertTrue(result.body.contains("| Bob | 25 |"))
    }

    @Test
    fun `SPA detection false when few scripts`() {
        val html =
            "<html><body>" +
                "<div>Small content</div>" +
                "<script src=\"app.js\"></script>" +
                "</body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertFalse(result.spaWarning)
    }

    @Test
    fun `img without alt uses empty alt text`() {
        val html = "<html><body><img src=\"https://example.com/pic.png\"></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("![](https://example.com/pic.png)"))
    }

    @Test
    fun `multiple headings at different levels`() {
        val html =
            "<html><body>" +
                "<h1>Main</h1>" +
                "<h2>Sub</h2>" +
                "<h3>Detail</h3>" +
                "</body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("# Main"))
        assertTrue(result.body.contains("## Sub"))
        assertTrue(result.body.contains("### Detail"))
    }

    @Test
    fun `blockquote with multiple lines`() {
        val html = "<html><body><blockquote><p>Line one</p><p>Line two</p></blockquote></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("> Line one"))
        assertTrue(result.body.contains("> Line two"))
    }

    @Test
    fun `link without href`() {
        val html = "<html><body><a>No link</a></body></html>"
        val result = HtmlToMarkdown.convert(html)
        assertTrue(result.body.contains("No link"))
    }
}
