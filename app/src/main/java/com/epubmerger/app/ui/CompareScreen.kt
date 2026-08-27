package com.epubmerger.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.epubmerger.app.model.EpubBook

@Composable
fun CompareScreen(viewModel: AppViewModel, onBack: () -> Unit, onProceed: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = onProceed,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text("Proceed to Merge") }
            }
        }
    ) { padding ->
        Row(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            viewModel.books.forEach { book ->
                BookCompareCard(book)
            }
        }
    }
}

@Composable
private fun BookCompareCard(book: EpubBook) {
    Card(modifier = Modifier.width(220.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(bookLabel(book.sourceIndex), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))

            val coverBytes = book.coverBytes
            if (coverBytes != null) {
                val bitmap = remember(book.sourceIndex) {
                    BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Cover for ${book.title}",
                        modifier = Modifier.fillMaxWidth().height(260.dp)
                    )
                } else {
                    NoCoverBox()
                }
            } else {
                NoCoverBox()
            }

            Spacer(Modifier.height(8.dp))
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Text(book.author, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            InfoRow("Chapters", book.chapterCount.toString())
            InfoRow("Words", book.wordCount.toString())
            InfoRow("Stylesheet", if (book.hasStylesheet) "Yes (${book.cssFiles.size})" else "None")
            InfoRow("Images", book.images.size.toString())
        }
    }
}

@Composable
private fun NoCoverBox() {
    Box(
        modifier = Modifier.fillMaxWidth().height(260.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("No cover found", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
