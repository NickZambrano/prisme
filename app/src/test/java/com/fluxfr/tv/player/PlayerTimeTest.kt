package com.fluxfr.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTimeTest {
    @Test fun clockUnderAnHour() {
        assertEquals("0:00", PlayerTime.clock(0))
        assertEquals("0:59", PlayerTime.clock(59_999))
        assertEquals("4:05", PlayerTime.clock(245_000))
        assertEquals("59:59", PlayerTime.clock(3_599_000))
    }

    @Test fun clockWithHours() {
        assertEquals("1:00:00", PlayerTime.clock(3_600_000))
        assertEquals("1:02:15", PlayerTime.clock(3_735_000))
        assertEquals("12:00:01", PlayerTime.clock(43_201_000))
    }

    @Test fun clockClampsNegative() {
        assertEquals("0:00", PlayerTime.clock(-5_000))
    }

    @Test fun remainingUsesTypographicMinus() {
        assertEquals("−38:20", PlayerTime.remaining(2_300_000))
        assertEquals("−1:00:05", PlayerTime.remaining(3_605_000))
    }

    @Test fun deltaAndRuntime() {
        assertEquals("10 s", PlayerTime.delta(10_000))
        assertEquals("10 s", PlayerTime.delta(-10_000))
        assertEquals("2 min", PlayerTime.delta(120_000))
        assertEquals("1 min 30 s", PlayerTime.delta(90_000))
        assertEquals("45 min", PlayerTime.runtime(45 * 60_000L))
        assertEquals("1 h 52", PlayerTime.runtime(112 * 60_000L))
    }
}
