package com.serenidadelab.teleprompter

import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guarda o roteiro com a formatação (negrito, itálico, sublinhado, cores,
 * marca-texto, tamanhos relativos e centralização) em JSON.
 */
object ScriptFormat {

    fun encode(text: CharSequence): String {
        val arr = JSONArray()
        if (text is Spanned) {
            for (span in text.getSpans(0, text.length, Any::class.java)) {
                if (text.getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0) continue
                val s = text.getSpanStart(span)
                val e = text.getSpanEnd(span)
                if (s < 0 || e <= s) continue
                val o = JSONObject().put("s", s).put("e", e)
                when (span) {
                    is StyleSpan -> when (span.style) {
                        Typeface.BOLD -> o.put("t", "b")
                        Typeface.ITALIC -> o.put("t", "i")
                        Typeface.BOLD_ITALIC -> o.put("t", "bi")
                        else -> continue
                    }
                    is UnderlineSpan -> o.put("t", "u")
                    is StrikethroughSpan -> o.put("t", "x")
                    is ForegroundColorSpan -> o.put("t", "fg").put("v", span.foregroundColor)
                    is BackgroundColorSpan -> o.put("t", "bg").put("v", span.backgroundColor)
                    is RelativeSizeSpan -> o.put("t", "rs").put("v", span.sizeChange.toDouble())
                    is AlignmentSpan -> when (span.alignment) {
                        Layout.Alignment.ALIGN_CENTER -> o.put("t", "ac")
                        Layout.Alignment.ALIGN_OPPOSITE -> o.put("t", "ar")
                        else -> continue
                    }
                    else -> continue
                }
                arr.put(o)
            }
        }
        return JSONObject().put("text", text.toString()).put("spans", arr).toString()
    }

    fun decode(json: String): SpannableStringBuilder {
        val root = JSONObject(json)
        val sb = SpannableStringBuilder(root.optString("text"))
        val arr = root.optJSONArray("spans") ?: return sb
        val len = sb.length
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val s = j.optInt("s").coerceIn(0, len)
            val e = j.optInt("e").coerceIn(0, len)
            if (e <= s) continue
            val span: Any = when (j.optString("t")) {
                "b" -> StyleSpan(Typeface.BOLD)
                "i" -> StyleSpan(Typeface.ITALIC)
                "bi" -> StyleSpan(Typeface.BOLD_ITALIC)
                "u" -> UnderlineSpan()
                "x" -> StrikethroughSpan()
                "fg" -> ForegroundColorSpan(j.optInt("v"))
                "bg" -> BackgroundColorSpan(j.optInt("v"))
                "rs" -> RelativeSizeSpan(j.optDouble("v", 1.0).toFloat())
                "ac" -> AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)
                "ar" -> AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE)
                else -> continue
            }
            sb.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /**
     * Adapta o texto para fundo escuro: letras pretas/escuras viram brancas,
     * e texto com marca-texto claro fica preto para continuar legível.
     */
    fun forDarkBackground(src: CharSequence, keepColors: Boolean, boldAll: Boolean): SpannableStringBuilder {
        val out = SpannableStringBuilder(src)
        for (fg in out.getSpans(0, out.length, ForegroundColorSpan::class.java)) {
            if (!keepColors || isDark(fg.foregroundColor)) out.removeSpan(fg)
        }
        for (bg in out.getSpans(0, out.length, BackgroundColorSpan::class.java)) {
            if (!keepColors) {
                out.removeSpan(bg)
            } else if (!isDark(bg.backgroundColor)) {
                out.setSpan(
                    ForegroundColorSpan(Color.BLACK),
                    out.getSpanStart(bg), out.getSpanEnd(bg), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        if (boldAll && out.isNotEmpty()) {
            out.setSpan(StyleSpan(Typeface.BOLD), 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        return out
    }

    private fun isDark(color: Int): Boolean {
        val lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return lum < 100
    }

    /** Converte HTML copiado (Word, Google Docs, navegador) em texto formatado. */
    fun fromHtml(html: String): CharSequence {
        // O Google Docs envolve tudo num <b style="font-weight:normal">, que deixaria tudo em negrito
        val cleaned = html.replace(Regex("<b[^>]*docs-internal-guid[^>]*>"), "")
        val spanned = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_COMPACT)
        return trimTrailing(SpannableStringBuilder(spanned))
    }

    fun trimTrailing(sb: SpannableStringBuilder): SpannableStringBuilder {
        while (sb.isNotEmpty() && sb[sb.length - 1].isWhitespace()) sb.delete(sb.length - 1, sb.length)
        return sb
    }

    fun wordCount(text: CharSequence): Int =
        text.split(Regex("\\s+")).count { it.isNotBlank() }
}
