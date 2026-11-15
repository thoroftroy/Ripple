package dev.wonderdesk.ripple.shell

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File

/**
 * `wd` on the phone's command line. Runs as its own small process (started through app_process) and
 * forwards the request to the Ripple app, which can use Android's APIs on its behalf.
 */
object Wd {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress("ripple-wd-" + android.os.Process.myUid(), LocalSocketAddress.Namespace.ABSTRACT))
            val req = org.json.JSONObject()
                .put("args", org.json.JSONArray(args.toList()))
                .put("cwd", System.getenv("PWD") ?: File(".").absolutePath)
            s.outputStream.write((req.toString() + "\n").toByteArray())
            s.outputStream.flush()
            val reader = s.inputStream.bufferedReader()
            var exit = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("\u0000EXIT:")) { exit = line.removePrefix("\u0000EXIT:").toIntOrNull() ?: 0; break }
                println(line)
            }
            s.close()
            exit
        } catch (e: Exception) {
            System.err.println("wd: Ripple isn't running. Open the Android command line in Ripple and try again.")
            1
        }
        System.out.flush()
        System.exit(code)
    }
}
