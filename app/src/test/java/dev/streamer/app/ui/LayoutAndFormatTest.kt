package dev.streamer.app.ui

import androidx.compose.ui.unit.dp
import dev.streamer.app.ui.components.dotJoin
import dev.streamer.app.ui.components.formatClock
import dev.streamer.app.ui.components.formatLength
import dev.streamer.app.ui.navigation.ShellLayout
import dev.streamer.app.ui.navigation.WidthClass
import dev.streamer.app.ui.navigation.widthClassFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LayoutAndFormatTest {
    @Test
    fun widthClassesUseMaterialBreakpoints() {
        assertEquals(WidthClass.Compact, widthClassFor(599.dp))
        assertEquals(WidthClass.Medium, widthClassFor(600.dp))
        assertEquals(WidthClass.Medium, widthClassFor(839.dp))
        assertEquals(WidthClass.Expanded, widthClassFor(840.dp))
    }

    @Test
    fun playerPaneOnlyWhenContentStaysWide() {
        assertFalse(ShellLayout.forWindowWidth(400.dp).playerPaneFits)
        assertFalse(ShellLayout.forWindowWidth(700.dp).playerPaneFits)
        // Expanded but too narrow for rail + pane + 480dp of content.
        assertFalse(ShellLayout.forWindowWidth(860.dp).playerPaneFits)
        assertTrue(ShellLayout.forWindowWidth(1280.dp).playerPaneFits)
    }

    @Test
    fun clockFormatting() {
        assertEquals("0:00", 0.seconds.formatClock())
        assertEquals("3:58", (3.minutes + 58.seconds).formatClock())
        assertEquals("1:02:03", (1.hours + 2.minutes + 3.seconds).formatClock())
    }

    @Test
    fun lengthFormatting() {
        assertEquals("43 min", (43.minutes + 10.seconds).formatLength())
        assertEquals("1 min", 20.seconds.formatLength())
        assertEquals("1 hr", 60.minutes.formatLength())
        assertEquals("1 hr 12 min", 72.minutes.formatLength())
    }

    @Test
    fun dotJoinSkipsMissingParts() {
        assertEquals("Album · 2026", dotJoin("Album", null, "2026", ""))
    }
}
