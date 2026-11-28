package com.chalaiden.app.ui

import com.chalaiden.app.ussd.Flows
import com.chalaiden.app.ussd.Outcome
import com.chalaiden.app.ussd.RunState
import com.chalaiden.app.ussd.UssdSession.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepReplayTest {
    private val flow = Flows.sendMoney("01302277488", "10", "table 4")
    private val total = StepReplay.lines(flow)

    @Test fun listHasOneLinePerStepButThePinPlusTheMenu() {
        assertEquals(flow.steps.size, total)
    }

    @Test fun replayNeverRunsAheadOfTheSession() {
        assertEquals(0, StepReplay.reached(RunState.Working(0, "Opening"), total))
        assertEquals(2, StepReplay.reached(RunState.Working(2, "Entering"), total))
        assertEquals(total, StepReplay.reached(RunState.AwaitingPin(emptyList(), 0L), total))
        assertEquals(total, StepReplay.reached(RunState.Confirming, total))
    }

    @Test fun successWaitsForTheReplay() {
        val paid = Snapshot(flow, RunState.Finished(Outcome.Success("Done", "TRX1")), confirmed = true)
        assertTrue(!StepReplay.caughtUp(paid, 1))
        assertTrue(StepReplay.caughtUp(paid, total))
    }

    @Test fun failureAndCancelShowAtOnce() {
        val failed = Snapshot(flow, RunState.Finished(Outcome.Failed("Insufficient balance", pinSent = true)), confirmed = true)
        val cancelled = Snapshot(flow, RunState.Finished(Outcome.Cancelled), confirmed = true)
        assertTrue(StepReplay.caughtUp(failed, 0))
        assertTrue(StepReplay.caughtUp(cancelled, null))
    }

    @Test fun nothingReplaysBeforeTheHold() {
        assertTrue(StepReplay.caughtUp(Snapshot(flow, RunState.AwaitingPin(emptyList(), 0L), confirmed = false), null))
    }
}
