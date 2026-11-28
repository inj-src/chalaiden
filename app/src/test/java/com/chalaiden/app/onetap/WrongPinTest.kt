package com.chalaiden.app.onetap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WrongPinTest {
    @Test fun bkashsOwnWordsForAWrongPin() {
        // What *247# answered a wrong PIN with, on a balance check (2026-11-28).
        assertTrue(OneTap.isWrongPin("The Mobile PIN is invalid."))
    }

    @Test fun looserWordings() {
        assertTrue(OneTap.isWrongPin("Wrong PIN. Please try again"))
        assertTrue(OneTap.isWrongPin("Invalid Mobile PIN"))
        assertTrue(OneTap.isWrongPin("PIN mismatch"))
    }

    @Test fun refusalsThatSayNothingAboutThePin() {
        assertFalse(OneTap.isWrongPin("Insufficient balance."))
        assertFalse(OneTap.isWrongPin("Invalid amount."))
        assertFalse(OneTap.isWrongPin("The receiver account is invalid."))
        assertFalse(OneTap.isWrongPin("Your PIN will expire soon. Transaction successful, invalid reference ignored."))
    }
}
