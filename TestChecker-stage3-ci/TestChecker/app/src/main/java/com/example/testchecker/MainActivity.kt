@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.testchecker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BubbleScanner.init()
        val store = ExamStore(filesDir)
        setContent { MaterialTheme { App(store) } }
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Editor(val examId: String?) : Screen
    data class Detail(val examId: String) : Screen
    data class Items(val examId: String) : Screen
    data class Scan(val examId: String) : Screen
    data class AiScan(val examId: String) : Screen
    data class Papers(val examId: String) : Screen
    data class KeyImport(val examId: String) : Screen
}

@Composable
fun App(store: ExamStore) {
    val exams = remember { mutableStateListOf<Exam>().apply { addAll(store.loadExams()) } }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    fun persist() = store.saveExams(exams.toList())
    fun find(id: String?) = exams.firstOrNull { it.id == id }

    BackHandler(screen != Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Scan -> Screen.Detail(s.examId)
            is Screen.AiScan -> Screen.Detail(s.examId)
            is Screen.Papers -> Screen.Detail(s.examId)
            is Screen.KeyImport -> Screen.Items(s.examId)
            is Screen.Items -> Screen.Detail(s.examId)
            is Screen.Editor -> s.examId?.let { Screen.Detail(it) } ?: Screen.Home
            else -> Screen.Home
        }
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(exams, onNew = { screen = Screen.Editor(null) }, onOpen = { screen = Screen.Detail(it.id) }, onSettings = { screen = Screen.Settings })
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
        is Screen.Editor -> EditorScreen(
            existing = find(s.examId),
            onSave = { e ->
                val i = exams.indexOfFirst { it.id == e.id }
                if (i >= 0) exams[i] = e else exams.add(e)
                persist(); screen = Screen.Detail(e.id)
            },
            onCancel = { screen = s.examId?.let { Screen.Detail(it) } ?: Screen.Home }
        )
        is Screen.Detail -> find(s.examId)?.let { e ->
            DetailScreen(
                exam = e, store = store,
                onScan = { screen = Screen.Scan(e.id) },
                onAiScan = { screen = Screen.AiScan(e.id) },
                onItems = { screen = Screen.Items(e.id) },
                onPapers = { screen = Screen.Papers(e.id) },
                onEdit = { screen = Screen.Editor(e.id) },
                onDelete = { exams.remove(e); store.deleteScores(e.id); persist(); screen = Screen.Home },
                onBack = { screen = Screen.Home }
            )
        } ?: run { screen = Screen.Home }
        is Screen.Items -> find(s.examId)?.let { e ->
            ItemsScreen(
                exam = e,
                onImportKey = { screen = Screen.KeyImport(e.id) },
                onChange = { ne ->
                    val i = exams.indexOfFirst { it.id == ne.id }
                    if (i >= 0) { exams[i] = ne; persist() }
                },
                onBack = { screen = Screen.Detail(e.id) }
            )
        } ?: run { screen = Screen.Home }
        is Screen.Papers -> find(s.examId)?.let { e ->
            PapersScreen(e, store, onBack = { screen = Screen.Detail(e.id) })
        } ?: run { screen = Screen.Home }
        is Screen.KeyImport -> find(s.examId)?.let { e ->
            KeyImportScreen(
                exam = e,
                onApply = { found, replace ->
                    val merged = (if (replace) found else e.items + found).mapIndexed { n, x -> x.copy(number = n + 1) }
                    val i = exams.indexOfFirst { it.id == e.id }
                    if (i >= 0) { exams[i] = e.copy(items = merged); persist() }
                    screen = Screen.Items(e.id)
                },
                onBack = { screen = Screen.Items(e.id) },
                onSettings = { screen = Screen.Settings }
            )
        } ?: run { screen = Screen.Home }
        is Screen.Scan -> find(s.examId)?.let { e ->
            ScanScreen(e, store, onDone = { screen = Screen.Detail(e.id) })
        } ?: run { screen = Screen.Home }
        is Screen.AiScan -> find(s.examId)?.let { e ->
            AiScanScreen(e, store, onDone = { screen = Screen.Detail(e.id) }, onSettings = { screen = Screen.Settings })
        } ?: run { screen = Screen.Home }
    }
}

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(exams: List<Exam>, onNew: () -> Unit, onOpen: (Exam) -> Unit, onSettings: () -> Unit) {
    Scaffold(
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onNew) { Text("New exam") } }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Test Checker", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onSettings) { Text("Settings") }
            }
            Spacer(Modifier.height(12.dp))
            if (exams.isEmpty()) {
                Text("No exams yet. Tap “New exam” to enter an answer key.")
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(exams, key = { it.id }) { e ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(e) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(e.title, style = MaterialTheme.typography.titleMedium)
                            Text("${e.items.size} items")
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Editor

@Composable
fun EditorScreen(existing: Exam?, onSave: (Exam) -> Unit, onCancel: () -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var choices by remember { mutableStateOf(existing?.choices ?: 4) }
    var key by remember { mutableStateOf("") }
    val letters = "ABCDE".take(choices)
    val parsed = key.uppercase().filter { it in letters }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (existing == null) "New exam" else "Edit exam", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(title, { title = it }, label = { Text("Exam title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Choices per multiple-choice item")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (2..5).forEach { n -> FilterChip(selected = choices == n, onClick = { choices = n }, label = { Text("$n  (A–${'A' + n - 1})") }) }
        }
        if (existing == null) {
            OutlinedTextField(
                key, { key = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Multiple-choice key (optional, e.g. ABCDDACB…)") }, minLines = 3
            )
            Text("${parsed.length} MC items read. You can add problem solving, essay and other items afterwards under “Edit items”.", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = title.isNotBlank() && parsed.length <= SheetLayout.MAX_ITEMS,
                onClick = {
                    if (existing != null) onSave(existing.copy(title = title.trim(), choices = choices))
                    else onSave(Exam(
                        title = title.trim(), choices = choices,
                        items = parsed.mapIndexed { i, c -> Item(i + 1, QType.MULTIPLE_CHOICE, answer = c.toString()) }
                    ))
                }
            ) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

// ---------------------------------------------------------------- Detail

@Composable
fun DetailScreen(
    exam: Exam, store: ExamStore,
    onScan: () -> Unit, onAiScan: () -> Unit, onItems: () -> Unit, onPapers: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val paperCount = remember { store.loadScores(exam.id).size }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasMc = exam.items.any { it.type == QType.MULTIPLE_CHOICE }
    val summary = exam.items.groupingBy { it.type }.eachCount().entries.joinToString(" · ") { "${it.value} ${it.key.label.lowercase()}" }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(exam.title, style = MaterialTheme.typography.headlineSmall)
        Text(if (exam.items.isEmpty()) "No items yet" else summary)
        Button(onClick = onItems, Modifier.fillMaxWidth()) { Text("Edit items & answer key") }
        Button(onClick = onAiScan, Modifier.fillMaxWidth(), enabled = exam.items.isNotEmpty()) { Text("Check questionnaire / written work (AI)") }
        if (hasMc) {
            OutlinedButton(onClick = onScan, Modifier.fillMaxWidth()) { Text("Scan bubble answer sheet (offline)") }
            OutlinedButton(onClick = {
                val f = File(ctx.cacheDir, "sheets/${safe(exam.title)}-answer-sheet.pdf").also { it.parentFile!!.mkdirs() }
                SheetPdf.create(exam, f); shareFile(ctx, f, "application/pdf")
            }, Modifier.fillMaxWidth()) { Text("Make answer sheet (PDF)") }
        }
        OutlinedButton(onClick = onPapers, Modifier.fillMaxWidth()) { Text("Papers, results & analysis ($paperCount)") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text("Rename") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this exam?") },
        text = { Text("The answer key and all saved papers for it will be removed.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- Scan

@Composable
fun ScanScreen(exam: Exam, store: ExamStore, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCam by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ScanResult.Ok?>(null) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }

    fun process(bmp: Bitmap) {
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { BubbleScanner.scan(bmp, exam) } }
            busy = false
            r.onSuccess { if (it is ScanResult.Ok) result = it else if (it is ScanResult.Fail) error = it.reason }
                .onFailure { error = "Scan failed: ${it.message}" }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching { loadBitmap(ctx, uri) }.onSuccess { process(it) }.onFailure { error = "Could not open that image." }
    }
    LaunchedEffect(Unit) { if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA) }

    val r = result
    if (r != null) {
        ResultView(exam, r, store, onNext = { result = null }, onDone = onDone)
        return
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (hasCam) CameraPreview(capture, Modifier.fillMaxSize())
            else Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            if (busy) CircularProgressIndicator()
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        Text("Fit all 4 black corner squares in view.", modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = hasCam && !busy, onClick = {
                capture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val raw = image.toBitmap(); val rot = image.imageInfo.rotationDegrees; image.close()
                        val bmp = if (rot != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else raw
                        process(bmp)
                    }
                    override fun onError(exception: ImageCaptureException) { error = exception.message }
                })
            }) { Text("Capture") }
            OutlinedButton(enabled = !busy, onClick = { picker.launch("image/*") }) { Text("From gallery") }
            OutlinedButton(onClick = onDone) { Text("Close") }
        }
    }
}

@Composable
fun CameraPreview(capture: ImageCapture, modifier: Modifier) {
    val activity = LocalContext.current as ComponentActivity
    AndroidView(modifier = modifier, factory = { c ->
        val view = PreviewView(c)
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        }, ContextCompat.getMainExecutor(c))
        view
    })
}

@Composable
fun ResultView(exam: Exam, ok: ScanResult.Ok, store: ExamStore, onNext: () -> Unit, onDone: () -> Unit) {
    var answers by remember { mutableStateOf(ok.answers) }
    var name by remember { mutableStateOf("") }
    val graded = Grader.grade(exam, answers).filter { it.status != Status.PENDING }
    val score = graded.sumOf { it.earned }
    val total = graded.sumOf { it.item.points }
    val options = listOf("") + "ABCDE".take(exam.choices).map { it.toString() }

    fun save() = store.addScore(exam.id, ScoreRecord(
        name.ifBlank { "Student" }, score, total, System.currentTimeMillis(),
        items = graded.map { ItemRecord(it.item.number, it.item.type, it.given, it.earned, it.item.points) }
    ))

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Score: ${fmt(score)} / ${fmt(total)}", style = MaterialTheme.typography.headlineMedium) }
        item { OutlinedTextField(name, { name = it }, label = { Text("Student name") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(); onNext() }) { Text("Save & scan next") }
                OutlinedButton(onClick = { save(); onDone() }) { Text("Save & finish") }
            }
        }
        item { Text("Tap an item to change the detected answer if the scan misread it.", style = MaterialTheme.typography.bodySmall) }
        items(graded, key = { it.item.number }) { g ->
            val shown = when (g.given) { "" -> "blank"; "*" -> "multiple marks"; else -> g.given }
            Row(
                Modifier.fillMaxWidth().clickable {
                    val cur = answers[g.item.number] ?: ""
                    answers = answers + (g.item.number to options[(options.indexOf(cur) + 1) % options.size])
                }.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${g.item.number}.  $shown")
                Text(if (g.status == Status.CORRECT) "✓" else "✗  key: ${g.item.answer}")
            }
        }
        item { Image(ok.preview.asImageBitmap(), contentDescription = "Detected marks", modifier = Modifier.fillMaxWidth()) }
    }
}

// ---------------------------------------------------------------- helpers

fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(d)
fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

fun loadBitmap(ctx: Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

fun shareFile(ctx: Context, f: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Share"))
}
