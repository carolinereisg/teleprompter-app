package com.serenidadelab.teleprompter

import android.content.Context
import android.text.SpannableStringBuilder
import java.io.File

/** Preferências e roteiro (o roteiro fica em arquivo, para aceitar textos grandes). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("teleprompter", Context.MODE_PRIVATE)
    private val scriptFile = File(context.filesDir, "roteiro.json")
    private val legacyFile = File(context.filesDir, "roteiro.txt")

    /** Roteiro com formatação. */
    fun loadScript(): SpannableStringBuilder = try {
        when {
            scriptFile.exists() -> ScriptFormat.decode(scriptFile.readText())
            legacyFile.exists() -> SpannableStringBuilder(legacyFile.readText())
            else -> SpannableStringBuilder()
        }
    } catch (_: Exception) {
        SpannableStringBuilder()
    }

    fun saveScript(text: CharSequence) {
        scriptFile.writeText(ScriptFormat.encode(text))
    }

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    var sourceName: String
        get() = sp.getString("sourceName", "") ?: ""
        set(v) = sp.edit().putString("sourceName", v).apply()

    /** Tamanho da letra em sp (16–96, de 2 em 2). */
    var fontSizeSp: Int
        get() = snap(int("font", 36), 16, 96, 2)
        set(v) = putInt("font", snap(v, 16, 96, 2))

    /** Velocidade em palavras por minuto (60–260, de 5 em 5). */
    var wpm: Int
        get() = snap(int("wpm", 130), 60, 260, 5)
        set(v) = putInt("wpm", snap(v, 60, 260, 5))

    /** Escurecimento do fundo do teleprompter, em % (0–90, de 5 em 5). */
    var opacity: Int
        get() = snap(int("opacity", 45), 0, 90, 5)
        set(v) = putInt("opacity", snap(v, 0, 90, 5))

    var boldAll: Boolean
        get() = bool("boldAll", false)
        set(v) = putBool("boldAll", v)

    var keepColors: Boolean
        get() = bool("keepColors", true)
        set(v) = putBool("keepColors", v)

    /** 0 = livre, 1 = 30 s, 2 = 1 min, 3 = 3 min, 4 = personalizado. */
    var durationMode: Int
        get() = int("durMode", 0)
        set(v) = putInt("durMode", v)

    var customSeconds: Int
        get() = int("customSec", 120)
        set(v) = putInt("customSec", v)

    var landscape: Boolean
        get() = bool("landscape", false)
        set(v) = putBool("landscape", v)

    /** true = tentar 4K; false = Full HD. */
    var uhd: Boolean
        get() = bool("uhd", false)
        set(v) = putBool("uhd", v)

    var frontCamera: Boolean
        get() = bool("front", true)
        set(v) = putBool("front", v)

    var countdown: Boolean
        get() = bool("countdown", true)
        set(v) = putBool("countdown", v)

    var showGuides: Boolean
        get() = bool("guides", true)
        set(v) = putBool("guides", v)

    var showSilhouette: Boolean
        get() = bool("silhouette", false)
        set(v) = putBool("silhouette", v)

    var gestureEnabled: Boolean
        get() = bool("gesture", true)
        set(v) = putBool("gesture", v)

    /** Botões de volume / controle Bluetooth: false = mexer na posição do texto, true = mudar a velocidade. */
    var remoteChangesSpeed: Boolean
        get() = bool("remoteSpeed", false)
        set(v) = putBool("remoteSpeed", v)

    /** Ajusta a velocidade sozinho depois de 3 correções no mesmo sentido. */
    var adaptivePace: Boolean
        get() = bool("adaptive", true)
        set(v) = putBool("adaptive", v)

    /** Limite de gravação em segundos (0 = sem limite). */
    val limitSeconds: Int
        get() = when (durationMode) {
            1 -> 30
            2 -> 60
            3 -> 180
            4 -> customSeconds
            else -> 0
        }

    private fun snap(v: Int, min: Int, max: Int, step: Int): Int {
        val c = v.coerceIn(min, max)
        return min + ((c - min + step / 2) / step) * step
    }
}
