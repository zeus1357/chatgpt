package io.github.zeus1357.btwatch

import android.content.Context
import android.content.SharedPreferences

/** 감지 기록과 감시 켜짐 상태를 SharedPreferences에 보관한다. */
object EventLog {
    const val PREFS = "btwatch"
    const val KEY_LOG = "log"
    private const val KEY_ENABLED = "enabled"
    private const val MAX_ENTRIES = 100

    data class Entry(val timeMillis: Long, val name: String, val size: Long, val path: String)

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    @Synchronized
    fun add(context: Context, entry: Entry) {
        val lines = listOf(encode(entry)) + rawLines(context)
        prefs(context).edit().putString(KEY_LOG, lines.take(MAX_ENTRIES).joinToString("\n")).apply()
    }

    fun clear(context: Context) = prefs(context).edit().remove(KEY_LOG).apply()

    /** 최신 항목이 먼저 온다. */
    fun entries(context: Context): List<Entry> = rawLines(context).mapNotNull(::decode)

    private fun rawLines(context: Context): List<String> =
        prefs(context).getString(KEY_LOG, "").orEmpty().split('\n').filter { it.isNotEmpty() }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun encode(e: Entry) = "${e.timeMillis}\t${e.size}\t${clean(e.name)}\t${clean(e.path)}"

    private fun decode(line: String): Entry? {
        val p = line.split('\t')
        if (p.size != 4) return null
        return Entry(p[0].toLongOrNull() ?: return null, p[2], p[1].toLongOrNull() ?: return null, p[3])
    }
}
