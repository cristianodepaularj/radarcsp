package com.radarcsp

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log simples em memória (últimos 100 eventos). Nunca guarda o conteúdo da tela. */
object RadarLog {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(msg: String) {
        lines.add(0, "[" + fmt.format(Date()) + "] " + msg)
        while (lines.size > 100) lines.removeAt(lines.size - 1)
    }

    @Synchronized
    fun all(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }
}

/** Liga/desliga o radar (padrão: ligado). */
object RadarPrefs {
    private const val FILE = "radar"
    private const val KEY_ON = "on"

    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setOn(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, value).apply()
    }
}
