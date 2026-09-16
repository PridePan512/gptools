package com.example.gptest.data

import org.junit.Assert.*
import org.junit.Test

class HistoryAccessGateTest {
    @Test fun wafBlockPersistsAndSurvivesRestartWithoutExtendingForInFlightResponses() {
        var now = 1000L
        var saved = 0L
        val gate = HistoryAccessGate { now }
        gate.restore(0) { saved = it }
        assertThrows(HistoryAccessBlockedException::class.java) {
            gate.checkResponse(501, "location.href='https://waf.tencent.com/501page.html'", null)
        }
        assertEquals(901000L, saved)
        now += 2000
        assertThrows(HistoryAccessBlockedException::class.java) { gate.checkResponse(429, "", null) }
        assertEquals(901000L, saved)
        val restarted = HistoryAccessGate { now }
        restarted.restore(saved) {}
        assertNotNull(restarted.blocked())
        now = saved
        assertNull(restarted.blocked())
    }

    @Test fun honorsRetryAfterAndDoesNotTreatOrdinaryServerErrorsAsWaf() {
        val gate = HistoryAccessGate { 0L }
        gate.checkResponse(500, "temporary failure", null)
        gate.checkResponse(501, "not implemented", null)
        gate.checkResponse(200, "{}", null)
        assertNull(gate.blocked())
        assertThrows(HistoryAccessBlockedException::class.java) { gate.checkResponse(429, "", "3600") }
        assertEquals(3600000L, gate.blocked()!!.retryAt)
    }

    @Test fun handlesHttpDateAndMalformedRetryAfterAndSuccessfulStatusChallenge() {
        val gate = HistoryAccessGate { 0L }
        assertThrows(HistoryAccessBlockedException::class.java) {
            gate.checkResponse(403, "", "Thu, 1 Jan 1970 02:00:00 GMT")
        }
        assertEquals(7200000L, gate.blocked()!!.retryAt)
        val other = HistoryAccessGate { 0L }
        assertThrows(HistoryAccessBlockedException::class.java) {
            other.checkResponse(200, "waf.tencent.com", "nonsense")
        }
        assertEquals(900000L, other.blocked()!!.retryAt)
    }
}
