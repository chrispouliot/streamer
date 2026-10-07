package dev.streamer.app.ui

import dev.streamer.app.ui.player.SheetMotion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import androidx.compose.runtime.MonotonicFrameClock
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SheetMotionTest {
    /** Frame clock driven by virtual time, so Compose animations run under runTest. */
    private class TestFrameClock(private val scope: TestScope) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            kotlinx.coroutines.delay(16)
            return onFrame(scope.testScheduler.currentTime * 1_000_000)
        }
    }

    private fun runMotion(block: suspend TestScope.(SheetMotion) -> Unit) = runTest(StandardTestDispatcher()) {
        withContext(TestFrameClock(this)) {
            val motion = SheetMotion(this@withContext.let { kotlinx.coroutines.CoroutineScope(coroutineContext) })
            block(motion)
        }
    }

    @Test
    fun releaseMidwaySettlesFullyEvenAfterLateDragDeltas() = runMotion { motion ->
        motion.settleTo(0f)
        advanceUntilIdle()
        assertEquals(0f, motion.progress)

        motion.dragBy(0.3f)
        motion.settleTo(1f)
        advanceUntilIdle()
        assertEquals(1f, motion.progress)
    }

    @Test
    fun shortDragSpringsBack() = runMotion { motion ->
        motion.settleTo(0f)
        advanceUntilIdle()
        motion.dragBy(0.1f)
        motion.settleTo(0f)
        advanceUntilIdle()
        assertEquals(0f, motion.progress)
    }

    @Test
    fun dragIsClamped() = runMotion { motion ->
        motion.settleTo(0f)
        advanceUntilIdle()
        motion.dragBy(-5f)
        assertEquals(0f, motion.progress)
        motion.dragBy(5f)
        assertEquals(1f, motion.progress)
    }
}
