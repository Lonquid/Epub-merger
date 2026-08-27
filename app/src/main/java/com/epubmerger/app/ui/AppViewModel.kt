package com.epubmerger.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubmerger.app.epub.EpubBuilder
import com.epubmerger.app.epub.EpubParser
import com.epubmerger.app.model.EpubBook
import com.epubmerger.app.model.MergeSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

sealed class LoadState {
    object Idle : LoadState()
    object Loading : LoadState()
    data class Error(val message: String) : LoadState()
}

sealed class BuildState {
    object Idle : BuildState()
    object Building : BuildState()
    object Done : BuildState()
    data class Error(val message: String) : BuildState()
}

class AppViewModel : ViewModel() {

    var books by mutableStateOf<List<EpubBook>>(emptyList())
        private set

    var loadState by mutableStateOf<LoadState>(LoadState.Idle)
        private set

    var buildState by mutableStateOf<BuildState>(BuildState.Idle)
        private set

    // Merge selections, defaulted once >=2 books are loaded.
    var coverBookIndex by mutableStateOf<Int?>(null)
    var metadataBookIndex by mutableStateOf<Int?>(null)
    var stylesheetBookIndex by mutableStateOf<Int?>(null)
    var baseChaptersBookIndex by mutableStateOf<Int?>(null)
    var chapterOverrides by mutableStateOf<Map<Int, Int>>(emptyMap())
        private set

    private var nextSourceIndex = 0

    fun addBook(context: Context, uri: Uri) {
        loadState = LoadState.Loading
        val displayName = queryDisplayName(context, uri) ?: "Book ${nextSourceIndex + 1}"
        viewModelScope.launch {
            try {
                val index = nextSourceIndex
                val book = withContext(Dispatchers.IO) {
                    EpubParser.parse(context, uri, index, displayName)
                }
                nextSourceIndex++
                books = books + book
                loadState = LoadState.Idle
                ensureDefaultSelections()
            } catch (e: Exception) {
                loadState = LoadState.Error(e.message ?: "Could not read that epub file.")
            }
        }
    }

    fun removeBook(sourceIndex: Int) {
        books = books.filterNot { it.sourceIndex == sourceIndex }
        ensureDefaultSelections()
    }

    fun setChapterOverride(chapterIndex: Int, bookIndex: Int) {
        chapterOverrides = chapterOverrides + (chapterIndex to bookIndex)
    }

    fun clearChapterOverride(chapterIndex: Int) {
        chapterOverrides = chapterOverrides - chapterIndex
    }

    /** True only when every loaded book has the same chapter count, enabling per-chapter mixing. */
    fun chapterCountsMatch(): Boolean {
        val counts = books.map { it.chapterCount }.distinct()
        return books.size >= 2 && counts.size == 1
    }

    private fun ensureDefaultSelections() {
        val first = books.firstOrNull()?.sourceIndex
        if (coverBookIndex == null || books.none { it.sourceIndex == coverBookIndex }) coverBookIndex = first
        if (metadataBookIndex == null || books.none { it.sourceIndex == metadataBookIndex }) metadataBookIndex = first
        if (stylesheetBookIndex == null || books.none { it.sourceIndex == stylesheetBookIndex }) stylesheetBookIndex = first
        if (baseChaptersBookIndex == null || books.none { it.sourceIndex == baseChaptersBookIndex }) baseChaptersBookIndex = first
        chapterOverrides = emptyMap()
    }

    fun buildMergedEpub(context: Context, output: OutputStream, onFinished: (Boolean) -> Unit) {
        val cover = coverBookIndex
        val meta = metadataBookIndex
        val style = stylesheetBookIndex
        val base = baseChaptersBookIndex
        if (cover == null || meta == null || style == null || base == null) {
            buildState = BuildState.Error("Pick a source for every option first.")
            onFinished(false)
            return
        }
        buildState = BuildState.Building
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    EpubBuilder.build(
                        books = books,
                        selection = MergeSelection(
                            coverBookIndex = cover,
                            metadataBookIndex = meta,
                            stylesheetBookIndex = style,
                            baseChaptersBookIndex = base,
                            chapterOverrides = chapterOverrides
                        ),
                        out = output
                    )
                }
                buildState = BuildState.Done
                onFinished(true)
            } catch (e: Exception) {
                buildState = BuildState.Error(e.message ?: "Something went wrong while building the epub.")
                onFinished(false)
            }
        }
    }

    fun resetBuildState() {
        buildState = BuildState.Idle
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name?.removeSuffix(".epub")
    }
}
