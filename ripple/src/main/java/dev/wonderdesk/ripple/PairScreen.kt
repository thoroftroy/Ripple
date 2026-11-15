package dev.wonderdesk.ripple

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.wonderdesk.ripple.net.Native
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** What a scanned ripple://pair link carries. */
data class PairLink(val name: String, val addrs: String, val fp: String?, val code: String?, val bell: String?) {
    companion object {
        fun parse(s: String): PairLink? {
            val u = runCatching { Uri.parse(s) }.getOrNull() ?: return null
            if (u.scheme != "ripple") return null
            val port = u.getQueryParameter("p") ?: "47800"
            val addrs = (u.getQueryParameter("a") ?: "").split(',').filter { it.isNotBlank() }.joinToString(",") { a ->
                when {
                    a.startsWith("[") -> a
                    a.count { it == ':' } == 1 -> a
                    a.contains(':') -> "[$a]:$port"
                    else -> "$a:$port"
                }
            }
            val bs = u.getQueryParameter("bs"); val bt = u.getQueryParameter("bt"); val bk = u.getQueryParameter("bk")
            val bell = if (bs != null && bt != null && bk != null) JSONObject().put("server", bs).put("topic", bt).put("key", bk).toString() else null
            return PairLink(u.getQueryParameter("n") ?: "Computer", addrs, u.getQueryParameter("f"), u.getQueryParameter("c"), bell)
        }
    }
}

private data class Found(val name: String, val fp: String, val addr: String)

@Composable
fun PairScreen(uri: String?, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) } // 0 scan, 1 nearby, 2 address
    var working by remember { mutableStateOf<String?>(null) }
    var compare by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Found?>(null) }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    val device = remember { android.os.Build.MODEL ?: "Phone" }

    fun pair(addr: String, pin: String?, c: String?, bell: String?, label: String) {
        error = null; compare = null
        working = "Connecting to $label…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    Native.pair(addr, pin, c?.takeIf { it.isNotBlank() }, device, bell) { s ->
                        if (s.startsWith("compare:")) {
                            compare = s.removePrefix("compare:")
                            working = if (c.isNullOrBlank()) "Waiting for you to allow this phone on $label…" else "Checking the code…"
                        }
                    }
                }
            }
            working = null
            r.onSuccess { json ->
                val comp = Computer(JSONObject(json))
                Store.saveComputer(context, comp)
                onDone(comp.fp)
            }.onFailure { error = it.message ?: "Pairing didn't work" }
        }
    }

    LaunchedEffect(uri) {
        val link = uri?.let { PairLink.parse(it) }
        if (link != null) pair(link.addrs, link.fp, link.code, link.bell, link.name)
    }

    fun search() {
        searching = true
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { JSONArray(Native.discover(2500)) }.getOrNull()?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it).let { o -> Found(o.optString("name"), o.optString("fp"), o.optString("addr")) } }
                } ?: emptyList()
            }
            found = list; searching = false
        }
    }
    LaunchedEffect(mode) { if (mode == 1 && found.isEmpty()) search() }

    WonderApp(appName = "Ripple", about = "Ripple", title = "Add a computer", onBack = { onDone(null) }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val w = working
            if (w != null) {
                Panel {
                    LoadingStatus(w, Modifier.fillMaxWidth())
                    compare?.let {
                        Text("Check that your computer shows", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = wonderPalette().secondaryText)
                        Text(it, Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    }
                }
                return@Column
            }
            error?.let { Panel { ListRow(it, icon = WonderIcons.Error, danger = true) } }
            Segmented(listOf("Scan code", "On this Wi-Fi", "Address"), mode, onSelect = { mode = it })
            when (mode) {
                0 -> {
                    Text("On your computer open Ripple and choose “Pair a phone” (or run `ripple pair`). Point the camera at the code.", color = wonderPalette().secondaryText)
                    QrScanner(Modifier.fillMaxWidth().aspectRatio(1f)) { text ->
                        val link = PairLink.parse(text)
                        if (link != null && working == null) pair(link.addrs, link.fp, link.code, link.bell, link.name)
                    }
                }
                1 -> {
                    Panel(title = "Computers running Ripple nearby", trailing = { ToolbarButton(WonderIcons.Refresh, "Search again", { search() }) }) {
                        if (searching) LoadingStatus("Looking on this network…", compact = true)
                        else if (found.isEmpty()) ListRow("None found", subtitle = "Make sure both are on the same Wi-Fi and Ripple is open on the computer. A firewall can hide it: use Scan code instead.", icon = WonderIcons.Wifi)
                        found.forEachIndexed { i, f ->
                            ListRow(f.name, subtitle = f.addr, icon = WonderIcons.DeveloperBoard, selected = picked == f, onClick = { picked = f })
                            if (i < found.lastIndex) PanelDivider()
                        }
                    }
                    picked?.let { f ->
                        Field(code, { code = it.uppercase() }, "Pairing code (optional)", supporting = "Shown on the computer under the QR code. Leave empty to approve on the computer instead.")
                        WButton("Pair with ${f.name}", { pair(f.addr, f.fp, code, null, f.name) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
                    }
                }
                2 -> {
                    Field(address, { address = it }, "Computer address", supporting = "Its name or IP address, e.g. 192.168.1.20 (port 47800 unless you changed it)")
                    Field(code, { code = it.uppercase() }, "Pairing code (optional)")
                    WButton("Pair", { if (address.isNotBlank()) pair(address.trim(), null, code, null, address.trim()) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = address.isNotBlank())
                }
            }
        }
    }
}

/** Camera preview that reports the first QR code it reads. */
@Composable
fun QrScanner(modifier: Modifier, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    val p = wonderPalette()
    Box(modifier.clip(RoundedCornerShape(16.dp)).border(2.dp, p.accent, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        if (!granted) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                Text("Ripple needs the camera to read the pairing code.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                WButton("Allow camera", { ask.launch(Manifest.permission.CAMERA) }, kind = ButtonKind.Primary)
            }
            return@Box
        }
        val reader = remember { MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) } }
        val executor = remember { Executors.newSingleThreadExecutor() }
        var done by remember { mutableStateOf(false) }
        DisposableEffect(Unit) { onDispose { executor.shutdown() } }
        AndroidView(factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { img ->
                    try {
                        if (!done) {
                            val plane = img.planes[0]
                            val buf = plane.buffer
                            val data = ByteArray(buf.remaining()).also { buf.get(it) }
                            val src = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
                            val result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))) }.getOrNull()
                            reader.reset()
                            if (result != null && result.text.startsWith("ripple://")) {
                                done = true
                                view.post { onCode(result.text) }
                            }
                        }
                    } finally { img.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize(.62f).border(3.dp, Color.White.copy(.85f), RoundedCornerShape(18.dp)))
    }
}
