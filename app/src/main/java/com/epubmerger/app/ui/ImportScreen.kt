package com.epubmerger.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun ImportScreen(viewModel: AppViewModel, onCompare: () -> Unit) {
    val context = LocalContext.current
    val books = viewModel.books

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.addBook(context, it) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Epub Merger") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickerLauncher.launch(arrayOf("application/epub+zip")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add EPUB") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                "Import 2 or 3 copies of the same book (different editions/sources), " +
                    "then pick which one's cover, formatting, and chapter breaks to keep.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))

            if (viewModel.loadState is LoadState.Loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            val err = viewModel.loadState
            if (err is LoadState.Error) {
                Text(err.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No books imported yet. Tap \"Add EPUB\" to get started.")
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(books, key = { it.sourceIndex }) { book ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(bookLabel(book.sourceIndex) + ": " + book.title, style = MaterialTheme.typography.titleMedium)
                                    Text(book.author, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "${book.chapterCount} chapters · ${book.wordCount} words" +
                                            if (book.hasStylesheet) " · has stylesheet" else " · no stylesheet",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(onClick = { viewModel.removeBook(book.sourceIndex) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onCompare,
                    enabled = books.size >= 2,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (books.size < 2) "Add at least 2 books to compare" else "Compare Books")
                }
            }
        }
    }
}

fun bookLabel(sourceIndex: Int): String = "Book ${('A' + sourceIndex)}"
