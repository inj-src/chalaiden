package com.chalaiden.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberExtractorTest {
    @Test fun spacedGroups() = assertEquals(listOf("01783007004"), NumberExtractor.find("0178 3007 004"))
    @Test fun boxedDigits() = assertEquals(listOf("01745606306"), NumberExtractor.find("0 1 7 4 5 6 0 6 3 0 6"))
    @Test fun lookalikeLetters() = assertEquals(listOf("01745606306"), NumberExtractor.find("O17 4560 63O6"))
    // ML Kit's real read of a boxed bKash stand: the edges between boxes come back as '|'.
    @Test fun boxEdgesAreNotOnes() = assertEquals(listOf("01745606306"), NumberExtractor.find("01|7 456063|06"))
    // Read as 1s, these edges made a wrong but valid-looking 01714560163.
    @Test fun boxEdgesNeverMakeAWrongNumber() = assertEquals(listOf("01745606306"), NumberExtractor.find("017|4560|6306"))
    @Test fun pipeIsAOneWhenNothingElseFits() = assertEquals(listOf("01711000000"), NumberExtractor.find("0|711000000"))
    @Test fun countryCode() = assertEquals(listOf("01711000000"), NumberExtractor.find("+880 1711-000000"))
    @Test fun wordsAreLeftAlone() = assertEquals(emptyList<String>(), NumberExtractor.find("BANGLA QR"))
    @Test fun tooShort() = assertEquals(emptyList<String>(), NumberExtractor.find("0178 3007"))
}
