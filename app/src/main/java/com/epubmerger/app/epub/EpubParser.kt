package com.epubmerger.app.epub

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.epubmerger.app.model.Chapter
import com.epubmerger.app.model.EpubBook
import com.epubmerger.app.model.ManifestItem
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Reads an .epub (a zip container) into an in-memory [EpubBook].
 *
 * EPUBs are just zip files. The structure we rely on:
 *   META-INF/container.xml  -> points to the real "package document" (the OPF file)
 *   <opf path>               -> lists every file in the book ("manifest") plus reading
 *                                order ("spine") and title/author metadata
 *
 * We don't try to be a fully spec-complete EPUB reader (no support for encrypted/DRM
 * files, no epub2 guide fallback beyond what's below) -- just enough to reliably read
 * normal Calibre/Kindle/Standard-Ebooks-style files, which covers the vast majority of
 * epubs found in the wild.
 */
object EpubParser {

    class EpubParseException(message: String) : Exception(message)

    fun parse(context: Context, uri: Uri, sourceIndex: Int, displayName: String): EpubBook {
        val entries = readZipEntries(context, uri)
            ?: throw EpubParseException("Could not open $displayName as a zip/epub file.")

        val containerBytes = entries["META-INF/container.xml"]
            ?: throw EpubParseException("$displayName is missing META-INF/container.xml — not a valid epub.")

        val opfPath = findOpfPath(containerBytes)
        val opfBytes = entries[opfPath]
            ?: throw EpubParseException("$displayName's container.xml points to a missing file: $opfPath")

        val opfDir = opfPath.substringBeforeLast('/', "")
        fun resolve(href: String): String {
            if (href.isBlank()) return href
            val combined = if (opfDir.isEmpty()) href else "$opfDir/$href"
            // Collapse "a/b/../c" style relative paths.
            val parts = mutableListOf<String>()
            for (segment in combined.split('/')) {
                when (segment) {
                    ".", "" -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(segment)
                }
            }
            return parts.joinToString("/")
        }

        val opfInfo = parseOpf(opfBytes)

        val book = EpubBook(
            sourceIndex = sourceIndex,
            displayName = displayName,
            title = opfInfo.title.ifBlank { displayName },
            author = opfInfo.author.ifBlank { "Unknown" },
            language = opfInfo.language.ifBlank { "en" }
        )

        // Manifest: id -> (resolved path, media type, properties)
        val manifestByHref = mutableMapOf<String, ManifestItem>()
        val idToItem = mutableMapOf<String, ManifestItem>()
        for (raw in opfInfo.manifestItems) {
            val resolved = resolve(raw.href)
            val item = raw.copy(href = resolved)
            idToItem[raw.id] = item
            manifestByHref[resolved] = item
        }

        // Cover image: epub3 "properties=cover-image", else epub2 <meta name="cover" content="ID"/>
        var coverItem = idToItem.values.firstOrNull { it.properties?.contains("cover-image") == true }
        if (coverItem == null && opfInfo.coverMetaId != null) {
            coverItem = idToItem[opfInfo.coverMetaId]
        }
        if (coverItem != null) {
            entries[coverItem.href]?.let { bytes ->
                book.coverBytes = bytes
                book.coverMediaType = coverItem.mediaType
            }
        }

        // Stylesheets
        for (item in idToItem.values) {
            if (item.mediaType == "text/css") {
                entries[item.href]?.let { bytes ->
                    val name = item.href.substringAfterLast('/')
                    book.cssFiles[name] = String(bytes, Charsets.UTF_8)
                }
            }
        }

        // Other images (skip the cover, already captured)
        for (item in idToItem.values) {
            if (item.mediaType.startsWith("image/") && item.href != coverItem?.href) {
                entries[item.href]?.let { bytes ->
                    val name = item.href.substringAfterLast('/')
                    book.images[name] = bytes
                }
            }
        }

        // Spine (reading order) -> chapters
        var order = 0
        for (idref in opfInfo.spineIdrefs) {
            val item = idToItem[idref] ?: continue
            if (!(item.mediaType == "application/xhtml+xml" || item.mediaType == "text/html")) continue
            val bytes = entries[item.href] ?: continue
            val html = String(bytes, Charsets.UTF_8)
            val doc = Jsoup.parse(html)
            val bodyHtml = doc.body()?.html() ?: html
            val guessedTitle = guessChapterTitle(doc, order)
            book.chapters.add(
                Chapter(
                    id = item.id,
                    title = guessedTitle,
                    htmlContent = bodyHtml,
                    order = order
                )
            )
            order++
        }

        if (book.chapters.isEmpty()) {
            throw EpubParseException("$displayName has no readable chapters in its spine.")
        }

        return book
    }

    private fun guessChapterTitle(doc: org.jsoup.nodes.Document, order: Int): String {
        val heading = doc.selectFirst("h1, h2, h3")?.text()?.trim()
        if (!heading.isNullOrBlank()) return heading
        val docTitle = doc.title().trim()
        if (docTitle.isNotBlank()) return docTitle
        return "Chapter ${order + 1}"
    }

    private fun readZipEntries(context: Context, uri: Uri): Map<String, ByteArray>? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val map = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (zis.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                    val name = entry.name.replace('\\', '/').removePrefix("/")
                    map[name] = out.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return map
    }

    private fun findOpfPath(containerXml: ByteArray): String {
        val parser = newParser(containerXml)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && localName(parser.name) == "rootfile") {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (!fullPath.isNullOrBlank()) return fullPath
            }
            eventType = parser.next()
        }
        throw EpubParseException("Could not find the package document path in container.xml")
    }

    private data class OpfInfo(
        val title: String,
        val author: String,
        val language: String,
        val coverMetaId: String?,
        val manifestItems: List<ManifestItem>,
        val spineIdrefs: List<String>
    )

    private fun parseOpf(opfBytes: ByteArray): OpfInfo {
        val parser = newParser(opfBytes)
        var title = ""
        var author = ""
        var language = ""
        var coverMetaId: String? = null
        val manifestItems = mutableListOf<ManifestItem>()
        val spineIdrefs = mutableListOf<String>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (localName(parser.name)) {
                    "title" -> if (title.isBlank()) title = readText(parser).trim()
                    "creator" -> if (author.isBlank()) author = readText(parser).trim()
                    "language" -> if (language.isBlank()) language = readText(parser).trim()
                    "meta" -> {
                        val name = parser.getAttributeValue(null, "name")
                        if (name == "cover") {
                            coverMetaId = parser.getAttributeValue(null, "content")
                        }
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                        val properties = parser.getAttributeValue(null, "properties")
                        if (id.isNotBlank() && href.isNotBlank()) {
                            manifestItems.add(ManifestItem(id, href, mediaType, properties))
                        }
                    }
                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref")
                        if (!idref.isNullOrBlank()) spineIdrefs.add(idref)
                    }
                }
            }
            eventType = parser.next()
        }
        return OpfInfo(title, author, language, coverMetaId, manifestItems, spineIdrefs)
    }

    /** Strips an XML namespace prefix like "dc:title" -> "title". */
    private fun localName(name: String): String = name.substringAfter(':')

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        var eventType = parser.next()
        while (depth > 0) {
            when (eventType) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
            if (depth > 0) eventType = parser.next()
        }
        return sb.toString()
    }

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(bytes.inputStream(), "UTF-8")
        return parser
    }
}
