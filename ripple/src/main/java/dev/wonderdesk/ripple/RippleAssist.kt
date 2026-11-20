package dev.wonderdesk.ripple

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.wonderdesk.shared.WonderAssist
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ripple on the Wonderdesk Launcher's floating button: one card per computer showing whether it is on, what it is doing and what its
 * terminal sessions are up to, with buttons for the things you reach for (wake it, lock it, sleep it, start a session), plus a card for
 * each alert a session raises. Cards are only shown while the floating button is in use; the notifications are unchanged.
 */
object RippleAssist {
    private const val KEY = "ripple"
    const val WAKE = "dev.wonderdesk.ripple.ASSIST_WAKE"
    const val SLEEP = "dev.wonderdesk.ripple.ASSIST_SLEEP"
    const val LOCK = "dev.wonderdesk.ripple.ASSIST_LOCK"
    const val SESSION = "dev.wonderdesk.ripple.ASSIST_SESSION"
    const val CONNECT = "dev.wonderdesk.ripple.ASSIST_CONNECT"
    const val DISCONNECT = "dev.wonderdesk.ripple.ASSIST_DISCONNECT"

    private val usage = HashMap<String, Usage>()
    @Volatile private var lastRefresh = 0L
    @Volatile private var poller: Thread? = null

    private fun pi(c: Context, fp: String, act: String): PendingIntent =
        PendingIntent.getBroadcast(c, (fp + act).hashCode(), Intent(c, AssistActionReceiver::class.java).setAction(act).putExtra("fp", fp), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun idle(secs: Long) = when { secs < 60 -> "active"; secs < 3600 -> "idle ${secs / 60} min"; else -> "idle ${secs / 3600} h" }

    /** Posts (or updates) the card of every paired computer. Cheap to call often. */
    fun refresh(c: Context = Ripple.app) {
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 400) return
        lastRefresh = now
        runCatching {
            Store.computers(c).forEach { comp -> card(c, comp) }
            startPoller(c)
        }
    }

    private fun card(c: Context, comp: Computer) {
        val link = Ripple.link(comp.fp)
        val st = link?.state?.value ?: LinkState.Idle
        val connected = st is LinkState.Connected
        val lines = ArrayList<String>()
        lines += when (st) {
            is LinkState.Connected -> "On · connected" + st.via.takeIf { it.isNotBlank() }?.let { " via $it" }.orEmpty()
            is LinkState.Connecting -> st.step
            is LinkState.Offline -> Presence.short(comp)
            LinkState.Idle -> if (Store.seenAt(c, comp.fp) > 0 || Store.sleptAt(c, comp.fp) > 0) Presence.short(comp) else "Not connected"
        }
        if (connected) usage[comp.fp]?.let { u -> lines += "Processor ${u.cpu}%, memory ${u.memPct}%" + (u.temp?.let { ", $it°C" } ?: "") }
        val sessions = link?.sessions?.value.orEmpty()
        if (connected) lines += if (sessions.isEmpty()) "No terminal sessions" else sessions.take(3).joinToString("  ·  ") { s -> (s.name.ifBlank { s.program.ifBlank { "Session ${s.id}" } }) + " " + idle(s.idleSecs) } + if (sessions.size > 3) "  +${sessions.size - 3} more" else ""
        val open = PendingIntent.getActivity(c, comp.fp.hashCode(), Intent(c, MainActivity::class.java).putExtra("computer", comp.fp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val actions = if (connected) listOf("New session" to pi(c, comp.fp, SESSION), "Lock" to pi(c, comp.fp, LOCK), "Sleep" to pi(c, comp.fp, SLEEP), "Disconnect" to pi(c, comp.fp, DISCONNECT))
                      else listOf("Wake" to pi(c, comp.fp, WAKE), "Connect" to pi(c, comp.fp, CONNECT))
        WonderAssist.post(c, KEY, "computer-${comp.fp}", comp.name, lines.joinToString("\n"), actions, open, ongoing = true)
    }

    /** While a computer is connected, read its usage every 15 seconds so the card stays true. */
    private fun startPoller(c: Context) {
        if (poller?.isAlive == true) return
        poller = Thread {
            while (true) {
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { return@Thread }
                val live = Ripple.active().filter { it.state.value is LinkState.Connected }
                if (live.isEmpty() && Store.computers(c).isEmpty()) continue
                live.forEach { l -> runCatching { usage[l.computer.fp] = Usage.from(l.request(JSONObject().put("op", "stats"))) } }
                lastRefresh = 0; refresh(c)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** A session raised an alert: also a card, so it can be opened from the button. Goes away by itself after a while. */
    fun alert(c: Context, fp: String, session: Int, title: String, text: String, open: PendingIntent) {
        WonderAssist.post(c, KEY, "alert-$fp-$session", title, text, listOf("Open" to open), open, ongoing = false, expireMs = 20 * 60_000L)
    }

    fun fileOffer(c: Context, id: Int, title: String, text: String, save: PendingIntent) {
        WonderAssist.post(c, KEY, "file-$id", title, text, listOf("Save" to save), save, ongoing = false, expireMs = 30 * 60_000L)
    }
}

/** The buttons on Ripple's cards. */
class AssistActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Ripple.init(c)
        val fp = i.getStringExtra("fp") ?: return
        val comp = Store.computer(c, fp) ?: return
        val link = Ripple.link(fp)
        val pending = goAsync()
        Thread {
            try {
                when (i.action) {
                    RippleAssist.WAKE -> Wake.send(comp)
                    RippleAssist.CONNECT -> { link?.start(); RippleService.start(c) }
                    RippleAssist.DISCONNECT -> { link?.stop(); RippleService.stop(c) }
                    RippleAssist.LOCK -> link?.ask(JSONObject().put("op", "power").put("action", "lock"))
                    RippleAssist.SLEEP -> link?.ask(JSONObject().put("op", "power").put("action", "sleep"))?.let { r -> if (r.isSuccess) Store.setSleptAt(c, fp, System.currentTimeMillis()) }
                    RippleAssist.SESSION -> link?.request(JSONObject().put("op", "new").put("cwd", comp.home.ifBlank { "~" }).put("cols", 80).put("rows", 30))
                }
            } finally { RippleAssist.refresh(c); pending.finish() }
        }.start()
    }
}

/** Asked by the Launcher after it starts, to get the cards back. */
class AssistRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Ripple.init(c); RippleAssist.refresh(c) }
}

/**
 * One tap, one note: makes a new note in a Ripple notepad (so it syncs to your computer) and opens it in Scribble. Used by the floating
 * button's New note when it is set to make Ripple notes. Only apps signed like Ripple may start it.
 */
class QuickNoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ripple.init(this)
        val comp = Store.computer(this, Store.quickFp(this).orEmpty()) ?: Store.computers(this).firstOrNull()
        if (comp == null) { android.widget.Toast.makeText(this, "Pair a computer in Ripple first", android.widget.Toast.LENGTH_LONG).show(); finish(); return }
        if (!android.os.Environment.isExternalStorageManager()) {
            android.widget.Toast.makeText(this, "Ripple notes need all files access: allow it in Ripple, Notepads", android.widget.Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).putExtra("computer", comp.fp).putExtra("open", "notepads").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); return
        }
        Thread {
            val name = "Quick note " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())
            val remote = Store.quickRemote(this).ifBlank { (comp.home.ifBlank { "~" }) + "/Notes" }
            val n = runCatching { NoteLinks.create(this, comp.fp, name, NoteLinks.defaultFolder(), remote) }.getOrNull()
            runOnUiThread {
                if (n == null || !NoteLinks.openInScribble(this, n)) android.widget.Toast.makeText(this, "Couldn't open the new note", android.widget.Toast.LENGTH_LONG).show()
                finish(); overridePendingTransition(0, 0)
            }
        }.start()
    }
}

/** Commands from the Launcher's floating button that don't need a card first. Only apps signed like Ripple may send them. */
class AssistCommandReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Ripple.init(c)
        val comp = Store.computer(c, Store.quickFp(c).orEmpty()) ?: Store.computers(c).firstOrNull() ?: return
        if (i.action == "dev.wonderdesk.ripple.CMD_WAKE") { val p = goAsync(); Thread { try { Wake.send(comp) } finally { RippleAssist.refresh(c); p.finish() } }.start() }
    }
}
