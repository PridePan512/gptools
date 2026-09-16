package com.example.gptest

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gptest.data.HistoryAccess
import com.example.gptest.data.HistoryAccessBlockedException
import com.example.gptest.data.HistoryRepository
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One opt-in request, respecting persisted cooldown; never changes scan records. */
@RunWith(AndroidJUnit4::class)
class HistoryEndpointDiagnosticTest {
    @Test fun reportHistoryAvailability() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("historyDiagnostic") == "true")
        val result = HistoryRepository().fetchBars("sh688475", 100)
        val error = result.exceptionOrNull()
        if (error is HistoryAccessBlockedException) assertNotNull(HistoryAccess.gate.blocked())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nHISTORY_DIAGNOSTIC " + result.fold(
                onSuccess = { "OK bars=${it.bars.size}" },
                onFailure = { "${it.javaClass.simpleName}: ${it.message}" }
            ) + "\n")
        })
    }
}
