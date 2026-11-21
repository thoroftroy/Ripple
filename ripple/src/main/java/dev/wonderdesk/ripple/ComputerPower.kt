package dev.wonderdesk.ripple

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** How busy the computer is, from the daemon's `stats` request. */
data class Usage(
    val cpu: Int, val cores: Int, val memUsed: Long, val memTotal: Long, val swapUsed: Long, val swapTotal: Long,
    val gpu: Int?, val temp: Int?, val down: Long, val up: Long, val diskFree: Long, val diskTotal: Long,
    val load: Double, val uptime: Long,
) {
    val memPct get() = if (memTotal > 0) (memUsed * 100 / memTotal).toInt() else 0

    /** One short line for the top bar. */
    fun line(): String = buildList {
        add("CPU $cpu%")
        add("RAM $memPct%")
        gpu?.let { add("GPU $it%") }
        temp?.let { add("$it°C") }
    }.joinToString(" · ")

    companion object {
        fun from(o: JSONObject) = Usage(
            o.optInt("cpu"), o.optInt("cores", 1), o.optLong("mem_used"), o.optLong("mem_total"), o.optLong("swap_used"), o.optLong("swap_total"),
            if (o.isNull("gpu") || !o.has("gpu")) null else o.optInt("gpu"),
            if (o.isNull("temp") || !o.has("temp")) null else o.optInt("temp"),
            o.optLong("net_down"), o.optLong("net_up"), o.optLong("disk_free"), o.optLong("disk_total"),
            o.optJSONArray("load")?.optDouble(0, 0.0) ?: 0.0, o.optLong("uptime"),
        )
    }
}

/** A transfer rate in a few characters: 58K, 1.2M. */
fun rate(n: Long): String = when { n >= 1L shl 20 -> "%.1fM".format(n / (1L shl 20).toDouble()); n >= 1L shl 10 -> "${n shr 10}K"; else -> "${n}B" }

fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.0f MB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

private fun span(secs: Long): String {
    val d = secs / 86400; val h = secs % 86400 / 3600; val m = secs % 3600 / 60
    return if (d > 0) "${d}d ${h}h" else if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** A slim strip under the toolbar with the computer's load, each figure with a small bar. Tap it for the details. */
@Composable
fun UsageStrip(u: Usage, onClick: () -> Unit) {
    val p = wonderPalette()
    @Composable fun stat(label: String, value: String, pct: Int?) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = p.secondaryText)
                Spacer(Modifier.width(4.dp))
                Text(value, style = androidx.compose.material3.MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = p.text)
            }
            if (pct != null) {
                val c = if (pct < 60) androidx.compose.ui.graphics.Color(0xFF34C759) else if (pct < 85) androidx.compose.ui.graphics.Color(0xFFF2A516) else androidx.compose.ui.graphics.Color(0xFFE5483C)
                Box(Modifier.padding(top = 2.dp).width(44.dp).height(3.dp).background(p.panelBorder, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(pct.coerceIn(2, 100) / 100f).fillMaxHeight().background(c, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().background(p.panel).clickable(onClick = onClick).horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        stat("CPU", "${u.cpu}%", u.cpu)
        stat("RAM", "${u.memPct}%", u.memPct)
        u.gpu?.let { stat("GPU", "$it%", it) }
        u.temp?.let { stat("TEMP", "$it°", (it - 30) * 100 / 60) }
        stat("NET", "↓" + rate(u.down), null)
    }
}

/** What the usage strip shows: figures, or a note that the computer's Ripple is too old to report them. */
data class UsageState(val usage: Usage? = null, val old: Boolean = false)

/** Turns a failed request into something a person can act on. */
fun friendlyError(e: String?, computer: String): String =
    if (e != null && e.startsWith("unknown request")) "$computer is running an older Ripple that can't do this yet. It updates itself within hours, or run the installer again to update now."
    else e ?: "Something went wrong"

/** Polls the computer's usage every few seconds, only while the screen is showing and the link is up. */
@Composable
fun rememberUsage(link: Link, connected: Boolean, enabled: Boolean): State<UsageState> {
    val usage = remember(link) { mutableStateOf(UsageState()) }
    val resumed = rememberIsResumed()
    LaunchedEffect(link, connected, enabled, resumed) {
        if (!connected || !enabled || !resumed) return@LaunchedEffect
        while (isActive) {
            val r = withContext(Dispatchers.IO) { runCatching { link.request(JSONObject().put("op", "stats")) } }
            r.onSuccess { o -> usage.value = if (o.has("error")) UsageState(old = o.optString("error").startsWith("unknown request")) else UsageState(Usage.from(o)) }
            r.onFailure { e -> if (e.message?.startsWith("unknown request") == true) usage.value = UsageState(old = true) }
            delay(3000)
        }
    }
    return usage
}

@Composable
fun UsageDialog(computerName: String, u: Usage, onDismiss: () -> Unit) {
    WonderDialog(
        onDismissRequest = onDismiss,
        title = { Text(computerName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fun row(k: String, v: String) = k to v
                listOfNotNull(
                    row("Processor", "${u.cpu}% of ${u.cores} cores"),
                    row("Memory", "${bytes(u.memUsed)} of ${bytes(u.memTotal)} (${u.memPct}%)"),
                    if (u.swapTotal > 0) row("Swap", "${bytes(u.swapUsed)} of ${bytes(u.swapTotal)}") else null,
                    u.gpu?.let { row("Graphics", "$it%") },
                    u.temp?.let { row("Temperature", "$it°C") },
                    row("Network", "↓ ${bytes(u.down)}/s   ↑ ${bytes(u.up)}/s"),
                    row("Disk", "${bytes(u.diskFree)} free of ${bytes(u.diskTotal)}"),
                    row("Load", "%.2f".format(u.load)),
                    row("Up for", span(u.uptime)),
                ).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                        Text(k, Modifier.padding(end = 14.dp), color = wonderPalette().secondaryText)
                        Text(v, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
        },
        confirmButton = { WButton("Done", onDismiss, kind = ButtonKind.Primary) },
    )
}

/**
 * Wake-on-LAN. A magic packet only travels on the computer's own network, so on the home network the phone sends it directly. Away from home
 * (or on mobile data) the phone asks any other computer of yours that is awake and connected to send it for it.
 */
object Wake {
    fun send(computer: Computer): String {
        val macs = computer.macs
        if (macs.isEmpty()) return "Update Ripple on ${computer.name} and connect once, so this phone can learn how to wake it."
        val direct = sendDirect(computer, macs)
        // Ask other connected computers to send it on their network too: this is what makes waking work away from home.
        var helpers = 0
        Store.computers(Ripple.app).filter { it.fp != computer.fp }.forEach { other ->
            val link = Ripple.link(other.fp) ?: return@forEach
            if (link.state.value is LinkState.Connected) runCatching {
                val r = link.request(JSONObject().put("op", "wake_others").put("macs", org.json.JSONArray(macs)))
                if (r.optBoolean("ok")) helpers++
            }
        }
        return when {
            direct > 0 && helpers > 0 -> "Wake-up sent to ${computer.name}, also from $helpers other computer${if (helpers > 1) "s" else ""}. It can take up to a minute to come back."
            helpers > 0 -> "Asked $helpers other computer${if (helpers > 1) "s" else ""} on its network to wake ${computer.name}. It can take up to a minute."
            direct > 0 -> "Wake-up sent to ${computer.name}. It only works on the computer's own network unless another of your computers is awake and connected to send it. It can take up to a minute."
            else -> "Couldn't send the wake-up. You're not on ${computer.name}'s network, and no other connected computer could send it for you."
        }
    }

    private fun sendDirect(computer: Computer, macs: List<String>): Int {
        val targets = linkedSetOf("255.255.255.255")
        computer.addresses.forEach { a ->
            val ip = a.substringBeforeLast(':').trim('[', ']')
            val p = ip.split('.')
            if (p.size == 4 && p.all { it.toIntOrNull() != null }) { targets += "${p[0]}.${p[1]}.${p[2]}.255"; targets += ip }
        }
        var sent = 0
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                for (mac in macs) {
                    val hw = mac.split(':').mapNotNull { it.toIntOrNull(16)?.toByte() }
                    if (hw.size != 6) continue
                    val payload = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { hw }.toByteArray()
                    for (t in targets) for (port in intArrayOf(9, 7)) {
                        runCatching { s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(t), port)); sent++ }
                    }
                }
            }
        }
        return sent
    }
}

/** Runs a request and counts a reply with an error in it as a failure. */
fun Link.ask(o: JSONObject): Result<JSONObject> = runCatching { request(o).also { if (it.has("error")) throw RuntimeException(it.getString("error")) } }

/** Shown in place of the usage strip when there are no figures yet. */
@Composable
fun UsageNote(old: Boolean, name: String) {
    val p = wonderPalette()
    Text(if (old) "Usage needs the newer Ripple on $name. It updates itself, or run the installer again." else "Reading $name…",
        Modifier.fillMaxWidth().background(p.panel).padding(horizontal = 12.dp, vertical = 8.dp), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = p.secondaryText)
}
