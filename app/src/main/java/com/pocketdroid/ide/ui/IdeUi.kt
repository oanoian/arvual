package com.pocketdroid.ide.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketdroid.ide.AssetsBundle
import com.pocketdroid.ide.LanguageFactory
import com.pocketdroid.ide.TerminalEngine
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

private val Bg = Color(0xFF0D1117)
private val Panel = Color(0xFF161B22)
private val Fg = Color(0xFFC9D1D9)
private val Accent = Color(0xFF58A6FF)
private val Green = Color(0xFF7EE787)
private val Red = Color(0xFFF85149)
private val Muted = Color(0xFF8B949E)

enum class BottomTab { TERMINAL, PROBLEMS }

@Composable
fun IdeScreen(context: Context) {
    var project by remember { mutableStateOf<File?>(null) }
    var openFile by remember { mutableStateOf<File?>(null) }
    var editorText by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var terminalLines by remember {
        mutableStateOf(listOf("PocketDroid shell - sandboxed /system/bin/sh (no root needed)."))
    }
    var input by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(BottomTab.TERMINAL) }
    var running by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val seg = uri.lastPathSegment ?: "project"
            val name = seg.substringBefore(':').removePrefix("primary").ifBlank { "untitled" }
            project = AssetsBundle.projectDir(name).also { it.mkdirs() }
            openFile = null
            dirty = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        // ---------- Top bar ----------
        Row(
            modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = project?.name ?: "PocketDroid IDE",
                color = Accent, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { picker.launch(null) }) {
                Text("Open folder", color = Accent, fontSize = 13.sp)
            }
            TextButton(onClick = {
                val p = project ?: AssetsBundle.projectDir("MyFirstProject").also {
                    it.mkdirs(); project = it
                }
                val f = File(p, "Main.kt")
                if (!f.exists()) {
                    AssetsBundle.writeText(f, "fun main() {\n    println(\"Hello from PocketDroid\")\n}\n")
                }
                openFile = f
                editorText = AssetsBundle.readText(f)
                dirty = false
            }) { Text("New file", color = Accent, fontSize = 13.sp) }
            if (openFile != null) {
                TextButton(onClick = {
                    openFile?.let { AssetsBundle.writeText(it, editorText); dirty = false }
                }) {
                    Text(
                        if (dirty) "Save*" else "Save",
                        color = if (dirty) Color(0xFFFFA657) else Accent, fontSize = 13.sp
                    )
                }
            }
        }

        // ---------- Middle: tree + editor ----------
        Row(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier.width(150.dp).fillMaxHeight().background(Panel).padding(top = 4.dp)
            ) {
                val root = project ?: AssetsBundle.projectsRoot
                val files = remember(root, openFile, project, dirty) {
                    runCatching { AssetsBundle.tree(root).take(300) }.getOrDefault(emptyList())
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { f ->
                        val rel = f.absolutePath.removePrefix(root.absolutePath).trimStart('/')
                        Text(
                            text = (if (f.isDirectory) "* " else "  ") +
                                rel.substringAfterLast('/'),
                            color = when {
                                f == openFile -> Accent
                                f.isDirectory -> Muted
                                else -> Fg
                            },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !f.isDirectory) {
                                    openFile = f
                                    editorText = runCatching { AssetsBundle.readText(f) }.getOrDefault("")
                                    dirty = false
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF30363D)))
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (openFile == null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("No file open", color = Muted, fontSize = 14.sp)
                        Text("Tap \"Open folder\" or \"New file\"", color = Color(0xFF6E7681), fontSize = 12.sp)
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            CodeEditor(ctx).apply {
                                setLineSpacing(2f, 0f)
                                typefaceText = android.graphics.Typeface.MONOSPACE
                                setText(editorText)
                                LanguageFactory.applyTo(this, openFile?.name ?: "")
                                subscribeEvent(
                                    io.github.rosemoe.sora.event.ContentChangeEvent::class.java
                                ) { _: io.github.rosemoe.sora.event.ContentChangeEvent, _: io.github.rosemoe.sora.event.Unsubscribe ->
                                    dirty = true
                                    editorText = text.toString()
                                }
                            }
                        },
                        update = { ed ->
                            if (!dirty && ed.text.toString() != editorText) {
                                ed.setText(editorText)
                                LanguageFactory.applyTo(ed, openFile?.name ?: "")
                            }
                        }
                    )
                }
            }
        }

        // ---------- Bottom panel ----------
        Column(
            modifier = Modifier.height(220.dp).fillMaxWidth().background(Panel)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BottomTab.entries.forEach { t ->
                    TextButton(onClick = { tab = t }) {
                        Text(t.name, color = if (tab == t) Accent else Muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (running) Text("running...", color = Color(0xFFFFA657), fontSize = 11.sp)
                TextButton(onClick = { terminalLines = emptyList() }) {
                    Text("clear", color = Muted, fontSize = 12.sp)
                }
            }
            if (tab == BottomTab.TERMINAL) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scroll)
                        .padding(horizontal = 8.dp)
                ) {
                    terminalLines.forEach { line ->
                        Text(
                            line,
                            color = when {
                                line.startsWith("$") -> Fg
                                line.startsWith("[") || line.contains("error", true) -> Red
                                else -> Green
                            },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Surface(color = Bg, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text("$ ", color = Green, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                color = Fg, fontFamily = FontFamily.Monospace, fontSize = 13.sp
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    submitTerminalCommand(
                                        context, project, running,
                                        cmd = input,
                                        setRunning = { running = it },
                                        setInput = { input = it },
                                        setLines = { terminalLines = terminalLines + it },
                                    )
                                }
                            ),
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f).padding(8.dp)) {
                    Text(
                        "Problems: no language server attached yet.\n" +
                            "Planned: Kotlin compiler embeddable for on-device diagnostics.",
                        color = Muted, fontSize = 12.sp
                    )
                }
            }
        }
    }

    LaunchedEffect(terminalLines.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
}

private fun submitTerminalCommand(
    context: Context,
    project: File?,
    running: Boolean,
    cmd: String,
    setRunning: (Boolean) -> Unit,
    setInput: (String) -> Unit,
    setLines: (String) -> Unit,
) {
    if (running || cmd.isBlank()) return
    val workDir = project ?: AssetsBundle.projectsRoot
    setRunning(true)
    setInput("")
    setLines("\$ $cmd")
    Thread {
        val res = TerminalEngine.exec(context, workDir, cmd.trim())
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            when (res.exitCode) {
                -2 -> setLines("[shell session ended]")
                else -> {
                    val out = (res.stdout + res.stderr).trimEnd()
                    if (out.isNotEmpty()) setLines(out)
                    if (res.exitCode != 0) setLines("[exit ${res.exitCode}]")
                }
            }
            setRunning(false)
        }
    }.start()
}
