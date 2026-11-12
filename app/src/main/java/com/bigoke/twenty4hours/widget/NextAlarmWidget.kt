package com.bigoke.twenty4hours.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.widget.RemoteViews
import com.bigoke.twenty4hours.MainActivity
import com.bigoke.twenty4hours.R
import com.bigoke.twenty4hours.data.Alarm
import com.bigoke.twenty4hours.data.AlarmStore
import com.bigoke.twenty4hours.data.Stores
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Home-screen widget: a mini 24-hour dial with the next alarm beside it. */
class NextAlarmWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.update(context)
    }
}

object WidgetUpdater {
    private const val AMBER = 0xFFFF8A1F.toInt()
    private const val AMBER_LIGHT = 0xFFFFB25E.toInt()
    private const val AMBER_DARK = 0xFFE56A0A.toInt()
    private const val CREAM_LIGHT = 0xFFFFF1D9.toInt()
    private const val CREAM_DARK = 0xFFD9B98A.toInt()
    private const val TRACK = 0xFF1C1A18.toInt()
    private const val LINE = 0xFF3A3633.toInt()

    fun update(ctx: Context) {
        try {
            Stores.init(ctx)
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NextAlarmWidget::class.java))
            if (ids.isEmpty()) return

            val now = ZonedDateTime.now()
            val alarms = AlarmStore.alarms.value
            val enabled = alarms.filter { it.enabled }
            val next = enabled.minByOrNull { it.nextTrigger(now).toEpochSecond() }
            val nextAt = next?.nextTrigger(now)

            val views = RemoteViews(ctx.packageName, R.layout.widget_next_alarm)
            views.setImageViewBitmap(R.id.widget_dial, renderDial(alarms, next, now, 300))
            if (next != null && nextAt != null) {
                val days = nextAt.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay()
                val day = when (days) {
                    0L -> "Today"
                    1L -> "Tomorrow"
                    else -> nextAt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                }
                views.setTextViewText(R.id.widget_small, if (next.isReminder) "NEXT REMINDER" else "NEXT")
                views.setTextViewText(R.id.widget_label, next.displayLabel)
                views.setTextViewText(R.id.widget_time, next.timeText)
                views.setTextViewText(R.id.widget_sub, "$day · ${enabled.size} on")
            } else {
                views.setTextViewText(R.id.widget_small, "NO ALARMS")
                views.setTextViewText(R.id.widget_label, "Your day is free")
                views.setTextViewText(R.id.widget_time, "--:--")
                views.setTextViewText(R.id.widget_sub, "Tap to add one")
            }
            val open = PendingIntent.getActivity(
                ctx, 900, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            mgr.updateAppWidget(ids, views)
        } catch (_: Exception) {
        }
    }

    private fun pt(cx: Float, cy: Float, r: Float, deg: Float): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        return (cx + r * sin(a).toFloat()) to (cy - r * cos(a).toFloat())
    }

    private fun renderDial(alarms: List<Alarm>, next: Alarm?, now: ZonedDateTime, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = px / 2f
        val cy = px / 2f
        val r = px * 0.36f
        val tw = px * 0.1f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // outer line + ticks
        p.style = Paint.Style.STROKE; p.strokeWidth = px * 0.006f; p.color = LINE
        c.drawCircle(cx, cy, r + tw * 0.95f, p)
        p.style = Paint.Style.FILL
        for (h in 0 until 24 step 3) {
            val (x, y) = pt(cx, cy, r + tw * 0.95f, h * 15f)
            p.color = 0xFF8C8681.toInt(); c.drawCircle(x, y, px * 0.008f, p)
        }
        // track
        p.style = Paint.Style.STROKE; p.strokeWidth = tw; p.color = TRACK
        c.drawCircle(cx, cy, r, p)
        // inner disc
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy - r * 0.3f, r, 0xFF221E1A.toInt(), 0xFF110F0E.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r - tw / 2f - px * 0.02f, p)
        p.shader = null

        // arc now → next
        val nowMin = now.hour * 60 + now.minute
        if (next != null) {
            val mins = java.time.Duration.between(now, next.nextTrigger(now)).toMinutes()
            if (mins in 1..1440) {
                val ra = r + tw * 0.3f
                val oval = RectF(cx - ra, cy - ra, cx + ra, cy + ra)
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
                p.strokeWidth = px * 0.045f; p.color = AMBER; p.alpha = 50
                p.maskFilter = BlurMaskFilter(px * 0.02f, BlurMaskFilter.Blur.NORMAL)
                c.drawArc(oval, nowMin / 4f - 90f, mins / 4f, false, p)
                p.maskFilter = null
                p.alpha = 255; p.strokeWidth = px * 0.014f; p.color = AMBER_LIGHT
                c.drawArc(oval, nowMin / 4f - 90f, mins / 4f, false, p)
            }
        }

        // pucks
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        alarms.sortedBy { if (it.id == next?.id) 1 else 0 }.forEach { a ->
            val deg = a.minuteOfDay / 4f
            val (x, y) = pt(cx, cy, r, deg)
            val isNext = a.id == next?.id
            val l = tw * 1.45f
            val w = tw * 1.15f
            if (isNext) {
                p.shader = RadialGradient(x, y, l * 1.3f, (AMBER and 0x00FFFFFF) or 0x88000000.toInt(), 0, Shader.TileMode.CLAMP)
                c.drawCircle(x, y, l * 1.3f, p)
                p.shader = null
            }
            c.save()
            c.rotate(deg, x, y)
            val top = if (isNext) AMBER_LIGHT else CREAM_LIGHT
            val bot = if (isNext) AMBER_DARK else CREAM_DARK
            p.shader = LinearGradient(0f, y - w / 2, 0f, y + w / 2, top, bot, Shader.TileMode.CLAMP)
            p.alpha = if (a.enabled) 255 else 70
            if (a.isReminder) c.drawCircle(x, y, w * 0.42f, p)
            else c.drawRoundRect(RectF(x - l / 2, y - w / 2, x + l / 2, y + w / 2), w * 0.45f, w * 0.45f, p)
            p.shader = null; p.alpha = 255
            c.restore()
        }

        // now knob
        val (nx, ny) = pt(cx, cy, r, nowMin / 4f)
        p.shader = RadialGradient(nx - 3, ny - 4, tw * 0.5f, CREAM_LIGHT, CREAM_DARK, Shader.TileMode.CLAMP)
        c.drawCircle(nx, ny, tw * 0.38f, p)
        p.shader = null
        p.color = AMBER
        c.drawCircle(nx, ny, px * 0.012f, p)
        return bmp
    }
}
