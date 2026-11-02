package com.chalaiden.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    var live by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember {
        NumberAnalyzer(
            onLive = { live = it },
            onCaptured = { read -> ContextCompat.getMainExecutor(context).execute { onRead(read) } },
        )
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycle) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(executor, analyzer) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
            executor.shutdown()
        }
    }

    AndroidView({ previewView }, Modifier.fillMaxSize())

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Text(
                if (torch) "Torch on" else "Torch",
                style = Type.label,
                color = if (torch) t.onMarigold else Color.White,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (torch) t.marigold else Color.White.copy(alpha = 0.18f))
                    .clickable(role = Role.Switch) {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        Spacer(Modifier.weight(0.6f))
        Text(
            "Point at the number on the shop's bKash card",
            style = Type.title,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Spacer(Modifier.height(16.dp))
        Viewfinder(locked = live != null, modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().aspectRatio(3.2f))
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            val l = live
            if (l != null) NumberRail(l, compact = true, onTeal = true)
            else Text("Looking for an 11-digit number…", style = Type.body, color = Color.White.copy(alpha = 0.8f))
        }
        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("From photo", style = Type.label, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onGallery).padding(12.dp))
            Box(
                Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(8.dp).clip(CircleShape)
                    .background(Color.White).clickable(role = Role.Button) { analyzer.captureNow = true }
                    .semantics { contentDescription = "Capture now" },
            )
            Text("Type it", style = Type.label, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onTypeInstead).padding(12.dp))
        }
    }
}

/** Corner brackets around the strip where the printed number should sit. */
@Composable
private fun Viewfinder(locked: Boolean, modifier: Modifier) {
    val color = if (locked) tokens.marigold else Color.White
    Canvas(modifier) {
        val len = size.height * 0.28f
        val w = 4.dp.toPx()
        listOf(
            Offset(0f, 0f) to Offset(1f, 1f), Offset(size.width, 0f) to Offset(-1f, 1f),
            Offset(0f, size.height) to Offset(1f, -1f), Offset(size.width, size.height) to Offset(-1f, -1f),
        ).forEach { (o, d) ->
            drawLine(color, o, Offset(o.x + d.x * len, o.y), w, StrokeCap.Round)
            drawLine(color, o, Offset(o.x, o.y + d.y * len), w, StrokeCap.Round)
        }
    }
}

@Composable
private fun NoCamera(onAllow: () -> Unit, onGallery: () -> Unit, onTypeInstead: () -> Unit, onBack: () -> Unit) {
    val t = tokens
    Column(Modifier.fillMaxSize().background(t.paper).systemBarsPadding().padding(24.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = t.ink) }
        Spacer(Modifier.weight(1f))
        Text("Chalaiden needs the camera to read the shop's number", style = Type.display, color = t.ink)
        Spacer(Modifier.height(12.dp))
        Text("The photo stays on your phone. You can also pick a photo or type the number.", style = Type.body, color = t.inkSoft)
        Spacer(Modifier.weight(1f))
        PrimaryButton("Allow camera", onClick = onAllow)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("From photo", style = Type.button, color = t.teal, modifier = Modifier.clickable(onClick = onGallery).padding(14.dp))
            Text("Type it", style = Type.button, color = t.teal, modifier = Modifier.clickable(onClick = onTypeInstead).padding(14.dp))
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
