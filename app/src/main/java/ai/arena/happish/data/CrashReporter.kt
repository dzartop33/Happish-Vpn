package ai.arena.happish.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private const val PREFS = "happish_crash_report"
    private const val KEY_LAST_CRASH = "last_crash"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val report = buildReport(thread.name, throwable)
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_CRASH, report)
                .apply()
            runCatching { AppLog.e("Критическая ошибка приложения", throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun lastCrash(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_CRASH, "")
        .orEmpty()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_CRASH).apply()
    }

    fun copy(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Happish crash report", lastCrash(context)))
    }

    fun share(context: Context) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Happish VPN crash report")
            putExtra(Intent.EXTRA_TEXT, lastCrash(context))
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться crash report"))
    }

    private fun buildReport(threadName: String, throwable: Throwable): String {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return """
            Happish VPN crash report
            Time: $time
            Thread: $threadName
            Error: ${throwable::class.java.name}: ${throwable.message}

            $stack
        """.trimIndent()
    }
}
