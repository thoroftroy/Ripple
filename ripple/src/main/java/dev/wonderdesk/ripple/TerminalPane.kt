package dev.wonderdesk.ripple

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.wonderdesk.shared.LocalLook
import dev.wonderdesk.shared.WonderLook
import dev.wonderdesk.shared.lighten
import dev.wonderdesk.shared.darken
import dev.wonderdesk.shared.wonderPalette
import java.util.Properties

/** Terminal colour schemes shared with the desktop app. */
object Schemes {
    data class Scheme(val label: String, val bg: String, val fg: String, val cursor: String, val palette: List<String>)

    val all = linkedMapOf(
        "glass" to Scheme("Aqua Glass", "#0e1a33", "#d8e4f7", "#5ac8fa", listOf("#3a4a66", "#ff6b62", "#30d158", "#ffd60a", "#0a84ff", "#bf5af2", "#5ac8fa", "#d8e4f7", "#6e7fa3", "#ff8a80", "#7ee787", "#ffe45e", "#5ac8fa", "#da8fff", "#70d7ff", "#ffffff")),
        "paper" to Scheme("Paper", "#ffffff", "#1c1c1c", "#1e7fe8", listOf("#000000", "#c23621", "#25bc24", "#adad27", "#2a5fdd", "#d338d3", "#33bbc8", "#cbcccd", "#818383", "#fc391f", "#31e722", "#c5b81c", "#3b6cff", "#f935f8", "#14c5d4", "#e9ebeb")),
        "midnight" to Scheme("Midnight", "#1e1f24", "#e8e8ea", "#6ea8ff", listOf("#1e1f24", "#ff6b61", "#5bc777", "#f2b33d", "#6ea8ff", "#c792ea", "#5ad4e6", "#d5dae0", "#6b7078", "#ff8a80", "#7ee09a", "#ffd479", "#94c1ff", "#ddb3ff", "#8ae7f2", "#ffffff")),
        "phosphor" to Scheme("Phosphor", "#000000", "#28fe14", "#28fe14", listOf("#000000", "#990000", "#00a600", "#999900", "#0000b2", "#b200b2", "#00a6b2", "#bfbfbf", "#666666", "#e50000", "#00d900", "#e5e500", "#0000ff", "#e500e5", "#00e5e5", "#e5e5e5")),
        "ocean" to Scheme("Ocean", "#1c3f86", "#ffffff", "#7dd3ff", listOf("#1c3f86", "#ff7a7a", "#7ee08f", "#ffe17a", "#9cc4ff", "#f0a6ff", "#8be9fd", "#e6ecf5", "#6d8ac7", "#ff9a9a", "#a2f0ae", "#fff0a8", "#c1dbff", "#f7c8ff", "#b3f3ff", "#ffffff")),
        "parchment" to Scheme("Parchment", "#f6efd9", "#3b2f22", "#8b5a2b", listOf("#3b2f22", "#a8322b", "#4f7b2a", "#9a6b11", "#2f5a9e", "#8b3f8b", "#2f7f86", "#d9cfb6", "#7d6e5a", "#c4473f", "#6a9a3c", "#b9861d", "#4b78c2", "#a85ea8", "#45a0a8", "#fbf6e6")),
        "neon" to Scheme("Neon", "#0d0a1a", "#f2ecff", "#00f0ff", listOf("#0d0a1a", "#ff4f7b", "#3cffa8", "#ffce68", "#00b3ff", "#ff2e9a", "#00f0ff", "#d8ccff", "#5b3fa8", "#ff7d9c", "#7dffc4", "#ffe09c", "#4dd0ff", "#ff6ec0", "#7df9ff", "#ffffff")),
        "bliss" to Scheme("Bliss", "#fefefe", "#000000", "#0058e6", listOf("#000000", "#c1311f", "#3c9c3c", "#d08a00", "#0058e6", "#8f3fb0", "#1b8fae", "#ece9d8", "#7f9db9", "#e2483a", "#52c152", "#f2a900", "#3c82ff", "#b45fd6", "#3db3d6", "#ffffff")),
    )

    fun forLook(look: WonderLook) = when (look) {
        WonderLook.AQUA -> "glass"; WonderLook.BLISS -> "bliss"; WonderLook.DARK -> "midnight"; WonderLook.SUPER -> "neon"
    }

    fun resolve(c: Context, look: WonderLook): Scheme {
        val key = Store.scheme(c).let { if (it == "auto") forLook(look) else it }
        return all[key] ?: all.getValue("glass")
    }

    private var applied: Scheme? = null

    fun apply(s: Scheme, sessions: Collection<TerminalSession>) {
        if (applied != s) {
            val p = Properties()
            p["foreground"] = s.fg; p["background"] = s.bg; p["cursor"] = s.cursor
            s.palette.forEachIndexed { i, c -> p["color$i"] = c }
            TerminalColors.COLOR_SCHEME.updateWith(p)
            applied = s
        }
        sessions.forEach { it.emulator?.mColors?.reset() }
    }
}

/** Keys that soft keyboards lack. CTRL and ALT stick until the next key. */
class Modifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    fun consumeCtrl() = ctrl.also { if (it) ctrl = false }
    fun consumeAlt() = alt.also { if (it) alt = false }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun TerminalPane(
    session: TerminalSession?,
    modifier: Modifier = Modifier,
    mods: Modifiers,
    onViewReady: (TerminalView) -> Unit = {},
    onTap: () -> Unit = {},
) {
    val context = LocalContext.current
    val look = LocalLook.current
    val scheme = remember(look, Store.scheme(context)) { Schemes.resolve(context, look) }
    var fontPx by remember { mutableIntStateOf((Store.fontSize(context) * context.resources.displayMetrics.scaledDensity).toInt()) }
    val vibrate = remember { Store.vibrateKeys(context) }
    Box(modifier.background(Color(android.graphics.Color.parseColor(scheme.bg)))) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(horizontal = 3.dp),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setTextSize(fontPx)
                    setShowKeyboardSuggestions(Store.keyboardSuggestions(ctx)); setAutoKeyboardSuggestions(Store.autoSuggestions(ctx))
                    setTypeface(Typeface.MONOSPACE)
                    keepScreenOn = Store.keepScreenOn(ctx)
                    val view = this
                    setTerminalViewClient(object : TerminalViewClient {
                        override fun onScale(scale: Float): Float {
                            if (scale < 0.9f || scale > 1.1f) {
                                val next = (fontPx * (if (scale > 1f) 1.08f else 0.93f)).toInt().coerceIn(12, 120)
                                if (next != fontPx) {
                                    fontPx = next
                                    view.setTextSize(next)
                                    Store.setFontSize(ctx, (next / ctx.resources.displayMetrics.scaledDensity).toInt())
                                }
                                return 1.0f
                            }
                            return scale
                        }
                        override fun onSingleTapUp(e: MotionEvent) {
                            onTap()
                            view.requestFocus()
                            // Interactive apps with mouse tracking get the tap as a click
                            // through onUp. Forcing the keyboard there covers the menu
                            // the tap just opened, so leave it closed. The keyboard
                            // button in the extra keys row opens it when typing is needed.
                            val mouse = view.mEmulator?.isMouseTrackingActive == true
                            if (!mouse) ctx.getSystemService(InputMethodManager::class.java).showSoftInput(view, 0)
                        }
                        override fun shouldBackButtonBeMappedToEscape() = false
                        override fun shouldEnforceCharBasedInput() = true
                        override fun shouldUseCtrlSpaceWorkaround() = false
                        override fun isTerminalViewSelected() = true
                        override fun copyModeChanged(copyMode: Boolean) {}
                        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
                            if (vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            return false
                        }
                        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
                        override fun onLongPress(event: MotionEvent) = false
                        override fun readControlKey() = mods.consumeCtrl()
                        override fun readAltKey() = mods.consumeAlt()
                        override fun readShiftKey() = false
                        override fun readFnKey() = false
                        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
                            if (vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            return false
                        }
                        override fun onEmulatorSet() {
                            Schemes.apply(scheme, listOfNotNull(view.currentSession))
                            view.setTerminalCursorBlinkerRate(600)
                            view.setTerminalCursorBlinkerState(true, true)
                        }
                        override fun logError(tag: String?, message: String?) {}
                        override fun logWarn(tag: String?, message: String?) {}
                        override fun logInfo(tag: String?, message: String?) {}
                        override fun logDebug(tag: String?, message: String?) {}
                        override fun logVerbose(tag: String?, message: String?) {}
                        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                        override fun logStackTrace(tag: String?, e: Exception?) {}
                    })
                    // No custom touch handling here on purpose. TerminalView already
                    // scrolls the transcript, pinches to zoom and sends taps as mouse
                    // clicks when the app asks for them. Any extra layer that sends
                    // arrow keys on drags turns scrolls into history searches.
                    onViewReady(this)
                }
            },
            update = { v ->
                v.setShowKeyboardSuggestions(Store.keyboardSuggestions(context)); v.setAutoKeyboardSuggestions(Store.autoSuggestions(context))
                if (session != null && v.currentSession !== session) {
                    v.attachSession(session)
                    Schemes.apply(scheme, listOf(session))
                    v.onScreenUpdated()
                    v.requestFocus()
                }
                Schemes.apply(scheme, listOfNotNull(session))
                v.invalidate()
            }
        )
    }
}

/** Client used by every terminal screen: clipboard, bell and redraws. */
class ScreenClient(private val context: Context, private val viewProvider: () -> TerminalView?) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        // Output arriving from the computer sends a ring through Ripple's scene.
        dev.wonderdesk.shared.SceneFeed.beat()
        val v = viewProvider() ?: return
        if (v.currentSession === changedSession) v.onScreenUpdated()
    }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) { viewProvider()?.invalidate() }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text == null) return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        session?.emulator?.paste(text)
    }
    override fun onBell(session: TerminalSession) {
        if (Store.bellVibrate(context)) runCatching { context.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(40, 80)) }
    }
    override fun onColorsChanged(session: TerminalSession) { viewProvider()?.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = Store.cursorStyle(context)
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

/** The row of extra keys above the keyboard. */
@Composable
fun ExtraKeys(view: () -> TerminalView?, mods: Modifiers, onPaste: () -> Unit, onKeyboard: () -> Unit) {
    val p = wonderPalette()
    val look = LocalLook.current
    val keyShape = RoundedCornerShape(if (look == WonderLook.BLISS) 3.dp else 7.dp)
    fun send(code: Int) {
        val v = view() ?: return
        var meta = 0
        if (mods.consumeCtrl()) meta = meta or com.termux.terminal.KeyHandler.KEYMOD_CTRL
        if (mods.consumeAlt()) meta = meta or com.termux.terminal.KeyHandler.KEYMOD_ALT
        v.handleKeyCode(code, meta)
    }
    fun text(s: String) {
        val v = view() ?: return
        val sess = v.currentSession ?: return
        if (mods.ctrl || mods.alt) {
            val ctrl = mods.consumeCtrl(); val alt = mods.consumeAlt()
            v.inputCodePoint(0, s.codePointAt(0), ctrl, alt)
        } else sess.write(s)
    }
    @Composable
    fun Key(label: String, active: Boolean = false, weight: Float = 1f, onClick: () -> Unit) {
        val bg = when {
            active -> Brush.verticalGradient(listOf(p.accent.lighten(.3f), p.accent, p.accent.darken(.15f)))
            p.dark -> Brush.verticalGradient(listOf(Color(0xFF4A5059), Color(0xFF33373E)))
            else -> Brush.verticalGradient(listOf(Color.White, Color(0xFFE4E6EA), Color(0xFFD4D7DC)))
        }
        val v = androidx.compose.ui.platform.LocalView.current
        Box(
            Modifier.padding(horizontal = 2.dp, vertical = 3.dp).height(38.dp).widthIn(min = 42.dp).clip(keyShape).background(bg)
                .border(1.dp, if (active) p.accent.darken(.3f) else p.fieldBorder, keyShape)
                .clickable { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() }.padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = if (active) Color.White else p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    Row(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(p.toolbar)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Key("Esc") { send(KeyEvent.KEYCODE_ESCAPE) }
        Key("Tab") { send(KeyEvent.KEYCODE_TAB) }
        Key("⇧Tab") { view()?.currentSession?.write("\u001b[Z") }
        Key("Ctrl", active = mods.ctrl) { mods.ctrl = !mods.ctrl }
        Key("Alt", active = mods.alt) { mods.alt = !mods.alt }
        Key("←") { send(KeyEvent.KEYCODE_DPAD_LEFT) }
        Key("↓") { send(KeyEvent.KEYCODE_DPAD_DOWN) }
        Key("↑") { send(KeyEvent.KEYCODE_DPAD_UP) }
        Key("→") { send(KeyEvent.KEYCODE_DPAD_RIGHT) }
        Key("⏎") { send(KeyEvent.KEYCODE_ENTER) }
        Key("^C") { view()?.currentSession?.write(byteArrayOf(3), 0, 1) }
        Key("|") { text("|") }
        Key("~") { text("~") }
        Key("/") { text("/") }
        Key("-") { text("-") }
        Key("Home") { send(KeyEvent.KEYCODE_MOVE_HOME) }
        Key("End") { send(KeyEvent.KEYCODE_MOVE_END) }
        Key("PgUp") { send(KeyEvent.KEYCODE_PAGE_UP) }
        Key("PgDn") { send(KeyEvent.KEYCODE_PAGE_DOWN) }
        Key("Paste") { onPaste() }
        Key("⌨") { onKeyboard() }
    }
}

private fun TerminalSession.write(s: String) {
    val b = s.toByteArray()
    write(b, 0, b.size)
}
