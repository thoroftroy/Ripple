package dev.wonderdesk.ripple.shell

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.wonderdesk.ripple.Notifier
import dev.wonderdesk.ripple.R
import dev.wonderdesk.ripple.Ripple
import dev.wonderdesk.ripple.Store
import dev.wonderdesk.ripple.Transfers
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Answers `wd` requests from the phone's command line. */
object WdServer {
    @Volatile private var running = false

    val apps = linkedMapOf(
        "abacus" to "Abacus", "apphub" to "App Hub", "beacon" to "Beacon", "chronicle" to "Chronicle", "cinema" to "Cinema",
        "controlroom" to "Control Room", "echo" to "Echo", "launcher" to "Launcher", "memories" to "Memories", "packrat" to "Packrat",
        "pages" to "Pages", "pulse" to "Pulse", "ripple" to "Ripple", "safekeeper" to "Safekeeper", "scribble" to "Scribble",
        "timepiece" to "Timepiece", "vault" to "Vault", "wayfinder" to "Wayfinder",
    )

    fun start(c: Context) {
        if (running) return
        running = true
        val ctx = c.applicationContext
        Thread({
            val server = try { LocalServerSocket("ripple-wd-" + android.os.Process.myUid()) } catch (e: Exception) { running = false; return@Thread }
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: continue
                Thread { serve(ctx, s) }.start()
            }
        }, "wd-server").apply { isDaemon = true; start() }
    }

    private fun serve(c: Context, s: LocalSocket) {
        s.use { sock ->
            if (sock.peerCredentials.uid != android.os.Process.myUid()) return
            val line = sock.inputStream.bufferedReader().readLine() ?: return
            val req = JSONObject(line)
            val a = req.optJSONArray("args")
            val args = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
            val cwd = req.optString("cwd")
            val out = StringBuilder()
            val code = try { run(c, args, cwd, out) } catch (e: Exception) { out.appendLine("wd: ${e.message}"); 1 }
            sock.outputStream.write((out.toString().trimEnd('\n') + "\n\u0000EXIT:$code\n").toByteArray())
            sock.outputStream.flush()
        }
    }

    private fun pkgFor(name: String): String = when {
        name.contains('.') -> name
        else -> "dev.wonderdesk." + name.lowercase().replace(" ", "").replace("-", "")
    }

    private fun file(cwd: String, p: String) = if (p.startsWith("/")) File(p) else File(cwd, p)

    private const val HELP = """wd: Wonderdesk tools for this phone

  wd apps                    Wonderdesk apps installed here, with versions
  wd info APP                version, install dates and granted permissions
  wd open APP                open an app (by name, e.g. memories)
  wd settings [APP]          open Android's page for an app (or Ripple)
  wd update [APP]            check for updates in App Hub
  wd uninstall APP           ask Android to uninstall an app
  wd install FILE.apk        install an APK
  wd crashes                 recent crash reports collected by Control Room
  wd look                    the current Wonderdesk look
  wd computers               computers paired with Ripple
  wd send FILE [COMPUTER]    send a file to a computer's Ripple folder
  wd open-file FILE|URL      open a file or link in its app
  wd share FILE              share a file
  wd clip [TEXT]             show or set the clipboard
  wd notify TITLE [TEXT]     post a notification
  wd vibrate [MS]            vibrate
  wd battery | wifi | storage | device
  wd devtools                open Developer options
  wd wireless-debugging      open Wireless debugging"""

    private fun run(c: Context, args: List<String>, cwd: String, out: StringBuilder): Int {
        val pm = c.packageManager
        val cmd = args.firstOrNull() ?: "help"
        val rest = args.drop(1)
        fun launch(i: Intent) { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        when (cmd) {
            "help", "-h", "--help" -> out.appendLine(HELP)
            "apps" -> {
                out.appendLine(String.format("%-14s %-10s %s", "App", "Version", "Updated"))
                var n = 0
                apps.forEach { (id, label) ->
                    val info = runCatching { pm.getPackageInfo("dev.wonderdesk.$id", 0) }.getOrNull() ?: return@forEach
                    n++
                    out.appendLine(String.format("%-14s %-10s %s", label, info.versionName, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(info.lastUpdateTime))))
                }
                runCatching { pm.getPackageInfo("com.blissplayer.app", 0) }.getOrNull()?.let { out.appendLine(String.format("%-14s %-10s", "Bliss Player", it.versionName)); n++ }
                out.appendLine("$n installed")
            }
            "info" -> {
                val pkg = pkgFor(rest.firstOrNull() ?: return usage(out, "wd info APP"))
                val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull() ?: run { out.appendLine("$pkg isn't installed"); return 1 }
                val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                out.appendLine("${pm.getApplicationLabel(info.applicationInfo!!)} ($pkg)")
                out.appendLine("Version     ${info.versionName} (${info.longVersionCode})")
                out.appendLine("Installed   ${df.format(Date(info.firstInstallTime))}")
                out.appendLine("Updated     ${df.format(Date(info.lastUpdateTime))}")
                out.appendLine("Target SDK  ${info.applicationInfo!!.targetSdkVersion}")
                out.appendLine("APK size    ${Notifier.human(File(info.applicationInfo!!.sourceDir).length())}")
                val perms = info.requestedPermissions ?: emptyArray()
                val flags = info.requestedPermissionsFlags ?: IntArray(0)
                val granted = perms.filterIndexed { i, _ -> i < flags.size && (flags[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }
                out.appendLine("Granted     ${granted.map { it.substringAfterLast('.') }.sorted().joinToString(", ").ifEmpty { "none" }}")
            }
            "open" -> {
                val target = rest.firstOrNull() ?: return usage(out, "wd open APP")
                val i = pm.getLaunchIntentForPackage(pkgFor(target)) ?: run { out.appendLine("Can't open $target"); return 1 }
                launch(i)
            }
            "settings" -> launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkgFor(rest.firstOrNull() ?: "ripple"))))
            "update" -> {
                val i = pm.getLaunchIntentForPackage("dev.wonderdesk.apphub") ?: run { out.appendLine("App Hub isn't installed"); return 1 }
                rest.firstOrNull()?.let { i.putExtra("app", it) }
                launch(i)
                out.appendLine("Opened App Hub")
            }
            "uninstall" -> launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkgFor(rest.firstOrNull() ?: return usage(out, "wd uninstall APP")))))
            "install" -> {
                val f = file(cwd, rest.firstOrNull() ?: return usage(out, "wd install FILE.apk"))
                if (!f.exists()) { out.appendLine("${f.path} doesn't exist"); return 1 }
                val uri = FileProvider.getUriForFile(c, "dev.wonderdesk.ripple.share", f)
                launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            "crashes" -> {
                val uri = Uri.parse("content://dev.wonderdesk.controlroom.crashes/report")
                val text = runCatching { c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                    buildString { while (cur.moveToNext()) { for (i in 0 until cur.columnCount) append(cur.getString(i) ?: "").append(if (i < cur.columnCount - 1) " | " else "\n") } }
                } }.getOrNull()
                out.appendLine(text?.ifBlank { "No crash reports. Nice." } ?: "Control Room's crash reports aren't readable from here (is Control Room installed?)")
            }
            "look" -> out.appendLine(dev.wonderdesk.shared.GlobalAppearance.read(c)?.let { "${it.first.label} (motion: ${it.second.label})" } ?: "Aqua (Control Room not installed)")
            "computers" -> {
                val list = Store.computers(c)
                if (list.isEmpty()) out.appendLine("No computers paired. Pair one in Ripple.")
                list.forEach { comp -> out.appendLine("${comp.name}  ${comp.addresses.firstOrNull() ?: ""}") }
            }
            "send" -> {
                val f = file(cwd, rest.firstOrNull() ?: return usage(out, "wd send FILE [COMPUTER]"))
                if (!f.isFile) { out.appendLine("${f.path} isn't a file"); return 1 }
                val comps = Store.computers(c)
                val comp = rest.getOrNull(1)?.let { n -> comps.firstOrNull { it.name.equals(n, true) } } ?: comps.firstOrNull() ?: run { out.appendLine("No computers paired"); return 1 }
                Ripple.init(c)
                val saved = Transfers.upload(c, comp.fp, Uri.fromFile(f), null, notify = true)
                out.appendLine("Sent to ${comp.name}: $saved")
            }
            "open-file" -> {
                val t = rest.firstOrNull() ?: return usage(out, "wd open-file FILE|URL")
                if (t.contains("://")) launch(Intent(Intent.ACTION_VIEW, Uri.parse(t)))
                else {
                    val f = file(cwd, t)
                    val uri = FileProvider.getUriForFile(c, "dev.wonderdesk.ripple.share", f)
                    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                    launch(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open ${f.name}"))
                }
            }
            "share" -> {
                val f = file(cwd, rest.firstOrNull() ?: return usage(out, "wd share FILE"))
                val uri = FileProvider.getUriForFile(c, "dev.wonderdesk.ripple.share", f)
                launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}"))
            }
            "clip" -> {
                val cm = c.getSystemService(ClipboardManager::class.java)
                if (rest.isEmpty()) {
                    val t = Ripple.main.let { h ->
                        var r: String? = null
                        val lock = Object()
                        h.post { r = cm.primaryClip?.getItemAt(0)?.coerceToText(c)?.toString(); synchronized(lock) { lock.notifyAll() } }
                        synchronized(lock) { lock.wait(2000) }
                        r
                    }
                    out.appendLine(t ?: "(the clipboard is empty, or Android hides it while Ripple isn't on screen)")
                } else {
                    Ripple.main.post { cm.setPrimaryClip(ClipData.newPlainText("wd", rest.joinToString(" "))) }
                    out.appendLine("Copied")
                }
            }
            "notify" -> {
                Notifier.channels(c)
                Notifier.post(c, (System.currentTimeMillis() % 100000).toInt(), android.app.Notification.Builder(c, Notifier.CH_ALERTS)
                    .setSmallIcon(R.drawable.ic_stat_ripple).setContentTitle(rest.firstOrNull() ?: "wd").setContentText(rest.drop(1).joinToString(" ")).setAutoCancel(true).build())
            }
            "vibrate" -> c.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot((rest.firstOrNull()?.toLongOrNull() ?: 300L).coerceIn(1, 5000), VibrationEffect.DEFAULT_AMPLITUDE))
            "battery" -> {
                val bm = c.getSystemService(BatteryManager::class.java)
                out.appendLine("Level     ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")
                out.appendLine("Charging  ${if (bm.isCharging) "yes" else "no"}")
                val now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (now != Int.MIN_VALUE && now != 0) out.appendLine("Current   ${now / 1000} mA")
            }
            "wifi" -> {
                val cm = c.getSystemService(ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                val lp = cm.getLinkProperties(cm.activeNetwork)
                out.appendLine("Network   " + when {
                    caps == null -> "none"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "other"
                })
                lp?.linkAddresses?.forEach { out.appendLine("Address   ${it.address.hostAddress}") }
                caps?.let { out.appendLine("Speed     ↓ ${it.linkDownstreamBandwidthKbps / 1000} Mb/s  ↑ ${it.linkUpstreamBandwidthKbps / 1000} Mb/s") }
            }
            "storage" -> {
                val st = StatFs(Environment.getDataDirectory().path)
                out.appendLine("Free      ${Notifier.human(st.availableBytes)} of ${Notifier.human(st.totalBytes)}")
                val am = c.getSystemService(ActivityManager::class.java)
                val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                out.appendLine("Memory    ${Notifier.human(mi.availMem)} free of ${Notifier.human(mi.totalMem)}")
            }
            "device" -> {
                out.appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                out.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}")
                out.appendLine("ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
            }
            "devtools" -> launch(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            "wireless-debugging" -> launch(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").let { if (it.resolveActivity(pm) != null) it else Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) })
            else -> { out.appendLine("wd: unknown command “$cmd”. Try wd help."); return 1 }
        }
        return 0
    }

    private fun usage(out: StringBuilder, text: String): Int { out.appendLine("usage: $text"); return 2 }
}
