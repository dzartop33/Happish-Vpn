package ai.arena.happish.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_LINES = 250
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(message: String) = add("INFO", message)
    fun w(message: String) = add("WARN", message)
    fun e(message: String, throwable: Throwable? = null) {
        add("ERROR", buildString {
            append(message)
            throwable?.message?.let { append(": ").append(it) }
        })
    }

    fun allText(): String = _lines.value.joinToString("\n")

    fun clear() {
        _lines.value = emptyList()
    }

    private fun add(level: String, message: String) {
        val time = formatter.format(Date())
        val line = "[$time] $level  $message"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
    }
}
