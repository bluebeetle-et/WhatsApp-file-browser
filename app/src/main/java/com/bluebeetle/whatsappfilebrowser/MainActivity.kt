package com.bluebeetle.whatsappfilebrowser

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
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

data class MediaItem(val uri: Uri, val name: String, val mime: String, val size: Long, val added: Long)
enum class Section { DOWNLOADS, WHATSAPP, OTHERS }

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
    var whatsappUri by remember { mutableStateOf(prefs.getString("root", null)?.let(Uri::parse)) }
    var otherUri by remember { mutableStateOf(prefs.getString("other_root", null)?.let(Uri::parse)) }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var section by remember { mutableStateOf(Section.DOWNLOADS) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var mediaPermissionTick by remember { mutableIntStateOf(0) }

    fun refresh() {
        loading = true
        Thread {
            val found = when (section) {
                Section.WHATSAPP -> whatsappUri?.let { scanFolder(context, it) } ?: emptyList()
                Section.DOWNLOADS -> scanDownloads(context)
                Section.OTHERS -> otherUri?.let { scanFolder(context, it) } ?: emptyList()
            }
            (context as Activity).runOnUiThread { items = found; loading = false }
        }.start()
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mediaPermissionTick++
    }
    val whatsappPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTree(context, uri)
            prefs.edit().putString("root", uri.toString()).apply()
            whatsappUri = uri
            section = Section.WHATSAPP
        }
    }
    val otherPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTree(context, uri)
            prefs.edit().putString("other_root", uri.toString()).apply()
            otherUri = uri
            section = Section.OTHERS
        }
    }

    LaunchedEffect(section, whatsappUri, otherUri, mediaPermissionTick) { refresh() }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val permissions = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
                mediaPermissionLauncher.launch(permissions)
            }
        }
    }

    val visible = items.filter { it.name.contains(query, true) }.sortedByDescending { it.added }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("asimFiles", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionButton("Downloads", Section.DOWNLOADS, section, Modifier.weight(1f)) { section = it }
            SectionButton("WhatsApp", Section.WHATSAPP, section, Modifier.weight(1f)) { section = it }
            SectionButton("Others", Section.OTHERS, section, Modifier.weight(1f)) { section = it }
        }
        Spacer(Modifier.height(10.dp))
        when {
            section == Section.WHATSAPP && whatsappUri == null ->
                Button({ whatsappPicker.launch(expectedWhatsAppMediaUri()) }, Modifier.fillMaxWidth()) { Text("Allow WhatsApp Media Access") }
            section == Section.OTHERS && otherUri == null ->
                Button({ otherPicker.launch(null) }, Modifier.fillMaxWidth()) { Text("Choose another folder") }
            section == Section.WHATSAPP ->
                TextButton({ whatsappPicker.launch(whatsappUri) }) { Text("Change WhatsApp folder") }
            section == Section.OTHERS ->
                TextButton({ otherPicker.launch(otherUri) }) { Text("Change folder") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search files") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.uri.toString() }) { item -> FileRow(item) }
        }
    }
}

@Composable
fun SectionButton(label: String, value: Section, current: Section, modifier: Modifier, select: (Section) -> Unit) {
    if (value == current) Button({ select(value) }, modifier) { Text(label) }
    else OutlinedButton({ select(value) }, modifier) { Text(label) }
}

@Composable
fun FileRow(item: MediaItem) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.mime.startsWith("image/")) AsyncImage(item.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
            else Icon(Icons.Default.Description, null, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.added)), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton({ openFile(context, item) }) { Text("Open") }
                    TextButton({ shareFile(context, item) }) { Text("Share") }
                    TextButton({
                        if (saveToDownloads(context, item)) Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                    }) { Text("Save") }
                }
            }
        }
    }
}

fun persistTree(context: android.content.Context, uri: Uri) {
    try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
}

fun scanDownloads(context: android.content.Context): List<MediaItem> {
    val result = mutableListOf<MediaItem>()
    val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_ADDED, MediaStore.Downloads.DATE_MODIFIED)
    context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, null, null, MediaStore.Downloads.DATE_ADDED + " DESC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
        val name = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
        val mime = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
        val size = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
        val added = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
        val modified = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
        while (c.moveToNext()) {
            val n = c.getString(name) ?: "Unnamed file"
            if (isUsefulFile(n)) result += MediaItem(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(id)), n, c.getString(mime) ?: mimeFromName(n), c.getLong(size), 1000L * (c.getLong(added).takeIf { it > 0 } ?: c.getLong(modified)))
        }
    }
    return result
}

fun scanFolder(context: android.content.Context, rootUri: Uri): List<MediaItem> {
    val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
    val result = mutableListOf<MediaItem>()
    fun walk(dir: DocumentFile) {
        dir.listFiles().forEach { f ->
            if (f.isDirectory) walk(f) else {
                val n = f.name ?: "Unnamed file"
                if (isUsefulFile(n)) result += MediaItem(f.uri, n, f.type ?: context.contentResolver.getType(f.uri) ?: mimeFromName(n), f.length(), f.lastModified())
            }
        }
    }
    walk(root)
    return result
}

fun openFile(context: android.content.Context, item: MediaItem) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(item.uri, item.mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
    catch (_: Exception) { Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show() }
}
fun shareFile(context: android.content.Context, item: MediaItem) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = item.mime; putExtra(Intent.EXTRA_STREAM, item.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share file"))
}
fun saveToDownloads(context: android.content.Context, item: MediaItem): Boolean {
    if (item.uri.authority == MediaStore.AUTHORITY) return true
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, item.name)
            put(MediaStore.Downloads.MIME_TYPE, item.mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        context.contentResolver.openInputStream(item.uri).use { input ->
            context.contentResolver.openOutputStream(target).use { output ->
                if (input != null && output != null) input.copyTo(output)
            }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        context.contentResolver.update(target, values, null, null)
        true
    } catch (_: Exception) {
        false
    }
}

fun expectedWhatsAppMediaUri(): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Android/media/com.whatsapp/WhatsApp/Media")
fun isUsefulFile(name: String): Boolean {
    val n = name.lowercase()
    return n != ".nomedia" && !n.startsWith(".") && !n.endsWith(".tmp") && !n.endsWith(".partial") && !n.endsWith(".download")
}
fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg","jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; "mp4" -> "video/mp4"; "3gp" -> "video/3gpp"; "opus","ogg" -> "audio/ogg"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "pdf" -> "application/pdf"; "doc" -> "application/msword"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; "xls" -> "application/vnd.ms-excel"; "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; "ppt" -> "application/vnd.ms-powerpoint"; "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"; "txt" -> "text/plain"; else -> "application/octet-stream"
}
