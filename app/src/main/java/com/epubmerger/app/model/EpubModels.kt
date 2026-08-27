package com.epubmerger.app.model

/** One manifest entry from the OPF file (an id -> href -> media-type mapping). */
data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String? = null
)

/** A single spine/chapter entry with its resolved HTML body. */
data class Chapter(
    val id: String,
    var title: String,
    val htmlContent: String,
    val order: Int
)

/**
 * Everything Epub Merger extracted from one imported .epub file.
 * `sourceIndex` is the position it was imported in (0, 1, 2 ...) and is used
 * throughout the UI as a stable "Book A / Book B / Book C" identifier.
 */
data class EpubBook(
    val sourceIndex: Int,
    val displayName: String,
    var title: String,
    var author: String,
    var language: String = "en",
    var coverBytes: ByteArray? = null,
    var coverMediaType: String? = null,
    val cssFiles: MutableMap<String, String> = mutableMapOf(),   // filename -> css text
    val images: MutableMap<String, ByteArray> = mutableMapOf(),  // filename -> bytes (excludes cover)
    val chapters: MutableList<Chapter> = mutableListOf()
) {
    val chapterCount: Int get() = chapters.size
    val wordCount: Int
        get() = chapters.sumOf { ch ->
            ch.htmlContent
                .replace(Regex("<[^>]*>"), " ")
                .trim()
                .split(Regex("\\s+"))
                .count { it.isNotBlank() }
        }
    val hasStylesheet: Boolean get() = cssFiles.isNotEmpty()

    override fun equals(other: Any?): Boolean = other is EpubBook && other.sourceIndex == sourceIndex
    override fun hashCode(): Int = sourceIndex
}

/** The choices the user has made on the Merge screen. */
data class MergeSelection(
    val coverBookIndex: Int,
    val metadataBookIndex: Int,
    val stylesheetBookIndex: Int,
    val baseChaptersBookIndex: Int,
    // chapterIndex -> overriding book's sourceIndex (only used when all books share a chapter count)
    val chapterOverrides: Map<Int, Int> = emptyMap()
)
