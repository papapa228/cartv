package com.bmwflow.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val FLOW_BG: Int = 0xFF070A12.toInt()

/**
 * Весь интерфейс нарисован в «единицах» макета 1600×600 и масштабируется
 * под реальный экран, поэтому не зависит от плотности (dpi) прошивки.
 */
class Stage(val s: Float) {
    fun px(v: Float): Int = (v * s).roundToInt()
    fun pxf(v: Float): Float = v * s

    companion object {
        const val W = 1600f
        const val H = 600f

        fun forActivity(a: Activity): Stage {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            a.windowManager.defaultDisplay.getRealMetrics(dm)
            val w = max(dm.widthPixels, dm.heightPixels).toFloat()
            val h = min(dm.widthPixels, dm.heightPixels).toFloat()
            return Stage(min(w / W, h / H))
        }
    }
}

fun FrameLayout.place(child: View, st: Stage, x: Float, y: Float, w: Float, h: Float) {
    val lp = FrameLayout.LayoutParams(st.px(w), st.px(h))
    lp.gravity = Gravity.TOP or Gravity.START
    lp.leftMargin = st.px(x)
    lp.topMargin = st.px(y)
    addView(child, lp)
}

fun TextView.sizeU(st: Stage, u: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, st.pxf(u))

object Fonts {
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
}

fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

/** Смешивает c1 и c2; t — доля c1. */
fun blend(c1: Int, c2: Int, t: Float): Int {
    val r = (Color.red(c1) * t + Color.red(c2) * (1 - t)).roundToInt()
    val g = (Color.green(c1) * t + Color.green(c2) * (1 - t)).roundToInt()
    val b = (Color.blue(c1) * t + Color.blue(c2) * (1 - t)).roundToInt()
    return Color.rgb(r, g, b)
}

/** Плитка: тёмная заливка с цветным оттенком, мягкий цветной контур и лёгкое свечение. */
fun tileBackground(st: Stage, color: Int): Drawable {
    val radius = st.pxf(24f)
    val stroke = maxOf(1, st.px(2f))
    val glow = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius + stroke * 2
        setColor(Color.TRANSPARENT)
        setStroke(stroke * 3, withAlpha(color, 0x2E))
    }
    val dark = 0xFF0A0D16.toInt()
    val body = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(blend(color, dark, 0.26f), dark, blend(color, dark, 0.12f))
    ).apply {
        cornerRadius = radius
        setStroke(stroke, withAlpha(color, 0xC8))
    }
    return LayerDrawable(arrayOf(glow, body)).apply {
        val inset = stroke * 2
        setLayerInset(1, inset, inset, inset, inset)
    }
}

fun roundedFill(st: Stage, color: Int, radiusU: Float, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = st.pxf(radiusU)
        setColor(color)
        if (strokeColor != Color.TRANSPARENT) setStroke(maxOf(1, st.px(1f)), strokeColor)
    }

/** Лёгкая анимация нажатия; клики продолжают работать. */
fun View.pressAnimation() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        false
    }
}

@Suppress("DEPRECATION")
fun Activity.goImmersive() {
    window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN)
}
