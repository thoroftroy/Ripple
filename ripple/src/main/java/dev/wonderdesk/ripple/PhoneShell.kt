package dev.wonderdesk.ripple

import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.wonderdesk.ripple.shell.WdServer
import dev.wonderdesk.shared.*
import java.io.File

/** Shells on the phone itself. They live as long as the app does. */
object PhoneShells {
    val sessions = mutableStateListOf<TerminalSession>()
    val proxy = ProxyClient(DelegateHolder.dummy)

    private fun setup(c: Context): Array<String> {
        // Canonical path, so it matches what the shell reports as the working directory.
        val home = File(c.filesDir, "home").apply { mkdirs() }.canonicalFile
        val bin = File(c.filesDir, "bin").apply { mkdirs() }
        val rc = File(home, ".mkshrc")
        if (!rc.exists() || !rc.readText().contains("ripple-rc-5")) rc.writeText(
            """
            # ripple-rc-5: settings for the Android command line. Edit freely; delete to restore defaults.
            # Android doesn't let apps run programs from their own storage, so wd is a shell function.
            wd() { /system/bin/app_process -Djava.class.path="${'$'}RIPPLE_APK" /system/bin dev.wonderdesk.ripple.shell.Wd "${'$'}@"; }
            e=${'$'}(printf '\033')
            _rp_host=${'$'}(getprop ro.product.model | tr ' ' '-')
            _rp_pwd() { case "${'$'}PWD" in "${'$'}HOME"*) echo "~${'$'}{PWD#${'$'}HOME}";; *) echo "${'$'}PWD";; esac; }
            # mksh: the first character, followed by a carriage return, marks colour codes as invisible.
            d=${'$'}(printf '\001'); r=${'$'}(printf '\r')
            PS1="${'$'}d${'$'}r${'$'}d${'$'}{e}[1;36m${'$'}d${'$'}_rp_host${'$'}d${'$'}{e}[0m${'$'}d:${'$'}d${'$'}{e}[1;34m${'$'}d"'${'$'}(_rp_pwd)'"${'$'}d${'$'}{e}[0m${'$'}d${'$'} "
            alias ls='ls --color=auto'
            alias ll='ls -la --color=auto'
            alias la='ls -A --color=auto'
            alias ..='cd ..'
            HISTFILE=${'$'}HOME/.mksh_history
            HISTSIZE=5000
            [ -f ${'$'}HOME/.profile ] && . ${'$'}HOME/.profile
            """.trimIndent() + "\n"
        )
        // Start from the app's own environment (it carries BOOTCLASSPATH and friends, which
        // app_process needs for wd), then set up a normal shell environment on top.
        val env = HashMap(System.getenv())
        env.putAll(mapOf(
            "HOME" to home.path, "TMPDIR" to c.cacheDir.path, "PREFIX" to c.filesDir.path,
            "PATH" to "${bin.path}:/system/bin:/system/xbin:/vendor/bin:/product/bin",
            "TERM" to "xterm-256color", "COLORTERM" to "truecolor", "LANG" to "en_US.UTF-8",
            "ENV" to rc.path, "RIPPLE_APK" to c.applicationInfo.sourceDir,
        ))
        for (k in listOf("ANDROID_DATA" to "/data", "ANDROID_ROOT" to "/system", "EXTERNAL_STORAGE" to "/sdcard")) env.putIfAbsent(k.first, k.second)
        return env.filter { it.value.isNotEmpty() }.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    fun new(c: Context): TerminalSession {
        WdServer.start(c)
        val env = setup(c)
        val home = File(c.filesDir, "home").canonicalPath
        val s = TerminalSession("/system/bin/sh", home, arrayOf("-sh"), env, Store.scrollback(c), proxy)
        sessions.add(s)
        return s
    }
}

@Composable
fun PhoneShellScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var termView by remember { mutableStateOf<TerminalView?>(null) }
    val client = remember { ScreenClient(context) { termView } }
    LaunchedEffect(Unit) { PhoneShells.proxy.target = client }
    val mods = remember { Modifiers() }
    var current by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { if (PhoneShells.sessions.isEmpty()) PhoneShells.new(context) }
    // Drop shells that have exited.
    val finishedWatcher = remember {
        object : com.termux.terminal.TerminalSessionClient by client {
            override fun onSessionFinished(finishedSession: TerminalSession) {
                PhoneShells.sessions.remove(finishedSession)
                if (PhoneShells.sessions.isEmpty()) onBack()
            }
        }
    }
    LaunchedEffect(Unit) { PhoneShells.proxy.target = finishedWatcher }
    val session = PhoneShells.sessions.getOrNull(current.coerceAtMost(PhoneShells.sessions.lastIndex.coerceAtLeast(0)))
    WonderApp(
        appName = "Ripple", about = "Ripple",
        title = android.os.Build.MODEL ?: "This phone",
        subtitle = "Android command line · type wd help",
        onBack = onBack,
        actions = { ToolbarButton(WonderIcons.Add, "New shell", { PhoneShells.new(context); current = PhoneShells.sessions.lastIndex }) },
        menu = PhoneShells.sessions.mapIndexed { i, _ -> WonderMenuItem("Shell ${i + 1}" + if (i == current) " (open)" else "") { current = i } },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (session != null) key(session) { TerminalPane(session, Modifier.fillMaxSize(), mods, onViewReady = { termView = it }) }
                else LoadingPane("Starting the shell…")
            }
            if (session != null && Store.extraKeys(context)) ExtraKeys({ termView }, mods,
                onPaste = {
                    val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (text != null) session.emulator?.paste(text)
                },
                onKeyboard = { termView?.let { v -> v.requestFocus(); context.getSystemService(InputMethodManager::class.java).toggleSoftInput(0, 0) } })
        }
    }
}
