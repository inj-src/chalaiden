package com.chalaiden.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun newerPatch() = assertTrue(Updates.isNewer("0.1.3", "0.1.2"))
    @Test fun comparesNumbersNotText() = assertTrue(Updates.isNewer("0.1.10", "0.1.9"))
    @Test fun newerMinorBeatsHigherPatch() = assertTrue(Updates.isNewer("0.2.0", "0.1.9"))
    @Test fun sameIsNotNewer() = assertFalse(Updates.isNewer("0.1.2", "0.1.2"))
    @Test fun olderIsNotNewer() = assertFalse(Updates.isNewer("0.1.1", "0.1.2"))
    @Test fun missingPartsAreZero() = assertFalse(Updates.isNewer("0.1", "0.1.0"))
    @Test fun suffixIgnored() = assertFalse(Updates.isNewer("0.1.2-beta", "0.1.2"))
}
