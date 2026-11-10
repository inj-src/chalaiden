package com.chalaiden.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.chalaiden.app.ocr.LiveNumber
import kotlinx.coroutines.delay
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.chalaiden.app.ocr.NumberAnalyzer
import com.chalaiden.app.ocr.NumberFinder
import com.chalaiden.app.ocr.NumberRead
import com.chalaiden.app.ui.components.NumberRail
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.Chip
import com.chalaiden.app.ui.components.PrimaryButton
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@Composable
fun ScanScreen(onRead: (NumberRead) -> Unit, onTypeInstead: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    val scope = rememberCoroutineScope()
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val read = withContext(Dispatchers.Default) { NumberFinder.read(decode(context, uri)) }
            onRead(read)
        }
    }
    val pickPhoto = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            Camera(onRead = onRead, onTypeInstead = onTypeInstead, onGallery = pickPhoto, onBack = onBack)
        } else {
            NoCamera(onAllow = { ask.launch(Manifest.permission.CAMERA) }, onGallery = pickPhoto, onTypeInstead = onTypeInstead, onBack = onBack)
        }
    }
}

@Composable
private fun Camera(onRead: (NumberRead) -> Unit, onTypeInstead: () -> Unit, onGallery: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val t = tokens
    // The viewfinder is dark in either theme, so the status bar icons go white while it's up.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(t.isDark) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.isAppearanceLightStatusBars = false
        onDispose { bars?.isAppearanceLightStatusBars = !t.isDark }
    }
    var live by remember { mutableStateOf<LiveNumber?>(null) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var guide by remember { mutableStateOf<Rect?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { ContextCompat.getMainExecutor(context) }
    // A capture posted just as the scanner closes must not navigate from whatever screen is next.
    val open = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val analyzer = remember {
        NumberAnalyzer(
            onLive = { seen -> main.execute { if (seen != null) live = seen } },
            onCaptured = { read -> main.execute { if (open.getAndSet(false)) onRead(read) } },
        )
    }
    // Kept through a missed frame or two: OCR drops a number now and then even when it is in view.
    LaunchedEffect(live) {
        if (live == null) return@LaunchedEffect
        delay(NumberAnalyzer.FRESH_MILLIS)
        live = null
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycle) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            // Preview and analysis share one 4:3 frame, so a box found in the frame lands on the preview.
            fun selector(size: Size) = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector(Size(1920, 1440))).build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector(Size(1280, 960))) // sharp enough for a clear crop, quick to read
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(executor, analyzer) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, main)
        onDispose {
            open.set(false)
            ProcessCameraProvider.getInstance(context).get().unbindAll()
            executor.shutdown()
        }
    }

    AndroidView({ previewView }, Modifier.fillMaxSize())
    NumberBox(live, guide, Modifier.fillMaxSize())

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(AppIcons.Back, "Back", onClick = onBack)
            Spacer(Modifier.weight(1f))
            CircleButton(AppIcons.Torch, if (torch) "Turn torch off" else "Turn torch on", active = torch) {
                torch = !torch
                camera?.cameraControl?.enableTorch(torch)
            }
        }

        Spacer(Modifier.weight(0.6f))
        Text(
            "Point at the number on the shop's bKash card",
            style = Type.title,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Spacer(Modifier.height(16.dp))
        // Where the brackets rest while no number is in view.
        Spacer(
            Modifier.padding(horizontal = 24.dp).fillMaxWidth().aspectRatio(3.2f)
                .onGloballyPositioned { guide = it.boundsInRoot() },
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            val l = live
            if (l != null) NumberRail(l.number, groups = l.groups, compact = true)
            else Text("Looking for an 11-digit number…", style = Type.body, color = Color.White.copy(alpha = 0.8f))
        }
        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CircleButton(AppIcons.Photo, "From photo", size = 56, onClick = onGallery)
            // The ring turns to the accent once a number is in view: pressing now takes it, and holding
            // still takes it by itself (NumberAnalyzer). No countdown is shown.
            val ring by animateColorAsState(if (live != null) t.accent else Color.White, label = "shutter")
            Box(
                Modifier.size(76.dp).clip(CircleShape).border(4.dp, ring, CircleShape).padding(8.dp).clip(CircleShape)
                    .background(Color.White).clickable(role = Role.Button) { analyzer.capture() }
                    .semantics { contentDescription = if (live != null) "Use this number" else "Take photo" },
            )
            CircleButton(AppIcons.Dialpad, "Type it", size = 56, onClick = onTypeInstead)
        }
    }
}

/** Translucent round button that reads on top of the camera preview. */
@Composable
private fun CircleButton(icon: ImageVector, label: String, size: Int = 44, active: Boolean = false, onClick: () -> Unit) {
    val t = tokens
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (active) t.accent else Color.Black.copy(alpha = 0.45f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (active) t.onAccent else Color.White, modifier = Modifier.size((size * 0.48f).dp))
    }
}

/**
 * Corner brackets that rest on [guide] while nothing is found, then move onto the number in view,
 * following its tilt, and back when it leaves. [live] is in camera-frame pixels; the preview fills
 * the screen centre-cropped (FILL_CENTER), and the same scale maps the box onto it.
 */
@Composable
private fun NumberBox(live: LiveNumber?, guide: Rect?, modifier: Modifier) {
    val accent = tokens.accent
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val corners = remember { List(4) { Animatable(Offset.Zero, Offset.VectorConverter) } }
    var placed by remember { mutableStateOf(false) }
    val color by animateColorAsState(if (live != null) accent else Color.White, label = "brackets")

    val target: List<Offset>? = when {
        live != null && canvas != IntSize.Zero -> onScreen(live, canvas)
        guide != null -> listOf(guide.topLeft, guide.topRight, guide.bottomRight, guide.bottomLeft)
        else -> null
    }
    LaunchedEffect(target) {
        target ?: return@LaunchedEffect
        if (!placed) {
            corners.zip(target).forEach { (a, p) -> a.snapTo(p) }
            placed = true
            return@LaunchedEffect
        }
        corners.zip(target).forEach { (a, p) ->
            launch { a.animateTo(p, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) }
        }
    }

    Canvas(modifier.onSizeChanged { canvas = it }) {
        if (!placed) return@Canvas
        val c = corners.map { it.value }
        val side = ((c[3] - c[0]).getDistance() + (c[2] - c[1]).getDistance()) / 2f
        val top = ((c[1] - c[0]).getDistance() + (c[2] - c[3]).getDistance()) / 2f
        val arm = (side * 0.3f).coerceAtMost(top * 0.25f).coerceAtLeast(10.dp.toPx())
        val w = 4.dp.toPx()
        for (i in 0..3) {
            val p = c[i]
            // Along the top or bottom edge, and along the side edge, from this corner.
            val along = c[if (i % 2 == 0) i + 1 else i - 1]
            val across = c[3 - i]
            for (q in listOf(along, across)) {
                val d = q - p
                val len = d.getDistance().takeIf { it > 0f } ?: continue
                drawLine(color, p, p + d * (arm / len), w, StrokeCap.Round)
            }
        }
    }
}

/** The live number's corners on screen, padded out so the brackets sit around the digits, not on them. */
private fun onScreen(live: LiveNumber, view: IntSize): List<Offset> {
    val scale = maxOf(view.width / live.frameWidth.toFloat(), view.height / live.frameHeight.toFloat())
    val dx = (view.width - live.frameWidth * scale) / 2f
    val dy = (view.height - live.frameHeight * scale) / 2f
    val s = live.span
    val q = listOf(s.tl, s.tr, s.br, s.bl).map { Offset(it.x * scale + dx, it.y * scale + dy) }
    val h = ((q[3] - q[0]).getDistance() + (q[2] - q[1]).getDistance()) / 2f
    val ux = ((q[1] - q[0]) + (q[2] - q[3])).let { it / it.getDistance().coerceAtLeast(1f) }
    val uy = ((q[3] - q[0]) + (q[2] - q[1])).let { it / it.getDistance().coerceAtLeast(1f) }
    val a = ux * (h * 0.5f)
    val b = uy * (h * 0.45f)
    return listOf(q[0] - a - b, q[1] + a - b, q[2] + a + b, q[3] - a + b)
}

@Composable
private fun NoCamera(onAllow: () -> Unit, onGallery: () -> Unit, onTypeInstead: () -> Unit, onBack: () -> Unit) {
    val t = tokens
    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding().padding(24.dp)) {
        CircleButton(AppIcons.Back, "Back", onClick = onBack)
        Spacer(Modifier.weight(1f))
        Text("Chalaiden needs the camera to read the shop's number", style = Type.display, color = t.ink)
        Spacer(Modifier.height(12.dp))
        Text("The photo stays on your phone. You can also pick a photo or type the number.", style = Type.body, color = t.inkSoft)
        Spacer(Modifier.weight(1f))
        PrimaryButton("Allow camera", onClick = onAllow)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Chip(AppIcons.Photo, "From photo", onGallery)
            Chip(AppIcons.Dialpad, "Type it", onTypeInstead)
        }
    }
}

private fun decode(context: android.content.Context, uri: Uri): Bitmap {
    val full = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = 2000f / maxOf(info.size.width, info.size.height)
            if (scale < 1f) d.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
    return full
}
