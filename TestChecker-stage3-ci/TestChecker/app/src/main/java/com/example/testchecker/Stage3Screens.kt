@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.testchecker

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun csv(c: String) = "\"" + c.replace("\"", "\"\"") + "\""
private fun pctText(d: Double) = String.format(Locale.US, "%.1f%%", d)

// ---------------------------------------------------------------- Papers & analysis

@Composable
fun PapersScreen(exam: Exam, store: ExamStore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var records by remember { mutableStateOf(store.loadScores(exam.id)) }
    var open by remember { mutableStateOf<ScoreRecord?>(null) }
    val stats = Analysis.stats(records)
    val itemStats = remember(records) { Analysis.itemStats(records) }
    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    fun update(list: List<ScoreRecord>) { records = list; store.saveScores(exam.id, list) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Papers – ${exam.title}", style = MaterialTheme.typography.headlineSmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = records.isNotEmpty(), onClick = {
                    val header = listOf("Name", "Score", "Total", "Percent", "Date") + exam.items.map { "Q${it.number}" }
                    val rows = records.sortedBy { it.time }.map { r ->
                        listOf(r.name, fmt(r.score), fmt(r.total), String.format(Locale.US, "%.1f", Analysis.pct(r)), df.format(Date(r.time))) +
                            exam.items.map { q -> r.items.firstOrNull { it.number == q.number }?.let { fmt(it.earned) } ?: "" }
                    }
                    val f = File(ctx.cacheDir, "exports/${safe(exam.title)}-results.csv").also { it.parentFile!!.mkdirs() }
                    f.writeText((listOf(header) + rows).joinToString("\n") { row -> row.joinToString(",") { csv(it) } })
                    shareFile(ctx, f, "text/csv")
                }) { Text("Export CSV") }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
        if (stats != null) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${stats.n} paper(s)", style = MaterialTheme.typography.titleMedium)
                    Text("Mean ${pctText(stats.meanPct)} · Median ${pctText(stats.medianPct)}")
                    Text("Highest ${pctText(stats.highPct)} · Lowest ${pctText(stats.lowPct)}")
                }
            }
        }
        if (itemStats.isNotEmpty()) {
            item { Text("Item analysis (hardest first)", style = MaterialTheme.typography.titleMedium) }
            items(itemStats, key = { "stat${it.number}" }) { s ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("#${s.number} ${s.type.label}")
                    Text("avg ${pctText(s.avgPct)} · full ${pctText(s.fullPct)}")
                }
            }
        }
        item { Text("Checked papers", style = MaterialTheme.typography.titleMedium) }
        if (records.isEmpty()) item { Text("Nothing saved yet.") }
        items(records.sortedByDescending { it.time }, key = { it.time }) { r ->
            Row(Modifier.fillMaxWidth().clickable { open = r }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(r.name)
                Text("${fmt(r.score)} / ${fmt(r.total)}")
            }
        }
    }

    open?.let { rec ->
        PaperDialog(
            rec,
            onRename = { n -> update(records.map { if (it.time == rec.time) it.copy(name = n) else it }); open = null },
            onDelete = { update(records.filter { it.time != rec.time }); open = null },
            onDismiss = { open = null }
        )
    }
}

@Composable
fun PaperDialog(rec: ScoreRecord, onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(rec.name) }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${fmt(rec.score)} / ${fmt(rec.total)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Student name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (rec.items.isEmpty()) Text("No item detail was saved for this paper.", style = MaterialTheme.typography.bodySmall)
                rec.items.forEach { it ->
                    val shown = when (it.given) { "" -> "blank"; "*" -> "multiple marks"; else -> it.given.take(40) }
                    Text("${it.number}. ${shown}  —  ${fmt(it.earned)}/${fmt(it.max)}")
                    if (it.feedback.isNotBlank()) Text(it.feedback, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { confirm = true }) { Text("Delete this paper") }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onRename(name.trim()) }) { Text("Save name") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete this paper?") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- Import key from photo

@Composable
fun KeyImportScreen(exam: Exam, onApply: (List<Item>, Boolean) -> Unit, onBack: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCam by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    val pages = remember { mutableStateListOf<Bitmap>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<Item>?>(null) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { u -> runCatching { loadBitmap(ctx, u) }.onSuccess { pages.add(it) } }
    }
    LaunchedEffect(Unit) { if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA) }

    val foundItems = found
    if (foundItems != null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Found ${foundItems.size} item(s)", style = MaterialTheme.typography.headlineSmall)
            val missing = foundItems.count { it.answer.isBlank() }
            Text(
                "The AI copies what is on the page. Check the key afterwards" + if (missing > 0) " — $missing item(s) had no answer shown." else ".",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(foundItems, key = { it.number }) { it ->
                    Text("${it.number}. ${it.type.label}: ${it.answer.take(40).ifBlank { "(no answer)" }}  · ${fmt(it.points)} pt")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (exam.items.isNotEmpty()) Button(onClick = { onApply(foundItems, true) }) { Text("Replace items") }
                Button(onClick = { onApply(foundItems, false) }) { Text(if (exam.items.isEmpty()) "Use these" else "Add to end") }
                OutlinedButton(onClick = { found = null }) { Text("Discard") }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (hasCam) CameraPreview(capture, Modifier.fillMaxSize())
            else Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            if (busy) CircularProgressIndicator()
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        Text(
            if (pages.isEmpty()) "Photograph your answer key (or a paper with the correct answers filled in), page by page."
            else "${pages.size} page(s) ready.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = hasCam && !busy && pages.size < 8, onClick = {
                capture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val raw = image.toBitmap(); val rot = image.imageInfo.rotationDegrees; image.close()
                        pages.add(if (rot != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else raw)
                    }
                    override fun onError(exception: ImageCaptureException) { error = exception.message }
                })
            }) { Text("Capture page") }
            OutlinedButton(enabled = !busy, onClick = { picker.launch("image/*") }) { Text("Gallery") }
            OutlinedButton(enabled = pages.isNotEmpty() && !busy, onClick = { pages.removeAt(pages.lastIndex) }) { Text("Undo") }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = pages.isNotEmpty() && !busy, onClick = {
                if (Prefs.apiKey(ctx).isBlank()) error = "Add your API key in Settings first."
                else {
                    busy = true; error = null
                    val snapshot = pages.toList()
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { AiGrader.importKey(Prefs.apiKey(ctx), Prefs.model(ctx), snapshot) } }
                        busy = false
                        r.onSuccess { if (it.isEmpty()) error = "No items found on those pages." else found = it }
                            .onFailure { error = it.message ?: "Import failed." }
                    }
                }
            }) { Text("Read key") }
            OutlinedButton(onClick = onSettings) { Text("Settings") }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}
