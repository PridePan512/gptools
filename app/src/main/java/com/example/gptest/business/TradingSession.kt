package com.example.gptest.business

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object TradingSession {
    val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

    enum class Phase {
        PRE_OPEN,
        OPEN,
        LUNCH,
        CLOSED
    }

    fun isOpen(clock: Clock = Clock.system(SHANGHAI)): Boolean {
        return phase(clock) == Phase.OPEN
    }

    fun phase(clock: Clock = Clock.system(SHANGHAI)): Phase {
        val now = ZonedDateTime.now(clock.withZone(SHANGHAI))
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) {
            return Phase.CLOSED
        }
        val time = now.toLocalTime()
        return when {
            time < MORNING_OPEN -> Phase.PRE_OPEN
            time < MORNING_CLOSE -> Phase.OPEN
            time < AFTERNOON_OPEN -> Phase.LUNCH
            time <= AFTERNOON_CLOSE -> Phase.OPEN
            else -> Phase.CLOSED
        }
    }

    private val MORNING_OPEN = LocalTime.of(9, 30)
    private val MORNING_CLOSE = LocalTime.of(11, 30)
    private val AFTERNOON_OPEN = LocalTime.of(13, 0)
    private val AFTERNOON_CLOSE = LocalTime.of(15, 0)
}
