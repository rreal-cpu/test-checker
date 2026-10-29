@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.testchecker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- Settings

object Prefs {
    const val DEFAULT_MODEL = "claude-sonnet-5-5"
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun apiKey(c: Context): String = sp(c).getString("api_key", "") ?: ""
    fun model(c: Context): String = sp(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun save(c: Context, key: String, model: String) {
        sp(c).edit().putString("api_key", key.trim()).putString("model", model.trim().ifBlank { DEFAULT_MODEL }).apply()
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.apiKey(ctx)) }
    var model by remember { mutableStateOf(Prefs.model(ctx)) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("AI checking sends photos of the papers to the Anthropic API. Get a key at console.anthropic.com. It is stored only on this phone, and usage is billed to that account.")
        OutlinedTextField(key, { key = it; saved = false }, label = { Text("Anthropic API key") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it; saved = false }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Student names and handwriting appear in the photos. Check your school's rules on student data before using it on real papers.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Prefs.save(ctx, key, model); saved = true }) { Text("Save") }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        if (saved) Text("Saved.")
    }
}

// ---------------------------------------------------------------- Items editor

@Composable
fun ItemsScreen(exam: Exam, onChange: (Exam) -> Unit, onImportKey: () -> Unit, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<Item?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var quickAdd by remember { mutableStateOf(false) }

    fun renumber(list: List<Item>) = list.mapIndexed { i, x -> x.copy(number = i + 1) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Items – ${exam.title}", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { editing = Item(number = exam.items.size + 1); isNew = true }) { Text("Add item") }
            OutlinedButton(onClick = { quickAdd = true }) { Text("Paste MC key") }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        OutlinedButton(onClick = onImportKey, Modifier.fillMaxWidth()) { Text("Import key from photo (AI)") }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(exam.items, key = { it.number }) { item ->
                Card(Modifier.fillMaxWidth().clickable { editing = item; isNew = false }) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("${item.number}. ${item.type.label}", style = MaterialTheme.typography.titleSmall)
                            Text(item.answer.take(60).ifBlank { "(no key)" }, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${fmt(item.points)} pt")
                    }
                }
            }
        }
    }

    editing?.let { cur ->
        ItemDialog(
            item = cur, isNew = isNew, choices = exam.choices,
            onSave = { saved ->
                val list = if (isNew) exam.items + saved else exam.items.map { if (it.number == saved.number) saved else it }
                onChange(exam.copy(items = list)); editing = null
            },
            onDelete = { onChange(exam.copy(items = renumber(exam.items.filter { it.number != cur.number }))); editing = null },
            onDismiss = { editing = null }
        )
    }
    if (quickAdd) QuickAddDialog(
        choices = exam.choices,
        onAdd = { letters ->
            val added = letters.map { Item(number = 0, type = QType.MULTIPLE_CHOICE, answer = it.toString()) }
            onChange(exam.copy(items = renumber(exam.items + added))); quickAdd = false
        },
        onDismiss = { quickAdd = false }
    )
}

@Composable
fun QuickAddDialog(choices: Int, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val letters = "ABCDE".take(choices)
    val parsed = text.uppercase().filter { it in letters }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add multiple-choice items") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Key letters in order") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("${parsed.length} items will be added at the end.")
            }
        },
        confirmButton = { TextButton(enabled = parsed.isNotEmpty(), onClick = { onAdd(parsed) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ItemDialog(item: Item, isNew: Boolean, choices: Int, onSave: (Item) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(item.type) }
    var question by remember { mutableStateOf(item.question) }
    var answer by remember { mutableStateOf(item.answer) }
    var points by remember { mutableStateOf(fmt(item.points)) }
    var rubric by remember { mutableStateOf(item.rubric) }
    var menu by remember { mutableStateOf(false) }

    val pts = points.toDoubleOrNull()
    val letters = "ABCDE".take(choices)
    val answerOk = if (type == QType.MULTIPLE_CHOICE) answer.trim().uppercase().singleOrNull()?.let { it in letters } == true
                   else answer.isNotBlank()
    val answerLabel = when (type) {
        QType.MULTIPLE_CHOICE -> "Correct letter (A–${letters.last()})"
        QType.IDENTIFICATION -> "Correct answer (use / between accepted variants)"
        QType.NUMERIC -> "Correct value with unit"
        QType.PROBLEM_SOLVING -> "Final answer and/or worked solution"
        QType.ESSAY -> "Key points a good answer contains"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Item ${item.number}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(type.label + "  ▾") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        QType.values().forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { type = t; menu = false }) }
                    }
                }
                if (type != QType.MULTIPLE_CHOICE)
                    OutlinedTextField(question, { question = it }, label = { Text("Question (optional, helps the AI)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(answer, { answer = it }, label = { Text(answerLabel) }, modifier = Modifier.fillMaxWidth(),
                    minLines = if (type == QType.PROBLEM_SOLVING || type == QType.ESSAY) 3 else 1)
                OutlinedTextField(points, { points = it }, label = { Text("Points") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                if (type != QType.MULTIPLE_CHOICE) {
                    OutlinedTextField(rubric, { rubric = it }, label = { Text("Rubric / partial credit rules") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Text("Example: 1 pt setup, 2 pts correct substitution, 1 pt final answer, 1 pt units. Numeric tolerance can go here too.", style = MaterialTheme.typography.bodySmall)
                }
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete this item") }
            }
        },
        confirmButton = {
            TextButton(enabled = pts != null && pts > 0 && answerOk, onClick = {
                onSave(item.copy(
                    type = type, question = question.trim(),
                    answer = if (type == QType.MULTIPLE_CHOICE) answer.trim().uppercase() else answer.trim(),
                    points = pts!!, rubric = rubric.trim()
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- AI scan

@Composable
fun AiScanScreen(exam: Exam, store: ExamStore, onDone: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCam by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    val pages = remember { mutableStateListOf<Bitmap>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var graded by remember { mutableStateOf<List<ItemResult>?>(null) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { u -> runCatching { loadBitmap(ctx, u) }.onSuccess { pages.add(it) } }
    }
    LaunchedEffect(Unit) { if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA) }

    val g = graded
    if (g != null) {
        AiResultView(exam, g, store, onNext = { graded = null; pages.clear() }, onDone = onDone)
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
            if (pages.isEmpty()) "Photograph each page of one student's paper (whole page, flat, good light)."
            else "${pages.size} page(s) ready. Add the next page or tap Check.",
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
                if (Prefs.apiKey(ctx).isBlank()) { error = "Add your API key in Settings first." }
                else {
                    busy = true; error = null
                    val snapshot = pages.toList()
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { AiGrader.grade(Prefs.apiKey(ctx), Prefs.model(ctx), exam, snapshot) } }
                        busy = false
                        r.onSuccess { graded = AiGrader.toResults(exam, it) }
                            .onFailure { error = it.message ?: "Grading failed." }
                    }
                }
            }) { Text("Check paper") }
            OutlinedButton(onClick = onSettings) { Text("Settings") }
            OutlinedButton(onClick = onDone) { Text("Close") }
        }
    }
}

@Composable
fun AiResultView(exam: Exam, results: List<ItemResult>, store: ExamStore, onNext: () -> Unit, onDone: () -> Unit) {
    var overrides by remember { mutableStateOf(mapOf<Int, Double>()) }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ItemResult?>(null) }

    fun earned(r: ItemResult) = overrides[r.item.number] ?: r.earned
    fun flagged(r: ItemResult) = r.confidence < 0.7 && r.item.number !in overrides
    val score = results.sumOf { earned(it) }
    val total = results.sumOf { it.item.points }
    val review = results.count { flagged(it) }
    fun save() = store.addScore(exam.id, ScoreRecord(
        name.ifBlank { "Student" }, score, total, System.currentTimeMillis(),
        items = results.map { ItemRecord(it.item.number, it.item.type, it.given, earned(it), it.item.points, it.feedback) }
    ))

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Score: ${fmt(score)} / ${fmt(total)}", style = MaterialTheme.typography.headlineMedium) }
        if (review > 0) item { Text("$review item(s) the AI was unsure about are highlighted. Please check them against the paper.", color = MaterialTheme.colorScheme.tertiary) }
        item { OutlinedTextField(name, { name = it }, label = { Text("Student name") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(); onNext() }) { Text("Save & next paper") }
                OutlinedButton(onClick = { save(); onDone() }) { Text("Save & finish") }
            }
        }
        item { Text("Tap any item to change its points.", style = MaterialTheme.typography.bodySmall) }
        items(results, key = { it.item.number }) { r ->
            val shown = when (r.given) { "" -> "(blank)"; "*" -> "(multiple marks)"; else -> r.given }
            Card(
                Modifier.fillMaxWidth().clickable { editing = r },
                colors = if (flagged(r)) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer) else CardDefaults.cardColors()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${r.item.number}. ${r.item.type.label}", style = MaterialTheme.typography.titleSmall)
                        Text("${fmt(earned(r))} / ${fmt(r.item.points)}")
                    }
                    Text("Student: $shown")
                    if (earned(r) < r.item.points) Text("Key: ${r.item.answer.take(80)}", style = MaterialTheme.typography.bodySmall)
                    if (r.feedback.isNotBlank()) Text(r.feedback, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    editing?.let { r ->
        var text by remember(r.item.number) { mutableStateOf(fmt(earned(r))) }
        val v = text.toDoubleOrNull()
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Item ${r.item.number} points (max ${fmt(r.item.points)})") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) },
            confirmButton = {
                TextButton(enabled = v != null, onClick = {
                    overrides = overrides + (r.item.number to v!!.coerceIn(0.0, r.item.points)); editing = null
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
}
