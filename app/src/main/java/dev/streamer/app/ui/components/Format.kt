package dev.streamer.app.ui.components

import java.time.Duration as JavaDuration
import java.time.Instant
import kotlin.time.Duration

/** Track-style time: 3:58, 1:02:03. */
fun Duration.formatClock(): String {
    val total = inWholeSeconds.coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Collection length: 43 min, 1 hr 12 min. */
fun Duration.formatLength(): String {
    val minutes = ((inWholeSeconds + 30) / 60).coerceAtLeast(if (inWholeSeconds > 0) 1 else 0)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h hr"
        else -> "$h hr $m min"
    }
}

fun songCount(count: Int): String = if (count == 1) "1 song" else "$count songs"

/** Joins the non-null, non-blank parts with a middle dot. */
fun dotJoin(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

/** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago". */
fun relativeTime(then: Instant, now: Instant): String {
    val elapsed = JavaDuration.between(then, now)
    val minutes = elapsed.toMinutes()
    val hours = elapsed.toHours()
    val days = elapsed.toDays()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        days < 2 -> "yesterday"
        else -> "$days days ago"
    }
}
