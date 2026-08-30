package fi.paso.pagevox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "Continue listening" readout. Only the arithmetic is tested here — the
 * wording moved into string resources when the UI was translated, so this pins
 * which shape gets chosen rather than the English text that renders it.
 */
class TimeLeftFormatTest {

    @Test
    fun showsWholeMinutesUnderAnHour() {
        assertEquals(TimeLeft.Minutes(1), timeLeftOf(90_000))
        assertEquals(TimeLeft.Minutes(18), timeLeftOf(18 * 60_000L))
        assertEquals(TimeLeft.Minutes(59), timeLeftOf(59 * 60_000L + 59_000))
    }

    @Test
    fun showsHoursAndMinutesBeyondAnHour() {
        assertEquals(TimeLeft.Hours(1, 0), timeLeftOf(60 * 60_000L))
        assertEquals(TimeLeft.Hours(1, 5), timeLeftOf(65 * 60_000L))
        assertEquals(TimeLeft.Hours(2, 30), timeLeftOf(150 * 60_000L))
    }

    @Test
    fun degradesGracefullyAtTheEnd() {
        assertEquals(TimeLeft.UnderAMinute, timeLeftOf(30_000))
        assertEquals(TimeLeft.AlmostDone, timeLeftOf(0))
        assertEquals(TimeLeft.AlmostDone, timeLeftOf(-1))
    }
}
