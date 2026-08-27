package com.epubmerger.app.epub

import com.epubmerger.app.model.Chapter
import com.epubmerger.app.model.EpubBook
import com.epubmerger.app.model.MergeSelection
import org.jsoup.Jsoup
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a brand-new, valid .epub file out of pieces pulled from several source books,
 * according to a [MergeSelection].
 */
object EpubBuilder {

    fun build(books: List<EpubBook>, selection: MergeSelection, out: OutputStream) {
        val byIndex = books.associateBy { it.sourceIndex }
        val coverBook = byIndex.getValue(selection.coverBookIndex)
        val metaBook = byIndex.getValue(selection.metadataBookIndex)
        val styleBook = byIndex.getValue(selection.stylesheetBookIndex)
        val baseBook = byIndex.getValue(selection.baseChaptersBookIndex)

        // Resolve final chapter list, honoring any per-chapter overrides.
        val finalChapters: List<Chapter> = baseBook.chapters.mapIndexed { i, ch ->
            val overrideBookIndex = selection.chapterOverrides[i]
            if (overrideBookIndex != null && overrideBookIndex != baseBook.sourceIndex) {
                byIndex[overrideBookIndex]?.chapters?.getOrNull(i) ?: ch
            } else ch
        }

        // Merge stylesheet content into one file.
        val mergedCss = styleBook.cssFiles.values.joinToString("\n\n")
            .ifBlank { DEFAULT_CSS }

        // Collect images: cover book's cover + any images referenced by the chosen chapters'
        // source books (we don't attempt reference-tracing per-chapter; we bundle every image
        // from every book contributing a chapter, which is always safe, just occasionally a
        // few KB larger than strictly necessary).
        val contributingBooks = (listOf(baseBook) + selection.chapterOverrides.values.mapNotNull { byIndex[it] }).distinct()
        val imageEntries = LinkedHashMap<String, ByteArray>()
        for (b in contributingBooks) {
            for ((name, bytes) in b.images) imageEntries.putIfAbsent(name, bytes)
        }

        val coverFileName = coverBook.coverBytes?.let {
            "cover." + extensionFor(coverBook.coverMediaType)
        }

        val uid = "urn:uuid:${UUID.randomUUID()}"

        ZipOutputStream(out).use { zip ->
            // 1. mimetype MUST be first and stored (not deflated), per the epub spec.
            writeStored(zip, "mimetype", "application/epub+zip".toByteArray(Charsets.UTF_8))

            // 2. container.xml
            writeDeflated(
                zip, "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
""".toByteArray(Charsets.UTF_8)
            )

            // 3. Stylesheet
            writeDeflated(zip, "OEBPS/Styles/stylesheet.css", mergedCss.toByteArray(Charsets.UTF_8))

            // 4. Cover image
            if (coverFileName != null) {
                writeDeflated(zip, "OEBPS/Images/$coverFileName", coverBook.coverBytes!!)
            }

            // 5. Other images
            for ((name, bytes) in imageEntries) {
                if (name != coverFileName) writeDeflated(zip, "OEBPS/Images/$name", bytes)
            }

            // 6. Chapters (rewrite each chapter's <link> to point at our merged stylesheet)
            val chapterFileNames = mutableListOf<String>()
            finalChapters.forEachIndexed { i, chapter ->
                val fileName = "chapter${(i + 1).toString().padStart(3, '0')}.xhtml"
                chapterFileNames.add(fileName)
                val xhtml = renderChapterXhtml(chapter)
                writeDeflated(zip, "OEBPS/Text/$fileName", xhtml.toByteArray(Charsets.UTF_8))
            }

            // 7. nav.xhtml (EPUB3 table of contents)
            writeDeflated(
                zip, "OEBPS/nav.xhtml",
                renderNav(metaBook.title, finalChapters, chapterFileNames).toByteArray(Charsets.UTF_8)
            )

            // 8. toc.ncx (EPUB2 compatibility table of contents)
            writeDeflated(
                zip, "OEBPS/toc.ncx",
                renderNcx(uid, metaBook.title, finalChapters, chapterFileNames).toByteArray(Charsets.UTF_8)
            )

            // 9. content.opf (package document tying everything together)
            writeDeflated(
                zip, "OEBPS/content.opf",
                renderOpf(
                    uid = uid,
                    title = metaBook.title,
                    author = metaBook.author,
                    language = metaBook.language,
                    coverFileName = coverFileName,
                    coverMediaType = coverBook.coverMediaType,
                    imageFiles = imageEntries.keys.filter { it != coverFileName },
                    chapterFileNames = chapterFileNames
                ).toByteArray(Charsets.UTF_8)
            )
        }
    }

    private fun renderChapterXhtml(chapter: Chapter): String {
        // Re-wrap the stored body HTML in a clean xhtml shell pointing at the merged stylesheet.
        val safeTitle = Jsoup.parse("<div>${chapter.title}</div>").text()
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>${escapeXml(safeTitle)}</title>
  <link rel="stylesheet" type="text/css" href="../Styles/stylesheet.css"/>
</head>
<body>
${chapter.htmlContent}
</body>
</html>
"""
    }

    private fun renderNav(bookTitle: String, chapters: List<Chapter>, fileNames: List<String>): String {
        val items = chapters.indices.joinToString("\n") { i ->
            "      <li><a href=\"Text/${fileNames[i]}\">${escapeXml(chapters[i].title)}</a></li>"
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>${escapeXml(bookTitle)}</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>Table of Contents</h1>
    <ol>
$items
    </ol>
  </nav>
</body>
</html>
"""
    }

    private fun renderNcx(uid: String, bookTitle: String, chapters: List<Chapter>, fileNames: List<String>): String {
        val navPoints = chapters.indices.joinToString("\n") { i ->
            """    <navPoint id="navPoint-${i + 1}" playOrder="${i + 1}">
      <navLabel><text>${escapeXml(chapters[i].title)}</text></navLabel>
      <content src="Text/${fileNames[i]}"/>
    </navPoint>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="$uid"/>
  </head>
  <docTitle><text>${escapeXml(bookTitle)}</text></docTitle>
  <navMap>
$navPoints
  </navMap>
</ncx>
"""
    }

    private fun renderOpf(
        uid: String,
        title: String,
        author: String,
        language: String,
        coverFileName: String?,
        coverMediaType: String?,
        imageFiles: List<String>,
        chapterFileNames: List<String>
    ): String {
        val manifestChapters = chapterFileNames.joinToString("\n") { name ->
            "    <item id=\"${idFor(name)}\" href=\"Text/$name\" media-type=\"application/xhtml+xml\"/>"
        }
        val manifestImages = imageFiles.joinToString("\n") { name ->
            "    <item id=\"${idFor(name)}\" href=\"Images/$name\" media-type=\"${mimeForImage(name)}\"/>"
        }
        val coverManifest = if (coverFileName != null) {
            "    <item id=\"cover-image\" href=\"Images/$coverFileName\" media-type=\"${coverMediaType ?: mimeForImage(coverFileName)}\" properties=\"cover-image\"/>"
        } else ""
        val spine = chapterFileNames.joinToString("\n") { name ->
            "    <itemref idref=\"${idFor(name)}\"/>"
        }
        val coverMeta = if (coverFileName != null) "    <meta name=\"cover\" content=\"cover-image\"/>" else ""

        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="BookId">$uid</dc:identifier>
    <dc:title>${escapeXml(title)}</dc:title>
    <dc:creator>${escapeXml(author)}</dc:creator>
    <dc:language>${escapeXml(language.ifBlank { "en" })}</dc:language>
$coverMeta
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="stylesheet" href="Styles/stylesheet.css" media-type="text/css"/>
$coverManifest
$manifestChapters
$manifestImages
  </manifest>
  <spine toc="ncx">
$spine
  </spine>
</package>
"""
    }

    private fun idFor(fileName: String) = "id-" + fileName.replace(Regex("[^a-zA-Z0-9]"), "-")

    private fun mimeForImage(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun extensionFor(mediaType: String?): String = when (mediaType) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/svg+xml" -> "svg"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun writeStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        val crc = CRC32()
        crc.update(bytes)
        entry.crc = crc.value
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeDeflated(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.DEFLATED
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private val DEFAULT_CSS = """
        body { font-family: serif; line-height: 1.4; margin: 1em; }
        h1, h2, h3 { text-align: center; }
    """.trimIndent()
}
