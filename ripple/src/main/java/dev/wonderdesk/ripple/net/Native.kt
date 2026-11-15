package dev.wonderdesk.ripple.net

/** Progress and status lines from the Rust core ("Ringing the doorbell…", "progress:1234/5678", "compare:123 456"). */
fun interface StatusCallback { fun onStatus(text: String) }

/**
 * The Rust core (libripple_jni): pairing, secure connections and streams. Every call blocks, so call
 * them from background threads. Failures throw RuntimeException with a message written for people.
 */
object Native {
    init { System.loadLibrary("ripple_jni") }

    const val KIND_JSON: Byte = 1
    const val KIND_DATA: Byte = 2

    @JvmStatic external fun init(dir: String, name: String): String
    @JvmStatic external fun discover(ms: Int): String
    @JvmStatic external fun pair(addr: String, pin: String?, code: String?, device: String, bell: String?, cb: StatusCallback?): String
    @JvmStatic external fun compareCode(serverFp: String): String
    @JvmStatic external fun connect(info: String, cb: StatusCallback?): Long
    @JvmStatic external fun connInfo(h: Long): String
    @JvmStatic external fun isAlive(h: Long): Boolean
    @JvmStatic external fun request(h: Long, json: String): String
    @JvmStatic external fun open(h: Long, json: String): Long
    @JvmStatic external fun streamReply(s: Long): String
    @JvmStatic external fun read(s: Long): ByteArray?
    @JvmStatic external fun writeData(s: Long, data: ByteArray, off: Int, len: Int): Boolean
    @JvmStatic external fun writeJson(s: Long, json: String): Boolean
    @JvmStatic external fun closeStream(s: Long)
    @JvmStatic external fun close(h: Long)
    @JvmStatic external fun upload(h: Long, fd: Int, req: String, cb: StatusCallback?): String
    @JvmStatic external fun download(h: Long, fd: Int, path: String, cb: StatusCallback?): Long
}
