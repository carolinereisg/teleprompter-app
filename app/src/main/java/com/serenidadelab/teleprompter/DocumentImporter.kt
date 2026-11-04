package com.serenidadelab.teleprompter

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
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
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** Importa arquivos .docx (Word) e .txt preservando a formatação. */
object DocumentImporter {

    class Result(val text: SpannableStringBuilder, val name: String)

    fun import(context: Context, uri: Uri): Result {
        val name = displayName(context, uri) ?: "arquivo"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Não foi possível abrir o arquivo.")
        val text = when {
            bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() -> readDocx(bytes)
            bytes.size >= 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() ->
                throw IllegalArgumentException(
                    "Este é um .doc antigo. No Word, use \"Salvar como\" .docx e importe de novo."
                )
            else -> SpannableStringBuilder(decodeText(bytes))
        }
        return Result(ScriptFormat.trimTrailing(text), name)
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    // ---------------- TXT ----------------
    private fun decodeText(bytes: ByteArray): String {
        val s = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                // .txt salvo no Windows em português costuma vir neste formato
                String(bytes, Charset.forName("windows-1252"))
            }
        }
        return s.replace("\r\n", "\n").replace('\r', '\n')
    }

    // ---------------- DOCX ----------------
    private class Props(
        var bold: Boolean? = null,
        var italic: Boolean? = null,
        var underline: Boolean? = null,
        var strike: Boolean? = null,
        var color: Int? = null,
        var highlight: Int? = null,
        var size: Int? = null,
        var basedOn: String? = null,
        var rStyle: String? = null,
    )

    private class Styles(val defaultSize: Int, val map: Map<String, Props>, val defaultParaStyle: String?) {
        fun resolve(id: String?): Props {
            val out = Props()
            var cur = id
            var guard = 0
            while (cur != null && guard++ < 12) {
                val p = map[cur] ?: break
                if (out.bold == null) out.bold = p.bold
                if (out.italic == null) out.italic = p.italic
                if (out.underline == null) out.underline = p.underline
                if (out.strike == null) out.strike = p.strike
                if (out.color == null) out.color = p.color
                if (out.highlight == null) out.highlight = p.highlight
                if (out.size == null) out.size = p.size
                cur = p.basedOn
            }
            return out
        }
    }

    private fun readDocx(bytes: ByteArray): SpannableStringBuilder {
        var docXml: ByteArray? = null
        var stylesXml: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "word/document.xml" -> docXml = zis.readBytes()
                    "word/styles.xml" -> stylesXml = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        val doc = docXml ?: throw IllegalArgumentException("Arquivo Word inválido.")
        val st = stylesXml
        val styles = if (st != null) parseStyles(st) else Styles(24, emptyMap(), null)
        return parseDocument(doc, styles)
    }

    private fun newParser(xml: ByteArray): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(xml), "UTF-8")
        return p
    }

    private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

    private fun onOff(v: String?): Boolean = when (v?.lowercase()) {
        null, "1", "true", "on" -> true
        else -> false
    }

    private fun hexColor(v: String?): Int? {
        if (v == null || v.equals("auto", true) || v.length != 6) return null
        return v.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
    }

    private fun highlightColor(v: String?): Int? {
        val hex = when (v) {
            "yellow" -> "FFFF00"; "green" -> "00FF00"; "cyan" -> "00FFFF"
            "magenta" -> "FF00FF"; "blue" -> "0000FF"; "red" -> "FF0000"
            "darkBlue" -> "000080"; "darkCyan" -> "008080"; "darkGreen" -> "008000"
            "darkMagenta" -> "800080"; "darkRed" -> "800000"; "darkYellow" -> "808000"
            "darkGray" -> "808080"; "lightGray" -> "C0C0C0"; "white" -> "FFFFFF"
            "black" -> "000000"
            else -> return null
        }
        return hexColor(hex)
    }

    /** Lê propriedades de formatação (w:b, w:i, w:sz…) para [target]. */
    private fun readRunProp(p: XmlPullParser, target: Props) {
        when (p.name) {
            "w:b" -> target.bold = onOff(p.attr("w:val"))
            "w:i" -> target.italic = onOff(p.attr("w:val"))
            "w:u" -> target.underline = p.attr("w:val")?.let { it != "none" } ?: true
            "w:strike" -> target.strike = onOff(p.attr("w:val"))
            "w:color" -> target.color = hexColor(p.attr("w:val"))
            "w:highlight" -> target.highlight = highlightColor(p.attr("w:val"))
            "w:shd" -> if (target.highlight == null) target.highlight = hexColor(p.attr("w:fill"))
            "w:sz" -> target.size = p.attr("w:val")?.toIntOrNull()
            "w:rStyle" -> target.rStyle = p.attr("w:val")
        }
    }

    private fun parseStyles(xml: ByteArray): Styles {
        val p = newParser(xml)
        val map = HashMap<String, Props>()
        var defaultSize = 24
        var defaultPara: String? = null
        var inDefaults = false
        var inRPr = false
        var cur: Props? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "w:rPrDefault" -> inDefaults = true
                    "w:style" -> {
                        val id = p.attr("w:styleId")
                        cur = Props()
                        if (id != null) map[id] = cur
                        if (p.attr("w:type") == "paragraph" && onOff(p.attr("w:default")) &&
                            p.attr("w:default") != null
                        ) defaultPara = id
                    }
                    "w:basedOn" -> cur?.basedOn = p.attr("w:val")
                    "w:rPr" -> inRPr = true
                    else -> if (inRPr) {
                        if (inDefaults) {
                            if (p.name == "w:sz") p.attr("w:val")?.toIntOrNull()?.let { defaultSize = it }
                        } else {
                            cur?.let { readRunProp(p, it) }
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "w:rPrDefault" -> inDefaults = false
                    "w:style" -> cur = null
                    "w:rPr" -> inRPr = false
                }
            }
            ev = p.next()
        }
        return Styles(defaultSize, map, defaultPara)
    }

    // Ignora: conteúdo duplicado de compatibilidade, texto excluído (revisão),
    // códigos de campo e caixas de texto (que bagunçariam a ordem dos parágrafos).
    private val SKIP = setOf(
        "mc:Fallback", "w:del", "w:instrText", "w:delText", "w:footnoteReference", "w:txbxContent"
    )

    private fun parseDocument(xml: ByteArray, styles: Styles): SpannableStringBuilder {
        val p = newParser(xml)
        val sb = SpannableStringBuilder()
        val baseSize = styles.resolve(styles.defaultParaStyle).size ?: styles.defaultSize

        var skip = 0
        var inPPr = false
        var inRPr = false
        var inRun = false
        var inText = false

        var paraStart = 0
        var paraStyle: String? = null
        var paraAlign: String? = null
        var paraList = false
        var bulletDone = false

        var runStart = 0
        var run = Props()

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = p.name
                    if (n in SKIP) {
                        skip++
                    } else if (skip == 0) {
                        when (n) {
                            "w:p" -> {
                                paraStart = sb.length; paraStyle = null; paraAlign = null
                                paraList = false; bulletDone = false
                            }
                            "w:pPr" -> inPPr = true
                            "w:rPr" -> inRPr = true
                            "w:pStyle" -> if (inPPr) paraStyle = p.attr("w:val")
                            "w:jc" -> if (inPPr && !inRPr) paraAlign = p.attr("w:val")
                            "w:numPr" -> if (inPPr) paraList = true
                            "w:r" -> {
                                if (paraList && !bulletDone) {
                                    sb.append("•  "); bulletDone = true
                                }
                                inRun = true; runStart = sb.length; run = Props()
                            }
                            "w:t" -> if (inRun) inText = true
                            "w:tab" -> if (inRun && !inPPr) sb.append("    ")
                            "w:br", "w:cr" -> if (inRun) sb.append("\n")
                            "w:noBreakHyphen" -> if (inRun) sb.append("-")
                            else -> if (inRun && inRPr) readRunProp(p, run)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inText && skip == 0) sb.append(p.text)
                XmlPullParser.END_TAG -> {
                    val n = p.name
                    if (n in SKIP) {
                        if (skip > 0) skip--
                    } else if (skip == 0) {
                        when (n) {
                            "w:t" -> inText = false
                            "w:pPr" -> inPPr = false
                            "w:rPr" -> inRPr = false
                            "w:r" -> {
                                inRun = false
                                applyRun(sb, runStart, sb.length, run, paraStyle, styles, baseSize)
                            }
                            "w:p" -> {
                                val end = sb.length
                                when (paraAlign) {
                                    "center" -> sb.setSpan(
                                        AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                                        paraStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                    )
                                    "right", "end" -> sb.setSpan(
                                        AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE),
                                        paraStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                    )
                                }
                                sb.append("\n")
                            }
                        }
                    }
                }
            }
            ev = p.next()
        }
        return sb
    }

    private fun applyRun(
        sb: SpannableStringBuilder, start: Int, end: Int, run: Props,
        paraStyle: String?, styles: Styles, baseSize: Int,
    ) {
        if (end <= start) return
        val ps = styles.resolve(paraStyle ?: styles.defaultParaStyle)
        val rs = styles.resolve(run.rStyle)
        val bold = run.bold ?: rs.bold ?: ps.bold ?: false
        val italic = run.italic ?: rs.italic ?: ps.italic ?: false
        val underline = run.underline ?: rs.underline ?: ps.underline ?: false
        val strike = run.strike ?: rs.strike ?: ps.strike ?: false
        val color = run.color ?: rs.color ?: ps.color
        val highlight = run.highlight ?: rs.highlight
        val size = run.size ?: rs.size ?: ps.size ?: baseSize
        val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        when {
            bold && italic -> sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, f)
            bold -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, f)
            italic -> sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, f)
        }
        if (underline) sb.setSpan(UnderlineSpan(), start, end, f)
        if (strike) sb.setSpan(StrikethroughSpan(), start, end, f)
        if (color != null) sb.setSpan(ForegroundColorSpan(color), start, end, f)
        if (highlight != null) sb.setSpan(BackgroundColorSpan(highlight), start, end, f)
        if (baseSize > 0) {
            val rel = size.toFloat() / baseSize
            if (abs(rel - 1f) > 0.05f) sb.setSpan(RelativeSizeSpan(rel.coerceIn(0.6f, 2.5f)), start, end, f)
        }
    }
}
