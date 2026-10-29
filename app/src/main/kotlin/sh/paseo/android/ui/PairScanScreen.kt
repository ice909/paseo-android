package sh.paseo.android.ui

import sh.paseo.android.ui.PaseoText as Text

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.paseo.android.AppViewModel
import sh.paseo.android.R
import sh.paseo.android.core.Endpoint
import sh.paseo.android.data.PairingQrDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun PairScanScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val p = LocalPaseoPalette.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    var cameraAttempt by remember { mutableIntStateOf(0) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val busy by vm.pairingBusy.collectAsStateWithLifecycle()
    val error by vm.pairingError.collectAsStateWithLifecycle()
    val needsPassword by vm.pairingPasswordNeeded.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { vm.clearPairingError(resetPassword = true); if (!granted && !requested) { requested = true; permission.launch(Manifest.permission.CAMERA) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun retry() { vm.cancelPairing(); vm.clearPairingError(resetPassword = true); cameraError = null; link = ""; paused = false; cameraAttempt++ }
    Column(Modifier.fillMaxSize().background(p.surface0).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).clickable(role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) { PaseoGlyph(R.drawable.lucide_arrow_left, "返回", Modifier.size(18.dp), p.foregroundMuted) }
            Text("扫描二维码", style = PaseoUiText, fontWeight = FontWeight.Medium, color = p.foreground)
        }
        Column(Modifier.weight(1f).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            if (!granted) Column(Modifier.padding(top = 24.dp).fillMaxWidth().background(p.surface2, RoundedCornerShape(12.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("相机权限", style = PaseoUiText, fontWeight = FontWeight.SemiBold, color = p.foreground)
                Text("允许相机访问，以扫描 daemon 提供的配对二维码。", style = PaseoUiText, color = p.foregroundMuted)
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF3B82F6)).clickable(role = Role.Button) {
                    if (requested && context is android.app.Activity && !context.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    else { requested = true; permission.launch(Manifest.permission.CAMERA) }
                }.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text("授予权限", style = PaseoUiText, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            } else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(p.surface2)) {
                CameraQrPreview(cameraAttempt, paused || busy || needsPassword, { raw ->
                    if (!paused && !busy) {
                        paused = true; link = raw
                        try { require("#offer=" in raw || "#connect=" in raw || raw.startsWith("relay://")); Endpoint.parse(raw); vm.saveHost(null, "", raw, "") {} }
                        catch (_: IllegalArgumentException) { cameraError = "无效的配对链接" }
                    }
                }, { cameraError = it; paused = true })
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    ScanFrame()
                    if (busy) Text("正在配对...", Modifier.padding(top = 24.dp), style = PaseoUiText, color = p.foreground)
                    (cameraError ?: error)?.let { Text(it, Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp), style = PaseoUiText, color = p.foreground) }
                    if (paused && !busy && !needsPassword) PaseoButton("扫描二维码", ::retry, Modifier.padding(top = 24.dp))
                }
            }
        }
    }
    if (needsPassword && link.isNotBlank()) PairingSheet(vm, initialLink = link, passwordRequired = true,
        onDismiss = { vm.clearPairingError(resetPassword = true); paused = true }, onSaved = {})
}

@Composable
private fun ScanFrame() {
    val color = LocalPaseoPalette.current.accent
    Canvas(Modifier.size(260.dp)) {
        val length = 36.dp.toPx()
        val stroke = 4.dp.toPx()
        val radius = 12.dp.toPx()
        val corner = Path().apply {
            moveTo(0f, length)
            lineTo(0f, radius)
            arcTo(Rect(0f, 0f, radius * 2, radius * 2), 180f, 90f, false)
            lineTo(length, 0f)
        }
        for (quarter in 0..3) rotate(quarter * 90f) { drawPath(corner, color, style = Stroke(stroke)) }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun CameraQrPreview(attempt: Int, paused: Boolean, onCode: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentPaused by rememberUpdatedState(paused)
    val currentCode by rememberUpdatedState(onCode)
    val currentError by rememberUpdatedState(onError)
    val view = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; contentDescription = "相机预览"; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES } }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "相机预览" })
    DisposableEffect(owner, view, attempt) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val disposed = AtomicBoolean(false)
        val delivering = AtomicBoolean(false)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val decoder = PairingQrDecoder()
        var lastDecodeNanos = 0L
        val analysis = ImageAnalysis.Builder().setTargetResolution(Size(1280, 720)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { image ->
            try {
                if (!disposed.get() && !currentPaused && !delivering.get() && System.nanoTime() - lastDecodeNanos >= 120_000_000L) {
                    lastDecodeNanos = System.nanoTime()
                    val plane = image.planes[0]
                    val code = decoder.decode(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                    if (code != null && delivering.compareAndSet(false, true)) main.execute {
                        try { if (!disposed.get() && !currentPaused) currentCode(code) }
                        finally { delivering.set(false) }
                    }
                }
            } finally { image.close() }
        }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            if (!disposed.get()) try {
                provider = future.get()
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                else currentError("无法打开相机，请检查相机权限后重试。")
            } catch (_: Exception) { currentError("无法打开相机，请检查相机权限后重试。") }
        }, main)
        onDispose { disposed.set(true); analysis.clearAnalyzer(); provider?.unbind(preview, analysis); executor.shutdown() }
    }
}
