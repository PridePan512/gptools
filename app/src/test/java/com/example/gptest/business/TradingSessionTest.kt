package com.example.gptest.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class TradingSessionTest {

    @Test
    fun weekday_beforeOpen_isPreOpen() {
        val clock = shanghaiClock(LocalTime.of(9, 29))
        assertEquals(TradingSession.Phase.PRE_OPEN, TradingSession.phase(clock))
        assertFalse(TradingSession.isOpen(clock))
    }

    @Test
    fun weekday_morningOpen_isOpen() {
        assertTrue(TradingSession.isOpen(shanghaiClock(LocalTime.of(9, 30))))
        assertTrue(TradingSession.isOpen(shanghaiClock(LocalTime.of(11, 29))))
        assertEquals(TradingSession.Phase.OPEN, TradingSession.phase(shanghaiClock(LocalTime.of(10, 0))))
    }

    @Test
    fun weekday_lunch_isLunch() {
        val clock = shanghaiClock(LocalTime.of(11, 30))
        assertEquals(TradingSession.Phase.LUNCH, TradingSession.phase(clock))
        assertFalse(TradingSession.isOpen(clock))
        assertFalse(TradingSession.isOpen(shanghaiClock(LocalTime.of(12, 30))))
    }

    @Test
    fun weekday_afternoonOpen_isOpen() {
        assertTrue(TradingSession.isOpen(shanghaiClock(LocalTime.of(13, 0))))
        assertTrue(TradingSession.isOpen(shanghaiClock(LocalTime.of(15, 0))))
    }

    @Test
    fun weekday_afterClose_isClosed() {
        val clock = shanghaiClock(LocalTime.of(15, 1))
        assertEquals(TradingSession.Phase.CLOSED, TradingSession.phase(clock))
        assertFalse(TradingSession.isOpen(clock))
    }

    @Test
    fun weekend_isClosedEvenDuringWeekdayHours() {
        val saturday = LocalDate.of(2026, 9, 5)
        val clock = shanghaiClock(LocalTime.of(10, 0), saturday)
        assertEquals(TradingSession.Phase.CLOSED, TradingSession.phase(clock))
        assertFalse(TradingSession.isOpen(clock))
    }

    @Test
    fun millisUntilOpen_preOpen_waitsUntilMorningOpen() {
        val clock = shanghaiClock(LocalTime.of(9, 0))
        assertEquals(30 * 60 * 1000L, TradingSession.millisUntilOpen(clock))
    }

    @Test
    fun millisUntilOpen_lunch_waitsUntilAfternoonOpen() {
        val clock = shanghaiClock(LocalTime.of(12, 0))
        assertEquals(60 * 60 * 1000L, TradingSession.millisUntilOpen(clock))
    }

    @Test
    fun millisUntilOpen_open_isZero() {
        assertEquals(0L, TradingSession.millisUntilOpen(shanghaiClock(LocalTime.of(10, 0))))
    }

    @Test
    fun millisUntilOpen_closed_isNull() {
        assertNull(TradingSession.millisUntilOpen(shanghaiClock(LocalTime.of(15, 1))))
        assertNull(
            TradingSession.millisUntilOpen(
                shanghaiClock(LocalTime.of(10, 0), LocalDate.of(2026, 9, 5))
            )
        )
    }

    private fun shanghaiClock(
        time: LocalTime,
        date: LocalDate = LocalDate.of(2026, 9, 3)
    ): Clock {
        val instant = ZonedDateTime.of(date, time, TradingSession.SHANGHAI).toInstant()
        return Clock.fixed(instant, TradingSession.SHANGHAI)
    }
}
