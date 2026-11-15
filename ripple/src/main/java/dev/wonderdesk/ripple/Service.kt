package dev.wonderdesk.ripple

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import org.json.JSONObject

object Notifier {
    const val CH_ALERTS = "alerts"
    const val CH_FILES = "files"
    const val CH_LINK = "link"
    private var nextId = 2000

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Session alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A session rang the bell, sent a message, or went quiet after working"
        })
        nm.createNotificationChannel(NotificationChannel(CH_FILES, "Files", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Files sent between this phone and your computers" })
        nm.createNotificationChannel(NotificationChannel(CH_LINK, "Staying connected", NotificationManager.IMPORTANCE_MIN).apply { description = "Shown while Ripple keeps a connection open in the background" })
    }

    fun openSession(c: Context, fp: String, session: Int): PendingIntent {
        val i = Intent(c, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("computer", fp).putExtra("session", session)
        return PendingIntent.getActivity(c, fp.hashCode() * 31 + session, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun onEvent(link: Link, ev: JSONObject) {
        val c = Ripple.app
        when (ev.optString("event")) {
            "attention" -> {
                if (!Store.alerts(c)) return
                val sid = ev.optInt("session")
                // Don't buzz for the session that's on screen right now.
                if (MainActivity.visibleSession == link.computer.fp to sid) return
                val text = ev.optString("text").ifBlank { "Rang the bell" }
                val title = "${ev.optString("name").ifBlank { "Session" }} · ${link.computer.name}"
                post(c, (link.computer.fp + sid).hashCode(), Notification.Builder(c, CH_ALERTS)
                    .setSmallIcon(R.drawable.ic_stat_ripple).setContentTitle(title).setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(openSession(c, link.computer.fp, sid)).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE).build())
            }
            "file_offer" -> {
                val name = ev.optString("name")
                val save = Intent(c, ActionReceiver::class.java).setAction(ActionReceiver.SAVE)
                    .putExtra("computer", link.computer.fp).putExtra("path", ev.optString("path")).putExtra("name", name)
                val id = nextId++
                save.putExtra("notification", id)
                val pi = PendingIntent.getBroadcast(c, id, save, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                post(c, id, Notification.Builder(c, CH_FILES).setSmallIcon(R.drawable.ic_stat_ripple)
                    .setContentTitle("${link.computer.name} sent $name").setContentText("${human(ev.optLong("size"))} · tap Save to keep it in Downloads")
                    .addAction(Notification.Action.Builder(null, "Save", pi).build()).setContentIntent(pi).setAutoCancel(true).build())
            }
        }
    }

    fun post(c: Context, id: Int, n: Notification) {
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    fun human(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }
}

/** Keeps connections alive while Ripple is in the background so sessions can alert you. */
class RippleService : Service() {
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Ripple.init(this)
        Notifier.channels(this)
        if (intent?.action == "stop") {
            Ripple.active().forEach { it.stop() }
            stopSelf()
            return START_NOT_STICKY
        }
        val names = Ripple.active().joinToString { it.computer.name }.ifBlank { "your computer" }
        val stop = PendingIntent.getService(this, 1, Intent(this, RippleService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, Notifier.CH_LINK).setSmallIcon(R.drawable.ic_stat_ripple)
            .setContentTitle("Connected to $names").setContentText("Sessions can alert you while Ripple is closed")
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        watchNetwork()
        return START_STICKY
    }

    private fun watchNetwork() {
        if (callback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        var last: Network? = cm.activeNetwork
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network != last) { last = network; Ripple.networkChanged() }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {}
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        callback = cb
    }

    override fun onDestroy() {
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) {
            if (!Store.stayConnected(c)) return
            runCatching { c.startForegroundService(Intent(c, RippleService::class.java)) }
        }
        fun stop(c: Context) { c.stopService(Intent(c, RippleService::class.java)) }
    }
}

/** Notification buttons. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Ripple.init(context)
        when (intent.action) {
            SAVE -> {
                val fp = intent.getStringExtra("computer") ?: return
                val path = intent.getStringExtra("path") ?: return
                val name = intent.getStringExtra("name") ?: path.substringAfterLast('/')
                val pending = goAsync()
                Thread {
                    try { Transfers.downloadToDownloads(context, fp, path, name) } finally { pending.finish() }
                }.start()
                context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("notification", 0))
            }
        }
    }
    companion object { const val SAVE = "dev.wonderdesk.ripple.SAVE" }
}
