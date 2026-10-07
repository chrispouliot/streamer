package dev.streamer.app.ui.components

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
