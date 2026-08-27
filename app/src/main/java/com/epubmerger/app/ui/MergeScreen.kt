package com.epubmerger.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun MergeScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val books = viewModel.books
    var showSavedMessage by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                viewModel.buildMergedEpub(context, out) { success ->
                    showSavedMessage = success
                }
            }
        }
    }

    val defaultFileName = remember(books) {
        val title = books.firstOrNull()?.title ?: "merged-book"
        title.replace(Regex("[^a-zA-Z0-9-_ ]"), "").trim().ifBlank { "merged-book" } + "-merged.epub"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merge") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    SourcePicker(
                        label = "Cover image from",
                        books = books,
                        selected = viewModel.coverBookIndex,
                        onSelect = { viewModel.coverBookIndex = it }
                    )
                }
                item {
                    SourcePicker(
                        label = "Title & author from",
                        books = books,
                        selected = viewModel.metadataBookIndex,
                        onSelect = { viewModel.metadataBookIndex = it }
                    )
                }
                item {
                    SourcePicker(
                        label = "Stylesheet / formatting from",
                        books = books,
                        selected = viewModel.stylesheetBookIndex,
                        onSelect = { viewModel.stylesheetBookIndex = it }
                    )
                }
                item {
                    SourcePicker(
                        label = "Chapter breaks & body text (base) from",
                        books = books,
                        selected = viewModel.baseChaptersBookIndex,
                        onSelect = { viewModel.baseChaptersBookIndex = it }
                    )
                }

                if (viewModel.chapterCountsMatch()) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        Text("Advanced: per-chapter overrides", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "All imported books have the same chapter count, so you can swap in " +
                                "an individual chapter's text from a different book.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val baseIndex = viewModel.baseChaptersBookIndex
                    val baseBook = books.firstOrNull { it.sourceIndex == baseIndex }
                    if (baseBook != null) {
                        items(baseBook.chapters.size) { chapterIndex ->
                            ChapterOverrideRow(
                                chapterIndex = chapterIndex,
                                chapterTitle = baseBook.chapters[chapterIndex].title,
                                books = books,
                                baseIndex = baseIndex!!,
                                selected = viewModel.chapterOverrides[chapterIndex] ?: baseIndex,
                                onSelect = { newIndex ->
                                    if (newIndex == baseIndex) viewModel.clearChapterOverride(chapterIndex)
                                    else viewModel.setChapterOverride(chapterIndex, newIndex)
                                }
                            )
                        }
                    }
                } else if (books.size >= 2) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Per-chapter mixing is only available when every imported book has the " +
                                "same chapter count. Right now they don't match, so the whole body text " +
                                "comes from a single \"base\" book above.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when (val state = viewModel.buildState) {
                is BuildState.Building -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is BuildState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                is BuildState.Done -> if (showSavedMessage) {
                    Text("Saved! Your merged epub is ready.", color = MaterialTheme.colorScheme.primary)
                }
                else -> {}
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.resetBuildState()
                    showSavedMessage = false
                    saveLauncher.launch(defaultFileName)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Build & Save Merged EPUB")
            }
        }
    }
}

@Composable
private fun SourcePicker(
    label: String,
    books: List<com.epubmerger.app.model.EpubBook>,
    selected: Int?,
    onSelect: (Int) -> Unit
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            books.forEach { book ->
                FilterChip(
                    selected = selected == book.sourceIndex,
                    onClick = { onSelect(book.sourceIndex) },
                    label = { Text(bookLabel(book.sourceIndex)) }
                )
            }
        }
    }
}

@Composable
private fun ChapterOverrideRow(
    chapterIndex: Int,
    chapterTitle: String,
    books: List<com.epubmerger.app.model.EpubBook>,
    baseIndex: Int,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            "Ch. ${chapterIndex + 1}: $chapterTitle",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        books.forEach { book ->
            FilterChip(
                selected = selected == book.sourceIndex,
                onClick = { onSelect(book.sourceIndex) },
                label = { Text(bookLabel(book.sourceIndex)) },
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
