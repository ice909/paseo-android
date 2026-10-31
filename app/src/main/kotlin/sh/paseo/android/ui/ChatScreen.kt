@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package sh.paseo.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import sh.paseo.android.AppViewModel
import sh.paseo.android.core.*

@Composable
fun ChatScreen(vm: AppViewModel, state: PaseoState, onCreateAgent: () -> Unit, topOverlayInset: Dp = 0.dp, expanded: Boolean = false, onFullscreenChange: (Boolean) -> Unit = {}) {
    val agent = state.selectedAgent
    if (agent == null) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        EmptyState(PaseoIcons.Sparkles, "工作区已就绪", "新建一个代理，开始处理任务。")
        Button(onClick = onCreateAgent, enabled = state.connected) { PaseoGlyph(PaseoIcons.Plus); Text("新建代理") }
    } else state.host?.let { host -> key(host.id, agent.id) { Conversation(vm, state, agent, ConversationKey(host.id, agent.id), topOverlayInset, expanded, onFullscreenChange) } }
}

@Composable
private fun Conversation(vm: AppViewModel, state: PaseoState, agent: Agent, key: ConversationKey, topOverlayInset: Dp, expanded: Boolean, onFullscreenChange: (Boolean) -> Unit) {
    val sessions by vm.conversations.sessions.collectAsStateWithLifecycle()
    val session = sessions[key] ?: ConversationState()
    var cursor by remember { mutableIntStateOf(session.draft.text.length) }
    var cursorRequest by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val collapse by vm.collapseTurns.collectAsStateWithLifecycle()
    var expandedTurns by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var forgePicker by rememberSaveable { mutableStateOf(false) }
    val behavior by vm.sendBehavior.collectAsStateWithLifecycle()
    val imports by vm.importingAttachments.collectAsStateWithLifecycle()
    val importing = key in imports
    val audio by vm.audio.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var voiceRequested by rememberSaveable { mutableStateOf(false) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.audio.start(key, voiceRequested) else vm.notices.tryEmit("需要麦克风权限才能使用语音")
    }
    fun startAudio(voice: Boolean) {
        if (importing) return
        voiceRequested = voice
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) vm.audio.start(key, voice)
        else microphone.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (context as? android.app.Activity)?.isChangingConfigurations != true) vm.audio.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val modEnter by vm.modEnter.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by rememberSaveable { mutableStateOf(true) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var modelRetry by remember { mutableIntStateOf(0) }
    var models by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var commands by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var setting by remember { mutableStateOf(false) }
    var controlError by remember { mutableStateOf<String?>(null) }
    fun updateControl(action: suspend () -> Unit) {
        setting = true; controlError = null
        vm.action {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { controlError = error.message ?: "设置失败，请重试" }
            finally { setting = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.attachFiles(key, uris)
    }
    LaunchedEffect(key, state.connected, agent.provider, agent.cwd, modelRetry) {
        if (!state.connected) return@LaunchedEffect
        loadingModels = true; modelError = null
        try { models = vm.repository.models(agent.provider, agent.cwd) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { modelError = "无法加载主机的模型选项" }
        finally { loadingModels = false }
        try { commands = vm.repository.commands(agent.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Commands remain available as typed text. */ }
    }
    val confirmed = state.timeline.map { it.messageId }.toSet()
    val pending = session.optimistic.filterNot { it.id in confirmed }
    val rows = state.timeline + pending.map { message -> TimelineRow("local:${message.id}", "user_message", message.draft.text,
        status = message.delivery, messageId = message.id, turnId = message.turnId) }
    val turnActive = agent.activeTurn != null || agent.status == "running" || session.sending
    val nestedKeys = remember(rows, expandedTurns, collapse) { if (collapse) expandedTurnWorkKeys(rows, expandedTurns.toSet()) else emptySet() }
    val presented = remember(rows, turnActive, expandedTurns, collapse) {
        groupToolRows(conversationPresentation(rows.filter(::visibleToolRow), turnActive, expandedTurns.toSet(), collapse), enabled = collapse, ungroupedKeys = nestedKeys)
    }
    val footers = remember(rows, turnActive) { completedAssistantFooters(rows.filter(::visibleToolRow), turnActive) }
    val last = rows.lastOrNull()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 100) }
            .distinctUntilChanged().collect { (scrolling, atBottom) -> if (scrolling) following = atBottom }
    }
    LaunchedEffect(last?.key, last?.text?.length, agent.running, session.optimistic.size) {
        if (following) listState.scrollToItem(0)
    }
    LaunchedEffect(listState, state.hasOlder, state.loadingTimeline, presented.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { index ->
            if (index >= presented.size - 3 && rows.isNotEmpty() && state.hasOlder && !state.loadingTimeline) vm.action { vm.repository.loadOlder() }
        }
    }
    val activeBehavior = if (behavior == SendBehavior.Queue && agent.pendingPermissions.isNotEmpty()) SendBehavior.Interrupt else behavior
    CompositionLocalProvider(LocalToolExpansion provides rememberToolExpansion()) {
    Column(Modifier.fillMaxSize().then(if (expanded) Modifier.statusBarsPadding() else Modifier).imePadding()) {
        Box(Modifier.fillMaxWidth().then(if (expanded) Modifier.height(0.dp) else Modifier.weight(1f))) {
            if (state.loadingTimeline && rows.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (rows.isEmpty() && !state.loadingTimeline && !turnActive) EmptyState(PaseoIcons.Sparkles, "从一个任务开始", "描述你想完成的工作，代理将在主机上执行。", Modifier.fillMaxSize())
            else LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topOverlayInset + 16.dp, bottom = 16.dp)) {
                if (turnActive) item("working") {
                    RunningTurnFooter(turnStartedAt(agent), session.cancelling, agent.pendingPermissions.isNotEmpty())
                }
                itemsIndexed(presented.asReversed(), key = { _, entry -> entry.key }, contentType = { _, entry -> if (entry.grouped) "tool_group" else entry.row.type }) { reverseIndex, entry ->
                    val row = entry.row
                    val footer = footers[entry.calls.lastOrNull()?.key ?: row.key]
                    val next = presented.getOrNull(presented.lastIndex - reverseIndex + 1)
                    val compactGap = (entry.grouped || row.groupable()) && next?.let { it.grouped || it.row.groupable() } == true
                    val rail = LocalPaseoPalette.current.border
                    Column(Modifier.then(if (row.key in nestedKeys) Modifier.drawBehind {
                        drawLine(rail, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                    }.padding(start = 16.dp) else Modifier).padding(bottom = if (footer != null) 0.dp else if (compactGap) 4.dp else 16.dp)) {
                        if (entry.grouped) ToolGroup(entry, agent.cwd)
                        else if (row.type == "turn_summary") ToolBadge(row.title, icon = PaseoIcons.Summary,
                            expanded = row.messageId in expandedTurns, onClick = {
                                expandedTurns = if (row.messageId in expandedTurns) expandedTurns - row.messageId else expandedTurns + row.messageId
                            })
                        else TimelineItem(vm, state, agent, key, row, session.sentAttachments[row.messageId].orEmpty())
                        if (footer != null) CompletedTurnFooter(footer)
                    }
                }
                if (state.hasOlder) item("older") {
                    TextButton(onClick = { vm.action { vm.repository.loadOlder() } }, enabled = !state.loadingTimeline, modifier = Modifier.fillMaxWidth()) { Text(if (state.loadingTimeline) "加载中…" else "加载更早的消息") }
                }
            }
            if (!following && !expanded) FilledTonalIconButton(onClick = { following = true; scope.launch { listState.animateScrollToItem(0) } }, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                PaseoGlyph(PaseoIcons.ArrowDown, "最新消息")
            }
        }
        if (agent.lastError != null && !agent.running && !expanded) Text(agent.lastError.orEmpty(), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (agent.pendingPermissions.isNotEmpty() && !expanded) Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            agent.pendingPermissions.forEach { request -> key(request.id) { PermissionCard(vm, agent.id, request, state.connected) } }
        }
        if (session.queue.isNotEmpty() && !expanded) Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            session.queue.forEach { message -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(message.draft.text.ifBlank { message.draft.attachments.joinToString { it.name } }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.conversations.editQueued(key, message.id) }, enabled = !session.sending, modifier = Modifier.size(32.dp)) { PaseoGlyph(PaseoIcons.Pencil, "编辑排队消息", Modifier.size(16.dp)) }
                FilledIconButton(onClick = { vm.conversations.sendQueued(key, message.id) }, enabled = state.connected && !session.sending, modifier = Modifier.size(28.dp)) { PaseoGlyph(PaseoIcons.ArrowUp, "立即发送排队消息", Modifier.size(16.dp)) }
            } }
        }
        session.error?.let { error -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (session.failed != null) TextButton(onClick = { vm.conversations.retry(key) }, enabled = state.connected && !session.sending) { Text("重试") }
            IconButton(onClick = { vm.conversations.dismissError(key) }, modifier = Modifier.size(28.dp)) { PaseoGlyph(PaseoIcons.X, "关闭发送错误", Modifier.size(14.dp)) }
        } }
        if (forgePicker) ForgeAttachmentPicker(vm, agent, state.features.bool("forgeSearch"), { vm.conversations.attach(key, listOf(it)); forgePicker = false }) { forgePicker = false }
        if (audio.key == key) ConversationAudioPanel(vm, audio)
        ChatAutocomplete(vm, state, agent, session.draft.text, cursor, commands) { text, position -> cursorRequest = text to position; vm.conversations.setDraft(key, text) }
        MessageComposer(session.draft.text, { vm.conversations.setDraft(key, it) }, "发送消息，/ 选择命令", enabled = audio.mode != "dictation", canSend = state.connected && !importing && (if (audio.mode == "dictation") audio.phase == "recording" else agent.running || !session.draft.empty),
            sending = session.sending || importing || audio.phase == "uploading", onSend = { following = true; if (audio.mode == "dictation") vm.audio.confirm(true) else vm.conversations.send(key) }, running = agent.running && audio.mode != "dictation",
            onStop = { vm.conversations.stop(key) }, onAlternateSend = { following = true; vm.conversations.send(key, alternate = true) },
            behavior = activeBehavior, modEnter = modEnter, cancelling = session.cancelling, hasAttachments = session.draft.attachments.isNotEmpty(),
            expanded = expanded, onExpand = { onFullscreenChange(!expanded) }, modifier = if (expanded) Modifier.weight(1f) else Modifier,
            onReceiveFiles = { vm.attachFiles(key, it) }, readSentMessages = { rows.asReversed().filter { it.type == "user_message" && it.text.isNotBlank() }.map { it.text } }, cursorRequest = cursorRequest, onSelection = { cursor = it },
            trailingControls = { IconButton(onClick = { if (audio.mode == "voice") vm.audio.mute() else if (!audio.active) startAudio(false) }, enabled = state.connected && !importing && audio.mode != "dictation", modifier = Modifier.size(32.dp)) { PaseoGlyph(PaseoIcons.Mic, if (audio.mode == "voice") "切换麦克风静音" else "语音输入", Modifier.size(18.dp), if (audio.active) LocalPaseoPalette.current.accent else LocalPaseoPalette.current.foregroundMuted) } },
            attachments = { ChatAttachmentTray(session.draft.attachments) { vm.conversations.removeAttachment(key, it) } }, scrollOptions = false, options = {
                ChatComposerMenu(behavior, vm::setSendBehavior, { picker.launch(arrayOf("*/*")) }, { picker.launch(arrayOf("image/*")) }, { startAudio(true) }, { forgePicker = true }, collapse, { vm.setCollapseTurns(!collapse) })
                CompactAgentControls(agent, models, enabled = state.connected && !setting,
                    loading = loadingModels, modelError = modelError, actionError = controlError, onRetry = { modelRetry++ }, modifier = Modifier.weight(1f),
                    onModel = { model -> updateControl { vm.repository.setModel(agent.id, model) } },
                    onThinking = { option -> updateControl { vm.repository.setThinking(agent.id, option) } },
                    onMode = { mode -> updateControl { vm.repository.setMode(agent.id, mode) } },
                    onFeature = { id, value -> updateControl { vm.repository.setFeature(agent.id, id, value) } })
            })
    }
    ConversationToolSheet(rows, agent.cwd)
    }
}

@Composable
private fun ChatAutocomplete(vm: AppViewModel, state: PaseoState, agent: Agent, draft: String, cursor: Int, commands: List<JsonObject>, onText: (String, Int) -> Unit) {
    val before = draft.substring(0, cursor.coerceIn(0, draft.length))
    val after = draft.substring(cursor.coerceIn(0, draft.length))
    val mention = Regex("(?:^|\\s)@([^\\s]*)$").find(before)
    var files by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mention?.value, state.connected) {
        files = emptyList(); error = null
        if (mention == null || !state.connected) return@LaunchedEffect
        delay(180); loading = true
        try { files = vm.repository.fileSuggestions(agent.cwd, mention.groupValues[1]) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
        finally { loading = false }
    }
    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
        if (before.startsWith('/') && !before.contains(' ') && !before.contains('\n')) {
            commands.filter { it.string("name").removePrefix("/").contains(before.removePrefix("/"), true) }.take(8).forEach { command ->
                ListItem(colors = paseoListItemColors(), headlineContent = { Text("/" + command.string("name").removePrefix("/")) }, supportingContent = { Text(command.string("description"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable { val replacement = "/" + command.string("name").removePrefix("/") + " "; onText(replacement + after, replacement.length) })
            }
        }
        if (mention != null) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            files.take(8).forEach { file -> ListItem(colors = paseoListItemColors(), headlineContent = { Text(file.string("path"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { FileGlyph(file.string("path"), file.string("kind") == "directory") }, modifier = Modifier.clickable {
                    val path = file.string("path")
                    val prefix = before.substring(0, before.lastIndexOf('@'))
                    val replacement = prefix + "\"" + path.replace("\"", "\\\"") + "\""
                    onText(replacement + after, replacement.length)
                }) }
        }
    }
}

@Composable
private fun TimelineItem(vm: AppViewModel, state: PaseoState, agent: Agent, key: ConversationKey, row: TimelineRow, localAttachments: List<ChatAttachment>) {
    when (row.type) {
        "user_message" -> ChatUserMessage(row, localAttachments, agent, state.connected, vm, key, { vm.conversations.retryPending(key, row.messageId) }, { vm.conversations.editPending(key, row.messageId) }) { mode -> vm.rewind(key, row, mode) }
        "assistant_message" -> MarkdownText(row.text, vm, state, agent, key, "${agent.id}:${row.key}")
        "reasoning" -> ReasoningMessage(row)
        "tool_call" -> ToolMessage(row, agent.cwd)
        "todo" -> Surface(shape = RoundedCornerShape(12.dp), color = LocalPaseoPalette.current.surface0) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("任务进度", style = MaterialTheme.typography.labelLarge)
                row.detail.array("items").forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaseoGlyph(if (item.bool("completed") || item.string("status") == "completed") PaseoIcons.CircleCheck else PaseoIcons.Circle, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(item.string("text"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        "error", "notification" -> Text(row.text, color = if (row.type == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        "compaction" -> Text(if (row.status == "loading") "正在整理上下文…" else "上下文已整理", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> if (row.text.isNotBlank()) Text(row.text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PermissionCard(vm: AppViewModel, agentId: String, request: PermissionRequest, connected: Boolean) {
    var pending by remember(request) { mutableStateOf(false) }
    LaunchedEffect(connected) { if (!connected) pending = false }
    val questions = remember(request) { request.questions() }
    val actions = request.actions.ifEmpty { listOf(PermissionAction("", "允许", "allow"), PermissionAction("", "拒绝", "deny")) }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PaseoGlyph(PaseoIcons.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(request.title ?: request.name, style = MaterialTheme.typography.titleSmall)
            }
            request.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (questions.isNotEmpty()) {
                QuestionForm(questions, connected && !pending) { answers ->
                    pending = true
                    vm.action { try { vm.repository.respondToPermission(agentId, request, "allow", updatedInput = request.answeredInput(answers)) }
                        catch (error: Exception) { pending = false; throw error } }
                }
            } else {
                val detail = request.detail?.let { it.string("command").ifBlank { it.string("text").ifBlank { it.toString() } } } ?: request.input?.toString()
                detail?.let { Text(it.take(3000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.filter { questions.isEmpty() || it.behavior == "deny" }.forEach { action ->
                    TextButton(onClick = {
                        pending = true
                        vm.action { try { vm.repository.respondToPermission(agentId, request, action.behavior, action.id.ifBlank { null }) }
                            catch (error: Exception) { pending = false; throw error } }
                    }, enabled = connected && !pending) { Text(action.label, color = if (action.behavior == "deny") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}
