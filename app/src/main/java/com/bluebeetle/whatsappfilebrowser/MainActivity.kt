package com.bluebeetle.whatsappfilebrowser

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

data class MediaItem(val uri: Uri, val name: String, val mime: String, val size: Long, val modified: Long)
enum class Section { RECENT, PHOTOS, DOCUMENTS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { BrowserApp() } }
    }
}

@Composable
fun BrowserApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("storage", Activity.MODE_PRIVATE) }
    var rootUri by remember { mutableStateOf(prefs.getString("root", null)?.let(Uri::parse)) }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var section by remember { mutableStateOf(Section.RECENT) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        val root = rootUri ?: return
        loading = true
        Thread {
            val found = scanFolder(context, root)
            (context as Activity).runOnUiThread { items = found; loading = false }
        }.start()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.edit().putString("root", uri.toString()).apply()
            rootUri = uri
        }
    }

    LaunchedEffect(rootUri) { if (rootUri != null) refresh() }

    if (rootUri == null) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("WhatsApp Files", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Choose your WhatsApp Media folder once. The app will remember it.")
            Spacer(Modifier.height(24.dp))
            Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("Choose WhatsApp Folder") }
            Spacer(Modifier.height(12.dp))
            Text("Usually: Android / media / com.whatsapp / WhatsApp / Media", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    val visible = items.filter {
        (section == Section.RECENT || section == Section.PHOTOS && it.mime.startsWith("image/") ||
                section == Section.DOCUMENTS && !it.mime.startsWith("image/")) &&
                it.name.contains(query, ignoreCase = true)
    }.sortedByDescending { it.modified }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("WhatsApp Files", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { picker.launch(rootUri) }) { Text("Change folder") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionButton("Recent", Section.RECENT, section, Modifier.weight(1f)) { section = it }
            SectionButton("Photos", Section.PHOTOS, section, Modifier.weight(1f)) { section = it }
            SectionButton("Documents", Section.DOCUMENTS, section, Modifier.weight(1f)) { section = it }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(query, { query = it }, label = { Text("Search files") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.uri.toString() }) { item -> FileRow(item, onRefresh = { refresh() }) }
        }
    }
}

@Composable
fun SectionButton(label: String, value: Section, current: Section, modifier: Modifier, select: (Section) -> Unit) {
    if (value == current) Button({ select(value) }, modifier) { Text(label) }
    else OutlinedButton({ select(value) }, modifier) { Text(label) }
}

@Composable
fun FileRow(item: MediaItem, onRefresh: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.mime.startsWith("image/")) {
                AsyncImage(model = item.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
            } else Icon(Icons.Default.Description, null, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.modified)), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { openFile(context, item) }) { Text("Open") }
                    TextButton(onClick = { shareFile(context, item) }) { Text("Share") }
                    TextButton(onClick = {
                        if (saveToDownloads(context, item)) Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(context, "Could not save file", Toast.LENGTH_SHORT).show()
                    }) { Text("Save") }
                }
            }
        }
    }
}

fun scanFolder(context: android.content.Context, rootUri: Uri): List<MediaItem> {
    val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
    val result = mutableListOf<MediaItem>()
    fun walk(dir: DocumentFile) {
        dir.listFiles().forEach { f ->
            if (f.isDirectory) walk(f)
            else {
                val mime = f.type ?: context.contentResolver.getType(f.uri) ?: "application/octet-stream"
                result += MediaItem(f.uri, f.name ?: "Unnamed file", mime, f.length(), f.lastModified())
            }
        }
    }
    walk(root)
    return result
}

fun openFile(context: android.content.Context, item: MediaItem) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    } catch (_: Exception) { Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show() }
}

fun shareFile(context: android.content.Context, item: MediaItem) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = item.mime; putExtra(Intent.EXTRA_STREAM, item.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Share file"))
}

fun saveToDownloads(context: android.content.Context, item: MediaItem): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, item.name)
        put(MediaStore.Downloads.MIME_TYPE, item.mime)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val target = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    context.contentResolver.openInputStream(item.uri).use { input ->
        context.contentResolver.openOutputStream(target).use { output -> input?.copyTo(output!!) }
    }
    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
    context.contentResolver.update(target, values, null, null)
    true
} catch (_: Exception) { false }
