package com.screentranslator.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * 번역 결과를 원문 글자 위치에 겹쳐 그리는 전체 화면 뷰. 아무 데나 누르면 [onDismiss]가 불린다.
 */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(
    context: Context,
    private val items: List<TranslationEngine.Item>,
    private val engineName: String,
    private val onDismiss: () -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val padding = 3 * density
    private val corner = 4 * density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 255, 250)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.rgb(26, 115, 232)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 33, 36)
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(4 * density, 0f, 0f, Color.BLACK)
    }

    private val location = IntArray(2)
    private var boxes: List<Pair<RectF, StaticLayout>>? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.argb(60, 0, 0, 0))

        // OCR 좌표는 화면 기준이라, 이 창이 화면에서 어디에 놓였는지만큼 되돌려 그린다.
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        for ((rect, layout) in boxes ?: buildBoxes().also { boxes = it }) {
            canvas.drawRoundRect(rect, corner, corner, boxPaint)
            canvas.drawRoundRect(rect, corner, corner, borderPaint)
            canvas.save()
            canvas.translate(rect.left + padding, rect.top + padding)
            layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()

        canvas.drawText("번역: $engineName · 화면을 누르면 닫혀요", width / 2f, height - 48 * density, hintPaint)
    }

    /**
     * 번역 글상자를 위에서부터 차례로 놓는다. 글자는 아래 글상자와 닿기 전까지의 공간에 맞춰 줄이고,
     * 그래도 이미 놓인 글상자와 겹치면 그 아래로 밀어서 서로 가리지 않게 한다.
     */
    private fun buildBoxes(): List<Pair<RectF, StaticLayout>> {
        val screenLeft = location[0].toFloat()
        val screenTop = location[1].toFloat()
        val screenRight = screenLeft + width
        val screenBottom = screenTop + height
        val gap = 2 * density
        val minWidth = 40 * density

        val sorted = items.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val placed = mutableListOf<Pair<RectF, StaticLayout>>()
        for ((index, item) in sorted.withIndex()) {
            val rect = RectF(item.bounds)
            rect.inset(-padding, -padding)
            val textWidth = (rect.width() - 2 * padding).coerceAtLeast(minWidth)
                .coerceAtMost(screenRight - screenLeft - 2 * padding).toInt()
            rect.right = rect.left + textWidth + 2 * padding
            if (rect.right > screenRight) rect.offset(screenRight - rect.right, 0f)
            if (rect.left < screenLeft) rect.offset(screenLeft - rect.left, 0f)

            // 바로 아래에 있는 다른 원문 글상자 위쪽까지가 이 번역이 쓸 수 있는 높이다.
            val below = sorted.drop(index + 1)
                .filter { it.bounds.top >= item.bounds.top && it.bounds.right > rect.left && it.bounds.left < rect.right }
                .minOfOrNull { it.bounds.top - padding - gap } ?: screenBottom
            val maxTextHeight = maxOf(rect.height(), below - rect.top) - 2 * padding

            // 원문 한 줄 높이에 맞춰 글자 크기를 정하고, 쓸 수 있는 높이를 넘치면 조금씩 줄인다.
            val lineHeight = item.bounds.height().toFloat() / item.lineCount
            var size = (lineHeight * 0.75f).coerceIn(sp(11f), sp(26f))
            var layout = makeLayout(item.translated, size, textWidth)
            while (layout.height > maxTextHeight && size > sp(8f)) {
                size -= density / 2
                layout = makeLayout(item.translated, size, textWidth)
            }
            rect.bottom = maxOf(rect.bottom, rect.top + layout.height + 2 * padding)

            // 그래도 앞서 놓은 글상자와 겹치면 그 아래로 내린다.
            var guard = placed.size + 1
            while (guard-- > 0) {
                val hit = placed.firstOrNull { RectF.intersects(it.first, rect) }?.first ?: break
                rect.offsetTo(rect.left, hit.bottom + gap)
            }
            if (rect.bottom > screenBottom) rect.offset(0f, screenBottom - rect.bottom)
            placed += rect to layout
        }
        return placed
    }

    private fun makeLayout(text: String, size: Float, width: Int): StaticLayout {
        val paint = TextPaint(textPaint).apply { textSize = size }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) onDismiss()
        return true
    }
}
