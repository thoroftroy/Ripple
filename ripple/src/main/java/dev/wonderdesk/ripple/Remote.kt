package dev.wonderdesk.ripple

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.wonderdesk.ripple.net.Native
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

data class SessionInfo(val id: Int, val name: String, val program: String, val cwd: String, val title: String, val idleSecs: Long, val attached: Int) {
    companion object {
        fun from(o: JSONObject) = SessionInfo(
            o.optInt("id"), o.optString("name"), o.optString("program"), o.optString("cwd"), o.optString("title"),
            o.optLong("idle_secs"), o.optInt("receivers")
        )
    }
}

sealed class LinkState {
    data object Idle : LinkState()
    data class Connecting(val step: String, val attempt: Int) : LinkState()
    data class Connected(val via: String) : LinkState()
    data class Offline(val message: String, val retryAt: Long) : LinkState()
}

/** Everything in the app that talks to computers goes through here. */
object Ripple {
    lateinit var app: Context
    private val links = HashMap<String, Link>()
    val main = Handler(Looper.getMainLooper())
    @Volatile var ready = false
    var fingerprint = ""

    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        val dir = java.io.File(app.filesDir, "identity").apply { mkdirs() }
        fingerprint = Native.init(dir.absolutePath, android.os.Build.MODEL ?: "Phone")
        ready = true
    }

    @Synchronized fun link(fp: String): Link? {
        links[fp]?.let { return it }
        val c = Store.computer(app, fp) ?: return null
        return Link(c).also { links[fp] = it }
    }

    @Synchronized fun active(): List<Link> = links.values.filter { it.wanted }

    @Synchronized fun forget(fp: String) {
        links.remove(fp)?.stop()
    }

    /** The phone switched networks: drop paths that can't follow and reconnect at once. */
    fun networkChanged() {
        synchronized(this) { links.values.toList() }.forEach { it.reconnectNow() }
    }
}

/** A live connection to one computer that reconnects by itself and keeps its terminals attached. */
class Link(var computer: Computer) {
    val state = MutableStateFlow<LinkState>(LinkState.Idle)
    val sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val terminals = mutableStateMapOf<Int, RemoteTerminal>()
    @Volatile var handle = 0L
    @Volatile var wanted = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    init {
        // Keep this computer's card on the Launcher's floating button true: on, asleep, what its sessions are doing.
        scope.launch { kotlinx.coroutines.flow.combine(state, sessions) { s, l -> s.toString() + l.joinToString { "${it.id}${it.idleSecs / 60}" } }.collect { delay(600); RippleAssist.refresh() } }
    }
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var watchStream = 0L
    var events: ((JSONObject) -> Unit)? = null

    fun start() {
        wanted = true
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        wanted = false
        job?.cancel()
        val h = handle
        handle = 0
        terminals.values.forEach { it.detachStream() }
        if (h != 0L) scope.launch { runCatching { Native.close(h) } }
        state.value = LinkState.Idle
    }

    fun reconnectNow() {
        if (!wanted) return
        val h = handle
        if (h != 0L) {
            // Closing makes the watch loop return, which reconnects straight away.
            scope.launch { runCatching { Native.close(h) } }
        } else wake.trySend(Unit)
    }

    private suspend fun loop() {
        var attempt = 0
        while (scope.isActive && wanted) {
            attempt++
            state.value = LinkState.Connecting("Starting…", attempt)
            val h = try {
                Native.connect(computer.json.toString()) { s -> state.value = LinkState.Connecting(s, attempt) }
            } catch (e: RuntimeException) {
                val wait = minOf(30, 2 shl minOf(attempt, 4)).toLong()
                state.value = LinkState.Offline(e.message ?: "Couldn't connect", System.currentTimeMillis() + wait * 1000)
                kotlinx.coroutines.withTimeoutOrNull(wait * 1000) { wake.receive() }
                continue
            }
            handle = h
            attempt = 0
            val via = runCatching { JSONObject(Native.connInfo(h)).optString("via") }.getOrDefault("")
            state.value = LinkState.Connected(via)
            Store.setSeenAt(Ripple.app, computer.fp, System.currentTimeMillis()); Store.setSleptAt(Ripple.app, computer.fp, 0L)
            refreshInfo(h)
            terminals.values.forEach { it.attach(h) }
            watch(h)
            handle = 0
            Store.setSeenAt(Ripple.app, computer.fp, System.currentTimeMillis())
            runCatching { Native.close(h) }
            terminals.values.forEach { it.detachStream() }
            if (wanted) state.value = LinkState.Connecting("Connection lost. Reconnecting…", 1)
            delay(400)
        }
    }

    private fun refreshInfo(h: Long) {
        runCatching {
            val r = JSONObject(Native.request(h, JSONObject().put("op", "info").toString()))
            val fresh = r.optJSONObject("computer")
            if (fresh != null && fresh.optString("fp") == computer.fp) {
                computer = Computer(fresh)
                Store.saveComputer(Ripple.app, computer)
            }
            r.optJSONArray("sessions")?.let { sessions.value = parse(it) }
        }
        // Let the computer's window match this phone's Wonderdesk look.
        runCatching {
            val look = dev.wonderdesk.shared.GlobalAppearance.read(Ripple.app)?.first?.name ?: "AQUA"
            Native.request(h, JSONObject().put("op", "set_look").put("look", look).toString())
        }
    }

    private fun parse(a: JSONArray) = (0 until a.length()).map { SessionInfo.from(a.getJSONObject(it)) }

    private fun watch(h: Long) {
        val s = try { Native.open(h, JSONObject().put("op", "watch").toString()) } catch (e: RuntimeException) { return }
        watchStream = s
        runCatching { JSONObject(Native.streamReply(s)).optJSONArray("sessions")?.let { sessions.value = parse(it) } }
        while (wanted) {
            val f = Native.read(s) ?: break
            if (f.isEmpty() || f[0] != Native.KIND_JSON) continue
            val ev = runCatching { JSONObject(String(f, 1, f.size - 1)) }.getOrNull() ?: continue
            when (ev.optString("event")) {
                "sessions" -> ev.optJSONArray("sessions")?.let { sessions.value = parse(it) }
                "clip" -> if (ev.optString("from") != "phone") ClipSync.fromComputer(Ripple.app, ev.optString("text"))
                "ended" -> {
                    val id = ev.optInt("session")
                    Ripple.main.post { terminals[id]?.ended(ev.optInt("code")) }
                }
            }
            events?.invoke(ev)
            Notifier.onEvent(this, ev)
        }
        runCatching { Native.closeStream(s) }
        watchStream = 0
    }

    // ---- requests (call from background threads)
    fun request(req: JSONObject): JSONObject {
        val h = handle
        if (h == 0L) throw RuntimeException("Not connected to ${computer.name}")
        return JSONObject(Native.request(h, req.toString()))
    }

    /** Opens (or returns) the terminal for a session. Main thread. */
    fun terminal(id: Int, name: String, client: TerminalSessionClient): RemoteTerminal {
        terminals[id]?.let { it.client.target = client; return it }
        val t = RemoteTerminal(this, id, name, ProxyClient(client))
        terminals[id] = t
        if (handle != 0L) t.attach(handle)
        return t
    }

    fun closeTerminal(id: Int) {
        terminals.remove(id)?.detachStream()
    }
}

/** Forwards to whichever screen currently shows the terminal. */
class ProxyClient(var target: TerminalSessionClient) : TerminalSessionClient by DelegateHolder.dummy {
    override fun onTextChanged(changedSession: TerminalSession) = target.onTextChanged(changedSession)
    override fun onTitleChanged(changedSession: TerminalSession) = target.onTitleChanged(changedSession)
    override fun onSessionFinished(finishedSession: TerminalSession) = target.onSessionFinished(finishedSession)
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = target.onCopyTextToClipboard(session, text)
    override fun onPasteTextFromClipboard(session: TerminalSession?) = target.onPasteTextFromClipboard(session)
    override fun onBell(session: TerminalSession) = target.onBell(session)
    override fun onColorsChanged(session: TerminalSession) = target.onColorsChanged(session)
    override fun onTerminalCursorStateChange(state: Boolean) = target.onTerminalCursorStateChange(state)
    override fun getTerminalCursorStyle(): Int? = target.terminalCursorStyle
}

object DelegateHolder {
    val dummy = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}

/** One session on the computer, shown in a terminal on the phone. */
class RemoteTerminal(val link: Link, val sessionId: Int, var name: String, val client: ProxyClient) {
    private val stream = AtomicLong(0)
    @Volatile var cols = 80
    @Volatile var rows = 24
    @Volatile var isEnded = false
    @Volatile var connected = false
    private var reader: Thread? = null
    var onState: (() -> Unit)? = null
    /** Keys typed while the connection is coming back; sent as soon as it is. */
    private val pending = java.io.ByteArrayOutputStream()
    /** When the current batch of unsent keys started piling up (0 when empty). */
    private var pendingSince = 0L
    /** Unsent typing older than this is dropped on reconnect: it belongs to a dead moment,
     *  and firing it would run stale commands the user can no longer see or delete. */
    private val pendingMaxAge = 60_000L

    /** All terminal output goes through here, in order, off the UI thread: a slow
     *  connection must never freeze typing, dragging or tapping. */
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ripple-send-$sessionId").apply { isDaemon = true }
    }
    private fun postSend(task: () -> Unit) {
        runCatching { sender.execute { try { task() } catch (_: Exception) { } } }
    }

    val session: TerminalSession = TerminalSession(object : TerminalSession.RemoteTransport {
        override fun send(data: ByteArray, offset: Int, count: Int) {
            // Copy: the caller's buffer is reused after we return.
            val buf = data.copyOfRange(offset, offset + count)
            postSend {
                if (isEnded) return@postSend
                val s = stream.get()
                if (s != 0L && runCatching { Native.writeData(s, buf, 0, buf.size) }.getOrDefault(false)) return@postSend
                if (!isEnded) synchronized(pending) {
                    if (pending.size() == 0) pendingSince = System.currentTimeMillis()
                    if (pending.size() + buf.size < 256 * 1024) pending.write(buf)
                }
            }
        }
        override fun resize(columns: Int, rows: Int) {
            cols = columns; this@RemoteTerminal.rows = rows
            val s = stream.get()
            if (s != 0L) postSend { runCatching { Native.writeJson(s, JSONObject().put("resize", JSONArray().put(columns).put(rows)).toString()) } }
        }
        override fun close() { detachStream() }
    }, Store.scrollback(Ripple.app), client)

    fun attach(h: Long) {
        if (isEnded || stream.get() != 0L) return
        reader = Thread({
            val s = try {
                Native.open(h, JSONObject().put("op", "attach").put("id", sessionId).put("cols", cols).put("rows", rows).put("history", true).toString())
            } catch (e: RuntimeException) {
                if (e.message?.contains("ended") == true) Ripple.main.post { ended(0) }
                return@Thread
            }
            stream.set(s)
            connected = true
            postSend {
                val (queued, stale) = synchronized(pending) {
                    val q = pending.toByteArray().also { pending.reset() }
                    val since = pendingSince; pendingSince = 0L
                    q to (q.isNotEmpty() && System.currentTimeMillis() - since > pendingMaxAge)
                }
                if (stale) Ripple.main.post {
                    android.widget.Toast.makeText(Ripple.app, "Connection was down a while so unsent typing was dropped", android.widget.Toast.LENGTH_LONG).show()
                } else if (queued.isNotEmpty()) {
                    if (!runCatching { Native.writeData(s, queued, 0, queued.size) }.getOrDefault(false)) synchronized(pending) {
                        if (pending.size() + queued.size < 256 * 1024) { if (pending.size() == 0) pendingSince = System.currentTimeMillis(); pending.write(queued) }
                    }
                }
            }
            Ripple.main.post { onState?.invoke() }
            while (true) {
                val f = Native.read(s) ?: break
                if (f.isEmpty()) continue
                if (f[0] == Native.KIND_DATA) {
                    session.feedRemote(f.copyOfRange(1, f.size), f.size - 1)
                } else {
                    val v = runCatching { JSONObject(String(f, 1, f.size - 1)) }.getOrNull()
                    if (v?.has("ended") == true) {
                        Ripple.main.post { ended(v.optInt("ended")) }
                        break
                    }
                }
            }
            stream.compareAndSet(s, 0)
            connected = false
            Ripple.main.post { onState?.invoke() }
        }, "ripple-term-$sessionId").apply { isDaemon = true; start() }
    }

    fun detachStream() {
        val s = stream.getAndSet(0)
        connected = false
        if (s != 0L) postSend { runCatching { Native.writeJson(s, "{\"detach\":true}"); Native.closeStream(s) } }
    }

    fun ended(code: Int) {
        if (isEnded) return
        isEnded = true
        runCatching { sender.shutdown() }
        session.remoteFinished(code)
        onState?.invoke()
    }

    /** Typed text from outside the keyboard (quick commands, paste). send() queues it in order. */
    fun type(text: String) {
        val b = text.toByteArray()
        Thread { session.write(b, 0, b.size) }.start()
    }
}

/** Why a computer can't be reached, in plain words: asleep (we put it there), or off, asleep or away from the network. */
object Presence {
    private fun ago(ms: Long): String { val m = ms / 60_000; return when { m < 1 -> "a moment ago"; m < 60 -> "$m min ago"; m < 48 * 60 -> "${m / 60} h ago"; else -> "${m / 1440} days ago" } }
    private fun clock(t: Long) = android.text.format.DateFormat.getTimeFormat(Ripple.app).format(java.util.Date(t))

    /** Headline and detail for an offline computer. */
    fun explain(c: Computer): Pair<String, String> {
        val slept = Store.sleptAt(Ripple.app, c.fp); val seen = Store.seenAt(Ripple.app, c.fp); val now = System.currentTimeMillis()
        return when {
            slept > 0 && now - slept < 24 * 3600_000L -> "${c.name} is asleep" to "Put to sleep from this phone at ${clock(slept)}. Tap Wake while you are on the same network, or press its power button."
            seen > 0 -> "${c.name} isn't reachable" to "It may be asleep, shut down or off the network. Last seen ${ago(now - seen)}."
            else -> "${c.name} isn't reachable" to "It may be asleep, shut down or off the network."
        }
    }
    fun short(c: Computer): String {
        val slept = Store.sleptAt(Ripple.app, c.fp); val seen = Store.seenAt(Ripple.app, c.fp); val now = System.currentTimeMillis()
        return if (slept > 0 && now - slept < 24 * 3600_000L) "Asleep since ${clock(slept)}" else if (seen > 0) "Not reachable · last seen ${ago(now - seen)}" else "Not reachable"
    }
}
