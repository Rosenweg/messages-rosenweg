package org.fossify.messages.rosenweg

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * WhatsApp-artiges Zitat in der Sprechblase: abgesetzter, abgerundeter Kasten mit
 * farbigem Balken links, Name fett und farbig, 1–2 Zeilen Text; die eigene Antwort
 * folgt darunter normal. Alles als Spans im selben TextView (die Sprechblase ist
 * dessen Hintergrund), damit die Fossify-Layouts fuer SMS unveraendert bleiben.
 */
class RwZitatSpan(
    private val balkenFarbe: Int,
    private val kastenFarbe: Int,
    private val balkenBreite: Int,
    private val abstand: Int,
    private val radius: Float,
) : LineBackgroundSpan, LeadingMarginSpan {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getLeadingMargin(first: Boolean) = balkenBreite + abstand

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) = Unit

    override fun drawBackground(
        canvas: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int,
    ) {
        val sp = text as? Spanned ?: return
        val spanStart = sp.getSpanStart(this)
        val spanEnd = sp.getSpanEnd(this)
        val ersteZeile = start <= spanStart
        val letzteZeile = end >= spanEnd
        val r = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val oben = if (ersteZeile) radius else 0f
        val unten = if (letzteZeile) radius else 0f
        val ecken = floatArrayOf(oben, oben, oben, oben, unten, unten, unten, unten)
        paint.color = kastenFarbe
        canvas.drawPath(Path().apply { addRoundRect(r, ecken, Path.Direction.CW) }, paint)
        paint.color = balkenFarbe
        val balken = RectF(left.toFloat(), top.toFloat(), (left + balkenBreite).toFloat(), bottom.toFloat())
        val bEcken = floatArrayOf(oben, oben, 0f, 0f, 0f, 0f, unten, unten)
        canvas.drawPath(Path().apply { addRoundRect(balken, bEcken, Path.Direction.CW) }, paint)
        paint.color = p.color
    }
}

object RwZitat {
    /**
     * Text einer Rosenweg-Sprechblase aufbauen: optional Absendername (Gruppen),
     * Zitat-Kasten, eigentlicher Text. textFarbe = Schriftfarbe der Sprechblase.
     */
    fun baue(tv: TextView, teile: RwTeile, absender: String?, textFarbe: Int, akzent: Int): CharSequence {
        val dp = tv.resources.displayMetrics.density
        val sb = SpannableStringBuilder()
        if (!absender.isNullOrEmpty()) {
            sb.append(absender, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append('\n')
        }
        if (teile.zitatText != null) {
            val start = sb.length
            val name = teile.zitatName
            if (name != null) {
                val ns = sb.length
                sb.append(name)
                sb.setSpan(StyleSpan(Typeface.BOLD), ns, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(akzent), ns, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append('\n')
            }
            val ts = sb.length
            sb.append(teile.zitatText.take(110))
            sb.setSpan(ForegroundColorSpan(ColorUtils.setAlphaComponent(textFarbe, 0xC0)), ts, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AbsoluteSizeSpan((tv.textSize * 0.88f).toInt()), ts, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Kasten: etwas dunkler bzw. heller als die Sprechblase
            val kasten = ColorUtils.setAlphaComponent(if (ColorUtils.calculateLuminance(textFarbe) > 0.5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0x22)
            sb.setSpan(
                RwZitatSpan(akzent, kasten, (3 * dp).toInt(), (8 * dp).toInt(), 6 * dp),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append('\n')
            // kleiner Abstand zwischen Kasten und Antwort
            val ls = sb.length
            sb.append(" \n")
            sb.setSpan(AbsoluteSizeSpan((tv.textSize * 0.35f).toInt()), ls, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append(teile.text)
        return sb
    }

    /** Hintergrund des Reaktions-Chips (abgerundet, mit Rand) */
    fun chipHintergrund(fuellung: Int, rand: Int, dp: Float) = GradientDrawable().apply {
        cornerRadius = 12 * dp
        setColor(fuellung)
        setStroke((1 * dp).toInt(), rand)
    }
}
