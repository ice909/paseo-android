package sh.paseo.android.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import sh.paseo.android.core.*

// PCM matches the official native capture and daemon streaming format.
data class ConversationAudioState(
    val key: ConversationKey? = null, val mode: String = "", val phase: String = "idle",
    val partial: String = "", val error: String? = null, val duration: Int = 0,
    val volume: Float = 0f, val muted: Boolean = false,
) { val active get() = mode.isNotEmpty() }
class ConversationAudio(
    private val context: Context, private val scope: CoroutineScope, private val repository: PaseoRepository,
    private val onTranscript: (ConversationKey, String, Boolean) -> Unit,
) {
    private val mutable = MutableStateFlow(ConversationAudioState())
    val state = mutable.asStateFlow()
    private val capturing = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private var deadline: Job? = null
    private var streamId = ""
    private var ready = false
    private var sent = -1
    private var flushing: Job? = null
    private var finishRequested = false
    private var finishSent = false
    private var sendAfter = false
    private val segments = mutableListOf<ByteArray>()
    private val playback = Channel<JsonObject>(Channel.UNLIMITED)
    @Volatile private var echoEnabled = false
    private var playing = false
    private var generation = 0
    @Volatile private var playbackGeneration = 0
    private var operation: Job? = null
    init {
        scope.launch { repository.events.collect(::event) }
        scope.launch { repository.state.map { Triple(it.host?.id, it.connected, it.selectedAgentId) }.distinctUntilChanged().collect { (host, connected, selected) ->
            val current = mutable.value
            val key = current.key ?: return@collect
            if (host != key.hostId || selected != key.agentId) { cancel(); return@collect }
            if (!connected) {
                ready = false; streamId = ""; deadline?.cancel()
                if (current.mode == "voice") cancel() else if (current.phase == "uploading") mutable.update { it.copy(phase = "failed", error = "连接已断开，录音已保留，可重试") }
            } else if (current.mode == "dictation" && current.phase == "recording") restartStream()
        } }
        scope.launch {
            for (payload in playback) {
                if (mutable.value.mode != "voice" || !payload.bool("isVoiceMode")) continue
                val currentGeneration = playbackGeneration
                playing = true
                mutable.update { it.copy(phase = "playing") }
                try { withTimeout(120_000) { play(payload, currentGeneration) } }
                catch (cancelled: CancellationException) { if (!scope.isActive) throw cancelled }
                catch (error: Exception) { mutable.update { it.copy(error = "播放失败：${error.message}") } }
                finally {
                    playing = false
                    if (currentGeneration == playbackGeneration && mutable.value.mode == "voice") {
                        runCatching { repository.audioPlayed(payload.string("id")) }
                        mutable.update { it.copy(phase = "listening") }
                    }
                }
            }
        }
    }
    fun start(key: ConversationKey, voice: Boolean = false) {
        if (mutable.value.active || repository.state.value.host?.id != key.hostId || !repository.state.value.connected) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutable.value = ConversationAudioState(key = key, error = "请允许麦克风权限后重试"); return
        }
        val capability = repository.state.value.capabilities.obj("voice").obj(if (voice) "voice" else "dictation")
        if (capability.isNotEmpty() && !capability.bool("enabled")) {
            mutable.value = ConversationAudioState(key = key, error = capability.string("reason").ifBlank { "主机未启用语音服务" }); return
        }
        generation++; playbackGeneration++; segments.clear(); finishRequested = false; finishSent = false; sendAfter = false
        mutable.value = ConversationAudioState(key, if (voice) "voice" else "dictation", "starting")
        val attempt = generation
        operation = scope.launch {
            try {
                if (voice) repository.setVoiceMode(true, key.agentId) else if (!restartStream()) return@launch
                if (attempt != generation) return@launch
                mutable.update { it.copy(phase = if (voice) "listening" else "recording") }
                captureJob?.join()
                if (attempt == generation) capture(voice, attempt)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error.message ?: "无法启动录音") }
        }
    }
    private fun restartStream(): Boolean {
        flushing?.cancel(); streamId = newId(); ready = false; sent = -1; finishSent = false
        return try { repository.startDictation(streamId); armTimeout(30_000, "语音服务响应超时，录音已保留"); true }
        catch (error: Exception) { fail(error.message ?: "语音服务不可用"); false }
    }
    @Suppress("MissingPermission")
    private fun capture(voice: Boolean, attempt: Int) {
        capturing.set(true)
        captureJob = scope.launch(Dispatchers.IO) {
            var audio: AudioRecord? = null
            var echo: AcousticEchoCanceler? = null
            try {
                val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0) { "设备不支持 16 kHz 录音" }
                audio = AudioRecord(if (voice) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400))
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                recorder = audio
                if (voice && AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(audio.audioSessionId)?.apply { enabled = true }
                echoEnabled = echo?.enabled == true
                audio.startRecording()
                val buffer = ByteArray(3200)
                var total = 0L
                while (isActive && capturing.get() && attempt == generation) {
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count < 0) { if (capturing.get()) error("麦克风读取失败 ($count)") else break }
                    if (count == 0) continue
                    total += count
                    val bytes = buffer.copyOf(count)
                    var energy = 0.0
                    for (i in 0 until count - 1 step 2) { val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toDouble() / 32768; energy += sample * sample }
                    val volume = sqrt(energy / maxOf(1, count / 2)).toFloat()
                    withContext(Dispatchers.Main.immediate) {
                        if (attempt != generation || !capturing.get()) return@withContext
                        mutable.update { it.copy(duration = (total / 32000).toInt(), volume = volume) }
                        if (voice) {
                            if (repository.state.value.connected) runCatching { repository.voiceChunk(if (mutable.value.muted || playing && !echoEnabled) ByteArray(count) else bytes) }
                                .onFailure { fail(it.message ?: "语音连接已断开") }
                        } else { segments += bytes; flush() }
                        if (!voice && total >= 19_200_000) confirm(false)
                    }
                }
            } catch (error: Exception) { if (error !is CancellationException) withContext(Dispatchers.Main.immediate) { if (attempt == generation) fail(error.message ?: "录音失败") } }
            finally { runCatching { audio?.stop() }; echo?.release(); echoEnabled = false; audio?.release(); if (recorder === audio) recorder = null }
        }
    }
    private fun flush() {
        if (!ready || !repository.state.value.connected || streamId.isEmpty() || flushing?.isActive == true) return
        flushing = scope.launch {
            try {
                while (sent + 1 < segments.size) {
                    repeat(minOf(16, segments.size - sent - 1)) { sent++; repository.dictationChunk(streamId, sent, segments[sent]) }
                    yield()
                }
                if (finishRequested && !finishSent) {
                    finishSent = true; repository.finishDictation(streamId, segments.lastIndex.coerceAtLeast(0))
                    armTimeout(30_000, "转写超时，录音已保留，可重试")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error.message ?: "语音上传失败") }
        }
    }
    fun confirm(send: Boolean) {
        if (mutable.value.mode != "dictation" || mutable.value.phase !in setOf("recording", "failed")) return
        stopCapture(); sendAfter = send; finishRequested = true
        if (segments.isEmpty()) { cancel(); return }
        mutable.update { it.copy(phase = "uploading", error = null) }
        if (!repository.state.value.connected) { fail("主机未连接，录音已保留，可重试"); return }
        if (!ready || streamId.isEmpty()) restartStream() else flush()
    }
    fun retry() { ready = false; streamId = ""; confirm(sendAfter) }
    fun mute() { mutable.update { it.copy(muted = !it.muted) } }
    fun interrupt() { if (mutable.value.mode == "voice") runCatching { repository.abortVoice() }; playbackGeneration++; playing = false; while (playback.tryReceive().isSuccess) { } }
    fun cancel() {
        val old = mutable.value; generation++; playbackGeneration++; operation?.cancel(); deadline?.cancel(); flushing?.cancel(); stopCapture()
        if (old.mode == "dictation" && streamId.isNotBlank()) runCatching { repository.cancelDictation(streamId) }
        if (old.mode == "voice" && repository.state.value.host?.id == old.key?.hostId && repository.state.value.connected) scope.launch { runCatching { repository.setVoiceMode(false, requireNotNull(old.key).agentId) } }
        while (playback.tryReceive().isSuccess) { /* Discard audio from the previous session. */ }
        segments.clear(); streamId = ""; ready = false; finishRequested = false
        mutable.value = ConversationAudioState()
    }
    private fun stopCapture() { capturing.set(false); runCatching { recorder?.stop() }; captureJob?.cancel() }
    private fun fail(message: String) { stopCapture(); deadline?.cancel(); mutable.update { it.copy(phase = "failed", error = message) } }
    private fun armTimeout(ms: Long, message: String) { deadline?.cancel(); deadline = scope.launch { delay(ms.coerceIn(1000, 600_000)); fail(message) } }
    private fun event(message: JsonObject) {
        val payload = message.obj("payload")
        val current = mutable.value
        if (message.string("type") == "audio_output") { if (current.mode == "voice") playback.trySend(payload); return }
        if (current.mode != "dictation" || payload.string("dictationId") != streamId) return
        when (message.string("type")) {
            "dictation_stream_ack" -> { if (!ready) deadline?.cancel(); ready = true; flush() }
            "dictation_stream_partial" -> mutable.update { it.copy(partial = payload.string("text")) }
            "dictation_stream_finish_accepted" -> armTimeout(payload.long("timeoutMs") ?: 120_000, "转写超时，录音已保留，可重试")
            "dictation_stream_error" -> fail(payload.string("error"))
            "dictation_stream_final" -> { val key = current.key ?: return; val text = payload.string("text"); val send = sendAfter; cancel(); onTranscript(key, text, send) }
        }
    }
    private suspend fun play(payload: JsonObject, attempt: Int) = withContext(Dispatchers.IO) {
        val bytes = Base64.getDecoder().decode(payload.string("audio"))
        val format = payload.string("format")
        if (format.startsWith("audio/pcm")) {
            val rate = Regex("rate=(\\d+)").find(format)?.groupValues?.get(1)?.toIntOrNull() ?: 16_000
            require(rate in 8000..96000)
            val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(4096, AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))).setTransferMode(AudioTrack.MODE_STREAM).build()
            try {
                track.play(); var offset = 0
                while (offset < bytes.size && attempt == playbackGeneration) { ensureActive(); val count = track.write(bytes, offset, minOf(4096, bytes.size - offset)); check(count > 0); offset += count }
                while (attempt == playbackGeneration && track.playbackHeadPosition.toLong() * 2 < offset) { delay(20) }
            } finally { runCatching { track.stop() }; track.release() }
        } else {
            val file = File.createTempFile("voice-", ".audio", context.cacheDir).apply { writeBytes(bytes) }
            val player = MediaPlayer()
            try {
                val complete = CompletableDeferred<Unit>()
                player.setOnCompletionListener { complete.complete(Unit) }
                player.setOnErrorListener { _, what, _ -> complete.completeExceptionally(IllegalStateException("音频格式无法播放 ($what)")); true }
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                player.setDataSource(file.path); player.prepare(); player.start()
                while (attempt == playbackGeneration && !complete.isCompleted) delay(20)
                if (attempt == playbackGeneration) complete.await()
            } finally { player.release(); file.delete() }
        }
    }
}
