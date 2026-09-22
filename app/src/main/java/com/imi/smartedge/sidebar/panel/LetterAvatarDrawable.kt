package com.imi.smartedge.sidebar.panel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/** Round avatar with the first letter of a name, used for contacts in the sidebar. */
class LetterAvatarDrawable(name: String) : Drawable() {

    private val letter = name.trim().firstOrNull()?.uppercase() ?: "#"
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFor(name) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val radius = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radius, circlePaint)
        textPaint.textSize = radius
        val textY = b.exactCenterY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter, b.exactCenterX(), textY, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        circlePaint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        circlePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private val PALETTE = intArrayOf(
            Color.parseColor("#5C6BC0"), Color.parseColor("#26A69A"), Color.parseColor("#EF5350"),
            Color.parseColor("#AB47BC"), Color.parseColor("#FFA726"), Color.parseColor("#42A5F5")
        )

        fun colorFor(name: String): Int = PALETTE[Math.floorMod(name.hashCode(), PALETTE.size)]
    }
}
