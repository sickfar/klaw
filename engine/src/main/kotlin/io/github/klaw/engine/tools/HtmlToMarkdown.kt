package io.github.klaw.engine.tools

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

object HtmlToMarkdown {
    data class Result(
        val title: String,
        val description: String,
        val body: String,
        val spaWarning: Boolean,
    )

    private const val SPA_BODY_THRESHOLD = 500
    private const val SPA_SCRIPT_THRESHOLD = 3

    private val walker = MarkdownDomWalker()

    fun convert(html: String): Result {
        val doc = Jsoup.parse(html)
        val title = extractTitle(doc)
        val description = extractDescription(doc)
        val scriptCount = doc.select("script").size
        walker.removeStrippedTags(doc)
        val body = doc.body()
        val sb = StringBuilder()
        if (body != null) {
            walker.convertNode(body, sb)
        }
        val bodyText = walker.collapseWhitespace(sb.toString().trim())
        val isSpa = bodyText.length < SPA_BODY_THRESHOLD && scriptCount >= SPA_SCRIPT_THRESHOLD
        return Result(title, description, bodyText, isSpa)
    }

    private fun extractTitle(doc: Document): String = doc.selectFirst("title")?.text().orEmpty()

    private fun extractDescription(doc: Document): String =
        doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
}

private class MarkdownDomWalker {
    private val strippedTags = setOf("script", "style", "nav", "footer", "header", "aside")
    private val headingPrefixes =
        mapOf(
            "h1" to "#",
            "h2" to "##",
            "h3" to "###",
            "h4" to "####",
            "h5" to "#####",
            "h6" to "######",
        )
    private val maxConsecutiveNewlines = 2

    fun removeStrippedTags(doc: Document) {
        strippedTags.forEach { tag -> doc.select(tag).remove() }
    }

    fun convertNode(
        node: Node,
        sb: StringBuilder,
    ) {
        when (node) {
            is TextNode -> sb.append(node.text())
            is Element -> convertElement(node, sb)
        }
    }

    fun collapseWhitespace(text: String): String {
        val pattern = Regex("\n{${maxConsecutiveNewlines + 1},}")
        return pattern.replace(text, "\n".repeat(maxConsecutiveNewlines))
    }

    private fun convertElement(
        element: Element,
        sb: StringBuilder,
    ) {
        val tag = element.tagName()
        val headingPrefix = headingPrefixes[tag]
        if (headingPrefix != null) {
            convertHeading(element, sb, headingPrefix)
            return
        }
        convertByTag(element, sb, tag)
    }

    private fun convertByTag(
        element: Element,
        sb: StringBuilder,
        tag: String,
    ) {
        when (tag) {
            "p" -> convertParagraph(element, sb)
            "ul" -> convertUnorderedList(element, sb)
            "ol" -> convertOrderedList(element, sb)
            "a" -> convertLink(element, sb)
            "strong", "b" -> wrapChildren(element, sb, "**")
            "em", "i" -> wrapChildren(element, sb, "*")
            "pre" -> convertPreformatted(element, sb)
            "code" -> sb.append("`").append(element.text()).append("`")
            "blockquote" -> convertBlockquote(element, sb)
            "table" -> convertTable(element, sb)
            "img" -> convertImage(element, sb)
            "br" -> sb.append("\n")
            else -> convertChildren(element, sb)
        }
    }

    private fun convertHeading(
        element: Element,
        sb: StringBuilder,
        prefix: String,
    ) {
        sb.append(prefix).append(" ")
        convertChildren(element, sb)
        sb.append("\n\n")
    }

    private fun convertParagraph(
        element: Element,
        sb: StringBuilder,
    ) {
        convertChildren(element, sb)
        sb.append("\n\n")
    }

    private fun convertUnorderedList(
        element: Element,
        sb: StringBuilder,
    ) {
        for (li in element.children().filter { it.tagName() == "li" }) {
            sb.append("- ")
            convertChildren(li, sb)
            sb.append("\n")
        }
        sb.append("\n")
    }

    private fun convertOrderedList(
        element: Element,
        sb: StringBuilder,
    ) {
        element.children().filter { it.tagName() == "li" }.forEachIndexed { index, li ->
            sb.append("${index + 1}. ")
            convertChildren(li, sb)
            sb.append("\n")
        }
        sb.append("\n")
    }

    private fun convertLink(
        element: Element,
        sb: StringBuilder,
    ) {
        val href = element.attr("href")
        if (href.isNotEmpty()) {
            sb.append("[")
            convertChildren(element, sb)
            sb.append("](").append(href).append(")")
        } else {
            convertChildren(element, sb)
        }
    }

    private fun wrapChildren(
        element: Element,
        sb: StringBuilder,
        wrapper: String,
    ) {
        sb.append(wrapper)
        convertChildren(element, sb)
        sb.append(wrapper)
    }

    private fun convertPreformatted(
        element: Element,
        sb: StringBuilder,
    ) {
        val codeElement = element.selectFirst("code")
        val codeText = codeElement?.wholeText() ?: element.wholeText()
        sb.append("```\n").append(codeText).append("\n```\n\n")
    }

    private fun convertBlockquote(
        element: Element,
        sb: StringBuilder,
    ) {
        val inner = StringBuilder()
        convertChildren(element, inner)
        val text = inner.toString().trim()
        for (line in text.split("\n")) {
            sb.append("> ").append(line).append("\n")
        }
        sb.append("\n")
    }

    private fun convertTable(
        element: Element,
        sb: StringBuilder,
    ) {
        val rows = element.select("tr")
        if (rows.isEmpty()) return
        val headerRow = rows.first()!!.select("th, td").map { it.text() }
        sb.append("| ").append(headerRow.joinToString(" | ")).append(" |\n")
        sb.append("| ").append(headerRow.joinToString(" | ") { "---" }).append(" |\n")
        for (row in rows.drop(1)) {
            val cells = row.select("th, td").map { it.text() }
            sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
        }
        sb.append("\n")
    }

    private fun convertImage(
        element: Element,
        sb: StringBuilder,
    ) {
        val alt = element.attr("alt")
        val src = element.attr("src")
        sb
            .append("![")
            .append(alt)
            .append("](")
            .append(src)
            .append(")")
    }

    private fun convertChildren(
        element: Element,
        sb: StringBuilder,
    ) {
        for (child in element.childNodes()) {
            convertNode(child, sb)
        }
    }
}
