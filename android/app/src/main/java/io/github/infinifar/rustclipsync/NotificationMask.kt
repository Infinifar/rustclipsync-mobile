package io.github.infinifar.rustclipsync

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.TypedValue
import kotlin.math.floor

/** Length never depends on the secret. Native notification padding can vary by OEM. */
internal object NotificationMask {
    fun text(context: Context): CharSequence {
        val resources = context.resources
        val metrics = resources.displayMetrics
        // Use the current display configuration, including cover display/rotation changes.
        // Bound tablet widths to a notification card rather than the full tablet screen.
        val cardWidth = (resources.configuration.screenWidthDp.coerceIn(160, 600) - 32) * metrics.density
        val paint = Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, metrics)
        }
        val count = floor(cardWidth * (2f / 3f) / paint.measureText("*").coerceAtLeast(1f)).toInt().coerceIn(7, 256)
        return SpannableString("*".repeat(count)).apply {
            setSpan(TypefaceSpan("monospace"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
