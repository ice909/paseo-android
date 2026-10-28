package sh.paseo.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import sh.paseo.android.AppViewModel
import sh.paseo.android.core.*

@Composable
fun FilesPane(vm: AppViewModel, agent: Agent, connected: Boolean, modifier: Modifier) {
    var path by rememberSaveable(agent.id) { mutableStateOf("") }
    var openFile by rememberSaveable(agent.id) { mutableStateOf(false) }
    var payload by remember { mutableStateOf(jsonObject()) }
    var version by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var editBase by rememberSaveable { mutableStateOf("{}") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val file = payload.obj("file")
    val original = remember(editBase) { wireJson.parseToJsonElement(editBase).jsonObject }
    val dirty = editing && draft != original.string("content")
    fun navigateUp() {
        if (dirty) { confirmDiscard = true; return }
        editing = false
        path = path.trimEnd('/').substringBeforeLast('/', "")
        openFile = false
    }
    BackHandler(path.isNotEmpty() || openFile || editing) { navigateUp() }
    LaunchedEffect(agent.id, path, openFile, version, connected) {
        if (!connected) return@LaunchedEffect
        loading = true; error = null
        try { payload = vm.repository.files(agent.cwd, path, openFile) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = e.message }
        finally { loading = false }
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navigateUp() }, enabled = path.isNotEmpty() || openFile) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上级目录") }
            Text(path.ifBlank { agent.cwd.substringAfterLast('/').ifBlank { "/" } }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (openFile && file.string("kind") == "text") IconButton(onClick = {
                draft = file.string("content"); editBase = file.toString(); editing = true
            }, enabled = connected && !loading && !saving) { Icon(Icons.Outlined.Edit, "编辑文件") }
            IconButton(onClick = { version++ }, enabled = connected && !editing && !loading) { Icon(Icons.Outlined.Refresh, "刷新文件") }
        }
        if (loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorMessage(it) }
        when {
            openFile && !loading && file.string("kind") == "text" -> {
                val lines = remember(file) { file.string("content").lines() }
                val scroll = rememberScrollState()
                LazyColumn(Modifier.fillMaxSize().horizontalScroll(scroll), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(lines) { index, line -> Row(Modifier.padding(horizontal = 12.dp)) {
                        Text("${index + 1}".padStart(4), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 14.dp))
                        SelectionContainer { Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp) }
                    } }
                }
            }
            openFile && !loading -> EmptyState(Icons.Outlined.InsertDriveFile, "暂不支持预览此文件", "当前支持 UTF-8 文本文件。", Modifier.weight(1f))
            else -> {
                val entries = payload.obj("directory").array("entries").map { it.jsonObject }.sortedWith(compareBy<JsonObject> { it.string("kind") != "directory" }.thenBy { it.string("name").lowercase() })
                if (entries.isEmpty() && !loading) EmptyState(Icons.Outlined.FolderOpen, "目录为空", path.ifBlank { agent.cwd }, Modifier.weight(1f))
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.string("path") }) { entry ->
                        Surface(onClick = { path = entry.string("path"); openFile = entry.string("kind") != "directory" }, color = MaterialTheme.colorScheme.background) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(if (entry.string("kind") == "directory") Icons.Outlined.FolderOpen else Icons.Outlined.Description, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(entry.string("name"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                if (entry.string("kind") == "directory") Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (editing) Dialog(onDismissRequest = { if (!saving) { if (dirty) confirmDiscard = true else editing = false } }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (dirty) confirmDiscard = true else editing = false }, enabled = !saving) { Icon(Icons.Outlined.Close, "关闭编辑器") }
                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch {
                        saving = true; error = null
                        try { vm.repository.saveFile(agent.cwd, original, draft); editing = false; version++ }
                        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
                        finally { saving = false }
                    } }, enabled = connected && !saving && dirty) { Text(if (saving) "保存中…" else "保存文件") }
                }
                error?.let { ErrorMessage(it) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
                    BasicTextField(draft, { draft = it }, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp))
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = { editing = false; confirmDiscard = false }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
}

@Composable
fun DiffPane(vm: AppViewModel, agent: Agent, connected: Boolean, modifier: Modifier) {
    var payload by remember { mutableStateOf(jsonObject()) }
    var version by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(agent.cwd, version, connected) {
        if (!connected) return@LaunchedEffect
        loading = true; error = null
        try { payload = vm.repository.diff(agent.cwd) }
        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
        finally { loading = false }
    }
    val files = payload.array("files").map { it.jsonObject }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("未提交的改动 · ${files.size} 个文件", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { version++ }, enabled = connected && !loading) { Icon(Icons.Outlined.Refresh, "刷新改动") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorMessage(it) }
        if (!loading && error == null && files.isEmpty()) EmptyState(Icons.Outlined.Difference, "工作区干净", "这里会显示主机上的未提交改动。", Modifier.weight(1f))
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (payload.bool("diffTooLarge")) item { ErrorMessage("部分改动过大，主机省略了详细内容。") }
            files.forEach { file ->
                item("file:${file.string("path")}") {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Difference, null, Modifier.size(16.dp))
                        Text(file.string("path"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text("+${file.long("additions") ?: 0}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        Text("−${file.long("deletions") ?: 0}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (file.string("status") in setOf("too_large", "binary")) item { Text("二进制文件或内容过大", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                file.array("hunks").forEachIndexed { hunkIndex, h ->
                    val hunk = h.jsonObject
                    item("${file.string("path")}:h$hunkIndex") { Text("@@ −${hunk.long("oldStart")} +${hunk.long("newStart")} @@", Modifier.fillMaxWidth().padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    itemsIndexed(hunk.array("lines"), key = { i, _ -> "${file.string("path")}:$hunkIndex:$i" }) { _, element ->
                        val line = element.jsonObject
                        val type = line.string("type")
                        val color = when (type) { "add" -> MaterialTheme.colorScheme.primary; "remove" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface }
                        Row(Modifier.fillMaxWidth().background(color.copy(alpha = if (type in setOf("add", "remove")) 0.09f else 0f)).padding(horizontal = 12.dp, vertical = 2.dp)) {
                            Text(when (type) { "add" -> "+ "; "remove" -> "− "; else -> "  " }, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            SelectionContainer { Text(line.string("content"), color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalPane(vm: AppViewModel, agent: Agent, connected: Boolean, modifier: Modifier) {
    var terminals by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selectedId by rememberSaveable(agent.id) { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var command by rememberSaveable(agent.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(agent.id, connected) {
        if (!connected) return@LaunchedEffect
        try {
            terminals = vm.repository.terminals(agent)
            if (selectedId.isBlank()) selectedId = terminals.firstOrNull()?.string("id").orEmpty()
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
    }
    LaunchedEffect(selectedId, connected) {
        text = ""
        if (selectedId.isBlank() || !connected) return@LaunchedEffect
        while (isActive) {
            try { text = vm.repository.captureTerminal(selectedId); error = null }
            catch (e: Exception) { if (e is CancellationException) throw e; error = e.message; break }
            delay(1_000)
        }
    }
    fun input(data: String) {
        try { vm.repository.terminalInput(selectedId, data) }
        catch (e: Exception) { error = e.message }
    }
    Column(modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = true }) { Icon(Icons.Outlined.Terminal, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(terminals.find { it.string("id") == selectedId }?.string("name")?.ifBlank { "终端" } ?: "文本终端"); Icon(Icons.Outlined.ExpandMore, null) }
                DropdownMenu(expanded, { expanded = false }) { terminals.forEach { terminal -> DropdownMenuItem(text = { Text(terminal.string("name").ifBlank { terminal.string("id").take(8) }) }, onClick = { selectedId = terminal.string("id"); expanded = false }) } }
            }
            IconButton(onClick = { scope.launch {
                loading = true
                try {
                    val terminal = vm.repository.createTerminal(agent)
                    terminals = terminals + terminal; selectedId = terminal.string("id")
                } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
                finally { loading = false }
            } }, enabled = connected && !loading) { Icon(Icons.Outlined.Add, "新建终端") }
        }
        Text("文本模式 · 每秒刷新最近 300 行", Modifier.padding(start = 20.dp, bottom = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { ErrorMessage(it) }
        if (selectedId.isBlank()) EmptyState(Icons.Outlined.Terminal, "还没有终端", "点击右上角 + 在主机上打开终端。", Modifier.weight(1f))
        else {
            val lines = remember(text) { text.lines() }
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(text) { if (list.firstVisibleItemIndex == 0) list.scrollToItem(0) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF121214)).padding(12.dp), state = list, reverseLayout = true) {
                items(lines.asReversed()) { line -> SelectionContainer { Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFFE4E4E7)) } }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { input("\u0003") }, enabled = connected && selectedId.isNotBlank()) { Text("Ctrl+C") }
            TextButton(onClick = { input("\u001b") }, enabled = connected && selectedId.isNotBlank()) { Text("Esc") }
            TextButton(onClick = { input("\t") }, enabled = connected && selectedId.isNotBlank()) { Text("Tab") }
            TextButton(onClick = { input("\r") }, enabled = connected && selectedId.isNotBlank()) { Text("Enter") }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(command, { command = it }, placeholder = { Text("输入命令") }, modifier = Modifier.weight(1f), singleLine = true)
            FilledIconButton(onClick = { input(command + "\r"); command = "" }, enabled = connected && selectedId.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send, "执行命令") }
        }
    }
}

@Composable
private fun ErrorMessage(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
