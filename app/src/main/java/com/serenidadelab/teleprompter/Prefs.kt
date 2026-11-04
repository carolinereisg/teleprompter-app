package com.serenidadelab.teleprompter

import android.content.Context
import java.io.File

/** Guarda as preferências e o texto do roteiro (em arquivo, para aceitar textos grandes). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("teleprompter", Context.MODE_PRIVATE)
    private val scriptFile = File(context.filesDir, "roteiro.txt")

    var script: String
        get() = if (scriptFile.exists()) scriptFile.readText() else ""
        set(value) = scriptFile.writeText(value)

    /** Tamanho da letra em sp (16–96). */
    var fontSizeSp: Int
        get() = sp.getInt("font", 36)
        set(v) = sp.edit().putInt("font", v.coerceIn(16, 96)).apply()

    /** Velocidade de rolagem (1–20). */
    var speed: Int
        get() = sp.getInt("speed", 5)
        set(v) = sp.edit().putInt("speed", v.coerceIn(1, 20)).apply()

    /** Escurecimento do fundo do teleprompter, em % (0–90). */
    var opacity: Int
        get() = sp.getInt("opacity", 45)
        set(v) = sp.edit().putInt("opacity", v.coerceIn(0, 90)).apply()

    /** 0 = livre, 1 = 30 s, 2 = 1 min, 3 = 3 min, 4 = personalizado. */
    var durationMode: Int
        get() = sp.getInt("durMode", 0)
        set(v) = sp.edit().putInt("durMode", v).apply()

    var customSeconds: Int
        get() = sp.getInt("customSec", 120)
        set(v) = sp.edit().putInt("customSec", v).apply()

    var gestureEnabled: Boolean
        get() = sp.getBoolean("gesture", true)
        set(v) = sp.edit().putBoolean("gesture", v).apply()

    var countdown: Boolean
        get() = sp.getBoolean("countdown", true)
        set(v) = sp.edit().putBoolean("countdown", v).apply()

    var frontCamera: Boolean
        get() = sp.getBoolean("front", true)
        set(v) = sp.edit().putBoolean("front", v).apply()

    var landscape: Boolean
        get() = sp.getBoolean("landscape", false)
        set(v) = sp.edit().putBoolean("landscape", v).apply()

    /** Limite de gravação em segundos (0 = sem limite). */
    val limitSeconds: Int
        get() = when (durationMode) {
            1 -> 30
            2 -> 60
            3 -> 180
            4 -> customSeconds
            else -> 0
        }
}
