package dev.wonderdesk.ripple

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A paired computer, kept as the JSON the core understands (addresses, identity, doorbell). */
data class Computer(val json: JSONObject) {
    val fp: String get() = json.optString("fp")
    val name: String get() = json.optString("name", "Computer")
    val home: String get() = json.optString("home")
    val user: String get() = json.optString("user")
    val sharedDir: String get() = json.optString("shared_dir")
    val hasDoorbell: Boolean get() = json.optJSONObject("doorbell") != null
    val addresses: List<String> get() = json.optJSONArray("addrs")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
}

data class QuickCommand(val label: String, val command: String, val folder: String)

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("ripple", Context.MODE_PRIVATE)

    fun computers(c: Context): List<Computer> {
        val raw = prefs(c).getString("computers", "[]") ?: "[]"
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { Computer(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    }

    fun saveComputer(c: Context, computer: Computer) {
        val list = computers(c).filter { it.fp != computer.fp }.toMutableList()
        val old = computers(c).indexOfFirst { it.fp == computer.fp }
        if (old >= 0) list.add(old.coerceAtMost(list.size), computer) else list.add(computer)
        prefs(c).edit().putString("computers", JSONArray(list.map { it.json }).toString()).apply()
    }

    fun removeComputer(c: Context, fp: String) {
        prefs(c).edit().putString("computers", JSONArray(computers(c).filter { it.fp != fp }.map { it.json }).toString()).apply()
    }

    fun computer(c: Context, fp: String) = computers(c).firstOrNull { it.fp == fp }

    // ---- settings
    fun fontSize(c: Context) = prefs(c).getInt("font_size", 13)
    fun setFontSize(c: Context, v: Int) = prefs(c).edit().putInt("font_size", v.coerceIn(6, 40)).apply()
    fun scheme(c: Context) = prefs(c).getString("scheme", "auto") ?: "auto"
    fun setScheme(c: Context, v: String) = prefs(c).edit().putString("scheme", v).apply()
    fun stayConnected(c: Context) = prefs(c).getBoolean("stay_connected", true)
    fun setStayConnected(c: Context, v: Boolean) = prefs(c).edit().putBoolean("stay_connected", v).apply()
    fun keepScreenOn(c: Context) = prefs(c).getBoolean("keep_screen_on", true)
    fun setKeepScreenOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("keep_screen_on", v).apply()
    fun vibrateKeys(c: Context) = prefs(c).getBoolean("vibrate_keys", true)
    fun setVibrateKeys(c: Context, v: Boolean) = prefs(c).edit().putBoolean("vibrate_keys", v).apply()
    /** Keyboard suggestions and autocorrect in the terminal. Off by default: a "fixed" command runs the wrong thing. */
    fun keyboardSuggestions(c: Context) = prefs(c).getBoolean("keyboard_suggestions", false)
    fun setKeyboardSuggestions(c: Context, v: Boolean) = prefs(c).edit().putBoolean("keyboard_suggestions", v).apply()
    /** Autocorrect switches on by itself inside full screen apps (chats), stays off at the shell. */
    fun autoSuggestions(c: Context) = prefs(c).getBoolean("auto_suggestions", true)
    fun setAutoSuggestions(c: Context, v: Boolean) = prefs(c).edit().putBoolean("auto_suggestions", v).apply()
    fun extraKeys(c: Context) = prefs(c).getBoolean("extra_keys", true)
    fun setExtraKeys(c: Context, v: Boolean) = prefs(c).edit().putBoolean("extra_keys", v).apply()
    fun alerts(c: Context) = prefs(c).getBoolean("alerts", true)
    fun setAlerts(c: Context, v: Boolean) = prefs(c).edit().putBoolean("alerts", v).apply()
    fun lastComputer(c: Context) = prefs(c).getString("last_computer", null)
    fun setLastComputer(c: Context, fp: String?) = prefs(c).edit().putString("last_computer", fp).apply()
    /** The tab last on screen for each computer, so reopening Ripple resumes it instead of jumping to another. */
    fun lastSession(c: Context, fp: String) = prefs(c).getInt("last_session_$fp", -1)
    fun setLastSession(c: Context, fp: String, id: Int) = prefs(c).edit().putInt("last_session_$fp", id).apply()

    fun quickCommands(c: Context): List<QuickCommand> {
        val raw = prefs(c).getString("quick", "[]") ?: "[]"
        return runCatching {
            JSONArray(raw).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> QuickCommand(o.optString("label"), o.optString("command"), o.optString("folder")) } } }
        }.getOrDefault(emptyList())
    }

    fun saveQuickCommands(c: Context, list: List<QuickCommand>) {
        prefs(c).edit().putString("quick", JSONArray(list.map { JSONObject().put("label", it.label).put("command", it.command).put("folder", it.folder) }).toString()).apply()
    }

    /** Folders recently used for new sessions, newest first. */
    fun recentFolders(c: Context, fp: String): List<String> =
        prefs(c).getString("folders_$fp", "")!!.split('\n').filter { it.isNotBlank() }

    fun rememberFolder(c: Context, fp: String, folder: String) {
        if (folder.isBlank()) return
        val list = (listOf(folder) + recentFolders(c, fp).filter { it != folder }).take(8)
        prefs(c).edit().putString("folders_$fp", list.joinToString("\n")).apply()
    }
}
