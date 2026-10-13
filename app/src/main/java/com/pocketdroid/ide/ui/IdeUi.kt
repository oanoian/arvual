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
import com.pocketdroid.ide.LocalServer
import com.pocketdroid.ide.TranslateEngine
import com.pocketdroid.ide.LanguageFactory
import com.pocketdroid.ide.RuntimeManager
import com.pocketdroid.ide.TerminalEngine
import com.pocketdroid.ide.core.AnalyzeEngine
import com.pocketdroid.ide.core.CoreBridge
import com.pocketdroid.ide.core.WorkspaceStore
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

private val Bg = Color(0xFF0D1117)
private val Panel = Color(0xFF161B22)
private val Fg = Color(0xFFC9D1D9)
private val Accent = Color(0xFF58A6FF)
private val Green = Color(0xFF7EE787)
private val Red = Color(0xFFF85149)
private val Muted = Color(0xFF8B949E)

enum class BottomTab { TERMINAL, RUNTIMES, DEPLOY, PROBLEMS, SEARCH }

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
    var installed by remember { mutableStateOf(RuntimeManager.installed(context)) }
    var runtimeMsg by remember { mutableStateOf("") }
    var problems by remember { mutableStateOf<List<CoreBridge.Event.Diagnostic>>(emptyList()) }
    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }
    var pendingJump by remember { mutableStateOf<Pair<File, Int>?>(null) }
    val scroll = rememberScrollState()

    // ---- Control-plane subscription: diagnostics stream into the PROBLEMS tab.
    DisposableEffect(Unit) {
        val unsub = CoreBridge.subscribe { ev ->
            if (ev is CoreBridge.Event.Diagnostic)
                android.os.Handler(android.os.Looper.getMainLooper())
                    .post { problems = (listOf(ev) + problems).take(200) }
        }
        onDispose { unsub() }
    }

    // ---- Workspace restore (persistence layer).
    LaunchedEffect(Unit) {
        val ws = WorkspaceStore.load(context)
        ws.projectName?.let { name ->
            val dir = AssetsBundle.projectDir(name)
            if (dir.isDirectory) project = dir
        }
        ws.tabs.firstOrNull()?.let { t ->
            val f = File(t.path)
            if (f.isFile && (project == null || f.absolutePath.startsWith(project!!.absolutePath))) {
                openFile = f
                editorText = runCatching { AssetsBundle.readText(f) }.getOrDefault("")
                dirty = false
            }
        }
    }

    // ---- Save workspace whenever tabs/project change (debounced by Compose recomposition).
    LaunchedEffect(project, openFile) {
        WorkspaceStore.save(
            context,
            WorkspaceStore.Workspace(
                projectName = project?.name,
                tabs = openFile?.let { listOf(WorkspaceStore.TabState(it.absolutePath)) } ?: emptyList(),
                activeTab = openFile?.absolutePath,
                terminalCwd = project?.absolutePath,
            )
        )
    }

    fun saveCurrentFile() {
        openFile?.let {
            AssetsBundle.writeText(it, editorText)
            dirty = false
            AnalyzeEngine.analyzeNow(it, editorText)   // feed the PROBLEMS panel
        }
    }

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
                TextButton(onClick = { saveCurrentFile() }) {
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
                                editorRef = this
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
                            // Jump-to-line requested from PROBLEMS/SEARCH panels.
                            pendingJump?.let { (f, line) ->
                                if (f == openFile) {
                                    val ln = (line - 1).coerceIn(0, (ed.lineCount - 1).coerceAtLeast(0))
                                    ed.cursor.lineToLine(ln)
                                    ed.scrollToLocation(ln, 0, true, false)
                                    pendingJump = null
                                }
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
                InteractiveTerminal(project)
            } else if (tab == BottomTab.SEARCH) {
                SearchPanel(project, openFileAt = { f, line ->
                    openFile = f
                    editorText = runCatching { AssetsBundle.readText(f) }.getOrDefault("")
                    dirty = false
                    pendingJump = f to line
                })
            } else if (tab == BottomTab.RUNTIMES) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scroll)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "On-device toolchains (downloaded into app storage; no root needed).\n" +
                            "Note: arm64-v8a devices only.",
                        color = Muted, fontSize = 11.sp
                    )
                    RuntimeManager.available.filter { !it.name.endsWith("-libs") }.forEach { rt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(
                                rt.label,
                                color = Fg, fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            val have = installed.contains(rt.name)
                            if (have) {
                                Text("installed ✓", color = Green, fontSize = 12.sp)
                            } else {
                                Button(
                                    onClick = {
                                        runtimeMsg = "Starting ${rt.name} download..."
                                        RuntimeManager.install(context, rt.name) { pct, msg ->
                                            android.os.Handler(android.os.Looper.getMainLooper())
                                                .post {
                                                    runtimeMsg = if (pct < 0) msg
                                                    else "$msg ($pct%)"
                                                    if (pct == 100) {
                                                        installed = RuntimeManager.installed(context)
                                                        terminalLines = terminalLines +
                                                            "[${rt.name} ready — try '${if (rt.name == "python") "python --version" else "node --version"}' in TERMINAL]"
                                                    }
                                                }
                                        }
                                    },
                                    enabled = !RuntimeManager.isBusy(),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text("install ~${rt.sizeHintMB}MB", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    if (runtimeMsg.isNotEmpty()) {
                        Text(runtimeMsg, color = Accent, fontSize = 12.sp)
                    }
                    Text(
                        "After install: python / pip / node / npm / npx work directly in the TERMINAL tab.",
                        color = Muted, fontSize = 11.sp
                    )
                }
            } else if (tab == BottomTab.DEPLOY) {
                DeployPanel(context, project, setLines = { terminalLines = terminalLines + it })
            } else if (tab == BottomTab.PROBLEMS) {
                ProblemsPanel(problems, onClear = { problems = emptyList() }) { diag ->
                    // Click-to-jump: resolve file inside the project if possible.
                    val root = project ?: AssetsBundle.projectsRoot
                    val candidate = File(root, diag.file)
                    if (candidate.isFile) {
                        openFile = candidate
                        editorText = runCatching { AssetsBundle.readText(candidate) }.getOrDefault("")
                        dirty = false
                        pendingJump = candidate to diag.line
                    }
                }
            } else Box(modifier = Modifier.weight(1f))
        }
    }

    LaunchedEffect(terminalLines.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
}

@Composable
private fun ProblemsPanel(
    problems: List<CoreBridge.Event.Diagnostic>,
    onClear: () -> Unit,
    onClick: (CoreBridge.Event.Diagnostic) -> Unit,
) {
    val scroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Problems (${problems.count { it.severity == "error" }} errors, " +
                "${problems.count { it.severity == "warning" }} warnings)",
                color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("clear", color = Muted, fontSize = 12.sp) }
        }
        if (problems.isEmpty())
            Text(
                "No diagnostics yet. Save a .py/.js/.html file and lightweight checks run automatically.\n" +
                    "(Full LSP IntelliSense is the next milestone.)",
                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(8.dp),
            )
        else Column(modifier = Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 8.dp)) {
            problems.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onClick(p) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (p.severity) { "error" -> "✕" ; "warning" -> "⚠" ; else -> "ℹ" },
                        color = when (p.severity) { "error" -> Red; "warning" -> Color(0xFFFFA657); else -> Accent },
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${p.file}:${p.line} — ${p.message}",
                        color = Fg, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeployPanel(
    context: android.content.Context,
    project: java.io.File?,
    setLines: (String) -> Unit,
) {
    var status by remember { mutableStateOf("") }
    var url by remember { mutableStateOf<String?>(null) }
    val dir = project ?: AssetsBundle.projectsRoot

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            "Translate & deploy — detects project type, prepares artifacts, and serves on localhost.",
            color = Muted, fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val r = TranslateEngine.translate(context, dir)
                    status = "[${r.kind}] ${r.message}" +
                        (r.startCommand?.let { "\n\$ $it" } ?: "")
                    setLines("[deploy] ${r.message}")
                    if (r.webRoot != null && r.kind == TranslateEngine.ProjectKind.WEB_STATIC) {
                        url = LocalServer.serve(r.webRoot)
                        setLines("[server] serving ${r.webRoot.name} at $url")
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("Detect & prepare", fontSize = 12.sp) }

            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    url = LocalServer.serve(dir)
                    status = "Serving entire project folder."
                    setLines("[server] $url")
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("Serve folder", fontSize = 12.sp) }

            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    LocalServer.stop()
                    status = "Server stopped."
                    url = null
                    setLines("[server] stopped")
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("Stop", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(6.dp))
        if (status.isNotEmpty()) Text(status, color = Fg, fontSize = 12.sp)
        url?.let {
            Text("Open in device browser: $it", color = Accent, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Executable (.exe) note: Android cannot emit Windows PE binaries directly.\n" +
                "For Python projects we bundle a PyInstaller spec; for Node we write a pkg config.\n" +
                "Run 'build-exe.sh' / 'build-exe.bat' on any PC to get win/linux/macos binaries.",
            color = Muted, fontSize = 11.sp
        )
    }
}
