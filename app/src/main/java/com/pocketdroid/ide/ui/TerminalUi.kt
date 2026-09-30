package com.pocketdroid.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketdroid.ide.AssetsBundle
import com.pocketdroid.ide.core.CoreBridge
import com.pocketdroid.ide.core.PtySession
import com.pocketdroid.ide.core.SearchEngine
import java.io.File

private val TBg = Color(0xFF0A0E14)
private val TFg = Color(0xFFD3E1EE)
private val TGreen = Color(0xFF7EE787)
private val TAccent = Color(0xFF58A6FF)
private val TMuted = Color(0xFF8B949E)

/** Process-group owner so MainActivity.onDestroy can reap every live shell. */
object TerminalSessions {
    private val live = java.util.concurrent.CopyOnWriteArrayList<PtySession>()
    fun add(s: PtySession) = live.add(s)
    fun remove(s: PtySession) = live.remove(s)
    fun closeAll() = live.toList().forEach { runCatching { it.killTree() } }
}

/**
 * InteractiveTerminal — real session-based terminal (PTY when openpty is
 * available, pipe fallback otherwise). Handles cd tracking, ANSI stripping,
 * Ctrl-C via a kill button, and long output streaming.
 */
@Composable
fun InteractiveTerminal(project: File?) {
    var lines by remember { mutableStateOf(listOf<String>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var cwd by remember { mutableStateOf(project ?: AssetsBundle.projectsRoot) }
    var session by remember { mutableStateOf<PtySession?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(cwd) {
        // (Re)start the shell whenever the working directory changes.
        session?.let { old -> runCatching { old.killTree() }; TerminalSessions.remove(old) }
        val s = PtySession.start(androidx.compose.ui.platform.LocalContext.current, cwd)
        session = s
        TerminalSessions.add(s)
        lines = lines + "[session started pid=${s.pid} cwd=${cwd.absolutePath}]"
        Thread {
            val dec = StringBuilder()
            s.readRaw { bytes, _ ->
                // Strip common ANSI sequences for display; batch by line.
                val txt = String(bytes, Charsets.UTF_8)
                    .replace(Regex("\u001B\\[[0-9;?]*[a-zA-Z]"), "")
                    .replace("\r", "")
                dec.append(txt)
                var idx = dec.indexOf('\n')
                while (idx >= 0) {
                    val line = dec.substring(0, idx)
                    dec.delete(0, idx + 1)
                    if (line.isNotBlank()) android.os.Handler(android.os.Looper.getMainLooper())
                        .post { lines = lines + line.take(400) }
                    idx = dec.indexOf('\n')
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                lines = lines + "[session ended]"
                busy = false
            }
        }.also { it.isDaemon = true }.start()
    }

    DisposableEffect(Unit) {
        val unsub = CoreBridge.subscribe { }
        onDispose { unsub(); session?.let { runCatching { it.killTree() }; TerminalSessions.remove(it) } }
    }

    Column(modifier = Modifier.fillMaxSize().background(TBg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("cwd: ${cwd.absolutePath}", color = TMuted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1,
                modifier = Modifier.weight(1f))
            TextButton(onClick = {
                session?.write(byteArrayOf(3)) // Ctrl-C to the foreground process
            }) { Text("^C", color = Color(0xFFF85149), fontSize = 12.sp) }
            TextButton(onClick = { lines = emptyList() }) { Text("clear", color = TMuted, fontSize = 12.sp) }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 8.dp)
        ) {
            lines.forEach { l ->
                Text(l, color = if (l.startsWith("$")) TFg else if (l.startsWith("[")) TMuted else TGreen,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Surface(color = Color(0xFF10141C), modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("$ ", color = TGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = LocalTextStyle.current.copy(color = TFg, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(TAccent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        val c = input.trim()
                        input = ""
                        if (c.isEmpty()) return@KeyboardActions
                        lines = lines + "\$ $c"
                        // cd handled locally so prompt/cwd state stays accurate
                        if (c == "cd" || c.startsWith("cd ")) {
                            val target = if (c == "cd") CoreBridge.sandbox.home
                                else File(cwd, c.removePrefix("cd").trim())
                            if (target.isDirectory) {
                                cwd = target
                                session?.writeLine("cd ${target.absolutePath}")
                            } else lines = lines + "cd: no such directory: ${c.removePrefix("cd").trim()}"
                            return@KeyboardActions
                        }
                        if (c == "exit") {
                            session?.killTree(); TerminalSessions.remove(session!!); session = null
                            lines = lines + "[shell exited — tap any command to restart]"
                            return@KeyboardActions
                        }
                        session?.writeLine(c) ?: run {
                            // Session was killed; transparently start a new one via next cmd.
                            busy = true
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            Thread {
                                val code = PtySession.execOnce(ctx, cwd, c) { out ->
                                    android.os.Handler(android.os.Looper.getMainLooper())
                                        .post { lines = lines + out.take(400) }
                                }
                                android.os.Handler(android.os.Looper.getMainLooper())
                                    .post { if (code != 0) lines = lines + "[exit $code]"; busy = false }
                            }.also { it.isDaemon = true }.start()
                        }
                    }),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
            }
        }
        LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }
    }
}

/** SearchPanel — project-wide find/replace with click-to-open results. */
@Composable
fun SearchPanel(project: File?, openFileAt: (File, Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var caseSensitive by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchEngine.Match>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val root = project ?: AssetsBundle.projectsRoot
    val scroll = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().background(TBg).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = TFg, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(TAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.isBlank()) return@KeyboardActions
                    searching = true; note = ""
                    Thread {
                        val r = SearchEngine.search(root, query, useRegex, caseSensitive)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            results = r; searching = false; note = "${r.size} match(es)"
                        }
                    }.also { it.isDaemon = true }.start()
                }),
                modifier = Modifier.weight(1f).border(1.dp, Color(0xFF30363D)).padding(6.dp),
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { useRegex = !useRegex }) {
                Text("regex", color = if (useRegex) TAccent else TMuted, fontSize = 11.sp)
            }
            TextButton(onClick = { caseSensitive = !caseSensitive }) {
                Text("Aa", color = if (caseSensitive) TAccent else TMuted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = replace, onValueChange = { replace = it }, singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = TFg, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(TAccent),
                modifier = Modifier.weight(1f).border(1.dp, Color(0xFF30363D)).padding(6.dp),
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    if (query.isBlank()) return@Button
                    val rep = SearchEngine.replaceAll(root, query, replace, useRegex, caseSensitive)
                    note = "replaced ${rep.occurrences} occurrence(s) in ${rep.files} file(s)"
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("Replace all", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (searching) "searching..." else note,
            color = TMuted, fontSize = 11.sp,
        )
        Column(modifier = Modifier.weight(1f).verticalScroll(scroll)) {
            items(results) { m ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { openFileAt(m.file, m.line) }
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        m.file.absolutePath.removePrefix(root.absolutePath).trimStart('/') + ":" + m.line,
                        color = TAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(140.dp), maxLines = 1,
                    )
                    Text(m.text, color = TFg, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
        }
    }
}
