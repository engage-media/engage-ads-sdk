package com.engage.ads.core

import com.engage.ads.EngageError
import com.engage.ads.ResourceLimits
import com.engage.ads.exceedsUtf8Bytes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal fun validateVastMarkup(markup: String) {
    if (markup.exceedsUtf8Bytes(ResourceLimits.MAX_VAST_BYTES)) {
        throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "VAST markup exceeds the size limit")
    }
    if (containsForbiddenDeclaration(markup)) {
        throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "VAST markup contains a prohibited XML declaration")
    }
    requireXmlDepth(markup)
    val document = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's bundled XML provider varies by API level and rejects some otherwise
            // standard feature names. The lexical declaration guard and rejecting resolver are
            // the security boundary; these are additional hardening where the provider supports
            // them and must not make ordinary VAST fail to parse.
            tryFeature("http://javax.xml.XMLConstants/feature/secure-processing", true)
            tryFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            tryFeature("http://xml.org/sax/features/external-general-entities", false)
            tryFeature("http://xml.org/sax/features/external-parameter-entities", false)
            tryFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            try { isXIncludeAware = false } catch (_: Exception) {}
            try { isExpandEntityReferences = false } catch (_: Exception) {}
            try { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") } catch (_: Exception) {}
            try { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") } catch (_: Exception) {}
        }
        factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("external XML entities are prohibited") }
        }.parse(InputSource(StringReader(markup)))
    } catch (error: Exception) {
        throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "VAST markup is malformed", error)
    }
    if (!document.documentElement.localName.equals("VAST", ignoreCase = true)) {
        throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "VAST root element is missing")
    }
    val ads = document.getElementsByTagNameNS("*", "Ad")
    if (ads.length == 0 && document.getElementsByTagName("Ad").length == 0) {
        throw EngageError(EngageError.Code.NO_FILL, "VAST contains no ads")
    }
}


private fun DocumentBuilderFactory.tryFeature(name: String, enabled: Boolean) {
    try { setFeature(name, enabled) } catch (_: Exception) {}
}

private fun requireXmlDepth(xml: String) {
    var index = 0
    var depth = 0
    while (index < xml.length) {
        val start = xml.indexOf('<', index)
        if (start < 0) return
        when {
            xml.regionMatches(start, "<!--", 0, 4) -> index = xml.indexOf("-->", start + 4).let { if (it < 0) return else it + 3 }
            xml.regionMatches(start, "<![CDATA[", 0, 9) -> index = xml.indexOf("]]>", start + 9).let { if (it < 0) return else it + 3 }
            xml.regionMatches(start, "<?", 0, 2) -> index = xml.indexOf("?>", start + 2).let { if (it < 0) return else it + 2 }
            else -> {
                var cursor = start + 1
                var quote: Char? = null
                while (cursor < xml.length) {
                    val character = xml[cursor]
                    if (quote == null && (character == '\'' || character == '"')) quote = character
                    else if (quote == character) quote = null
                    else if (quote == null && character == '>') break
                    cursor += 1
                }
                if (cursor >= xml.length) return
                val closing = xml.getOrNull(start + 1) == '/'
                val declaration = xml.getOrNull(start + 1) == '!'
                var beforeEnd = cursor - 1
                while (beforeEnd > start && xml[beforeEnd].isWhitespace()) beforeEnd -= 1
                val selfClosing = xml.getOrNull(beforeEnd) == '/'
                if (closing) depth = (depth - 1).coerceAtLeast(0)
                else if (!declaration && !selfClosing) {
                    depth += 1
                    if (depth > ResourceLimits.MAX_XML_DEPTH) throw EngageError(EngageError.Code.MALFORMED_RESPONSE, "VAST XML nesting exceeds the limit")
                }
                index = cursor + 1
            }
        }
    }
}

/**
 * Reject DTD and entity declarations before handing input to a platform XML provider. Comments,
 * CDATA and processing instructions are skipped so harmless declaration-like text remains valid.
 */
private fun containsForbiddenDeclaration(xml: String): Boolean {
    var index = 0
    while (index < xml.length) {
        val start = xml.indexOf('<', index)
        if (start < 0) return false
        when {
            xml.regionMatches(start, "<!--", 0, 4) -> {
                val end = xml.indexOf("-->", start + 4)
                if (end < 0) return false // The XML parser reports the unterminated comment.
                index = end + 3
            }
            xml.regionMatches(start, "<![CDATA[", 0, 9) -> {
                val end = xml.indexOf("]]>", start + 9)
                if (end < 0) return false // The XML parser reports the unterminated CDATA.
                index = end + 3
            }
            xml.regionMatches(start, "<?", 0, 2) -> {
                val end = xml.indexOf("?>", start + 2)
                if (end < 0) return false // The XML parser reports the unterminated PI.
                index = end + 2
            }
            else -> {
                var cursor = start + 1
                while (cursor < xml.length && xml[cursor].isWhitespace()) cursor += 1
                if (cursor < xml.length && xml[cursor] == '!') {
                    cursor += 1
                    while (cursor < xml.length && xml[cursor].isWhitespace()) cursor += 1
                    val forbidden = listOf("DOCTYPE", "ENTITY").any { keyword ->
                        xml.regionMatches(cursor, keyword, 0, keyword.length, ignoreCase = true) &&
                            xml.getOrNull(cursor + keyword.length)?.let { !it.isLetterOrDigit() && it != '_' && it != '-' } != false
                    }
                    if (forbidden) return true
                }
                index = start + 1
            }
        }
    }
    return false
}
