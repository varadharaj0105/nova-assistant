package com.varadha.nova

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.math.min

/** Nova's "face": a soft colourful orb that swirls while listening or thinking. */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private var angle = 0f
    private var level = 0f

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    /** Swirl on/off. */
    var active = false
        set(value) {
            field = value
            if (value && !spin.isStarted) spin.start()
            if (!value) {
                spin.cancel()
                level = 0f
                invalidate()
            }
        }

    /** Voice loudness 0..1 makes the orb breathe. */
    fun setLevel(l: Float) {
        level = level * 0.6f + l * 0.4f
        invalidate()
    }

    private fun color(attr: Int) = MaterialColors.getColor(this, attr, Color.MAGENTA)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val p = color(com.google.android.material.R.attr.colorPrimary)
        val t = color(com.google.android.material.R.attr.colorTertiary)
        val s = color(com.google.android.material.R.attr.colorSecondary)
        ring.shader = SweepGradient(cx, cy, intArrayOf(p, t, s, p), null)
        glow.shader = RadialGradient(cx, cy, min(w, h) / 2f, intArrayOf(withAlpha(p, 90), withAlpha(p, 0)), null, Shader.TileMode.CLAMP)
        core.shader = RadialGradient(cx, cy * 0.85f, min(w, h) / 2.2f, intArrayOf(withAlpha(Color.WHITE, 170), withAlpha(Color.WHITE, 0)), null, Shader.TileMode.CLAMP)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val max = min(width, height) / 2f
        val r = max * (0.72f + 0.12f * level)
        canvas.drawCircle(cx, cy, max * (0.9f + 0.1f * level), glow)
        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.restore()
        canvas.drawCircle(cx, cy, r, core)
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }
}
