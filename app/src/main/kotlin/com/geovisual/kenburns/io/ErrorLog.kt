package com.geovisual.kenburns.io

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Appends failures to filesDir/errors.log (last ~50 KB kept) and returns the full text for the clipboard. */
object ErrorLog {
    private const val MAX_BYTES = 50 * 1024
    private val lock = Any()

    fun record(context: Context, what: String, error: Throwable): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = "[$stamp] $what\n${Log.getStackTraceString(error)}\n"
        try {
            synchronized(lock) {
                val f = file(context)
                f.appendText(text)
                if (f.length() > MAX_BYTES) {
                    val bytes = f.readBytes()
                    f.writeBytes(bytes.copyOfRange(bytes.size - MAX_BYTES, bytes.size))
                }
            }
        } catch (e: Exception) {
            Log.w("ErrorLog", "could not write errors.log", e)
        }
        return text
    }

    /** Whole log, or "" if there is none. */
    fun read(context: Context): String = try {
        synchronized(lock) {
            val f = file(context)
            if (f.isFile) f.readText() else ""
        }
    } catch (e: Exception) {
        "Could not read errors.log: ${e.javaClass.simpleName}: ${e.message}"
    }

    fun clear(context: Context) {
        try {
            synchronized(lock) { file(context).delete() }
        } catch (e: Exception) {
            Log.w("ErrorLog", "could not clear errors.log", e)
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "errors.log")
}
