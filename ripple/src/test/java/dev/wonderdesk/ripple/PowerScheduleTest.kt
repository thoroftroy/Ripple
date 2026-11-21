package dev.wonderdesk.ripple

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class PowerScheduleTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = Calendar.getInstance().apply { set(y, mo, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    // 2026-11-20 is a Friday.
    @Test fun weekdaysSkipTheWeekend() {
        val s = PowerSchedule("1", "fp", "wake", 7, 30, 31)   // Mon-Fri
        val next = Calendar.getInstance().apply { timeInMillis = PowerSchedules.nextRun(s, at(2026, Calendar.NOVEMBER, 20, 9, 0)) }
        assertEquals(Calendar.MONDAY, next.get(Calendar.DAY_OF_WEEK)); assertEquals(7, next.get(Calendar.HOUR_OF_DAY)); assertEquals(30, next.get(Calendar.MINUTE))
    }
    @Test fun laterTodayRunsToday() {
        val s = PowerSchedule("1", "fp", "sleep", 23, 0, 127)
        val next = Calendar.getInstance().apply { timeInMillis = PowerSchedules.nextRun(s, at(2026, Calendar.NOVEMBER, 20, 9, 0)) }
        assertEquals(20, next.get(Calendar.DAY_OF_MONTH)); assertEquals(23, next.get(Calendar.HOUR_OF_DAY))
    }
    @Test fun labels() {
        assertEquals("Wake at 7:30, weekdays", PowerSchedule("1", "fp", "wake", 7, 30, 31).label())
        assertEquals("Eco mode at 22:05, Sat Sun", PowerSchedule("1", "fp", "eco", 22, 5, 96).label().replace("weekends", "Sat Sun"))
        assertTrue(PowerSchedule("1", "fp", "wake", 7, 30, 1).runsOn(Calendar.MONDAY))
        assertFalse(PowerSchedule("1", "fp", "wake", 7, 30, 1).runsOn(Calendar.SUNDAY))
    }
}
