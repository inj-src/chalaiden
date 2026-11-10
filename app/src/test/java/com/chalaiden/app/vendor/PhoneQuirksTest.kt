package com.chalaiden.app.vendor

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneQuirksTest {
    @Test fun vivoClosesTheDialogOnSend() = assertEquals(true, PhoneQuirks.of("vivo", "vivo").closesDialogOnSend)
    @Test fun iqooIsVivo() = assertEquals(true, PhoneQuirks.of("vivo", "iQOO").closesDialogOnSend)
    @Test fun stockKeepsItsShortWait() = assertEquals(2_500L, PhoneQuirks.of("OnePlus", "OnePlus").frontGapMillis)
    @Test fun vivoWaitsForBkashsSlowestReply() =
        assertEquals(PhoneQuirks.SLOWEST_REPLY_MILLIS, PhoneQuirks.of("vivo", "vivo").frontGapMillis)
    @Test fun unknownMakerIsStock() = assertEquals(PhoneQuirks(), PhoneQuirks.of(null, null))
}
