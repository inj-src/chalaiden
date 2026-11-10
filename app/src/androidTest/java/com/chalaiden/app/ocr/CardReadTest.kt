package com.chalaiden.app.ocr

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real card photos through the real ML Kit model, which only runs on Android. Run without wiping
 * app data: install both debug APKs with `adb install -r`, then
 * `adb shell am instrument -w com.chalaiden.app.test/androidx.test.runner.AndroidJUnitRunner`.
 * (Gradle's connectedAndroidTest uninstalls the app afterwards.)
 */
@RunWith(AndroidJUnit4::class)
class CardReadTest {
    private fun read(asset: String): String? {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return NumberFinder.read(ctx.assets.open(asset).use(BitmapFactory::decodeStream)).number
    }

    /** One digit per box: ML Kit reads the edges between boxes as '|' ("01|7 456063|06"). */
    @Test fun boxedStand() = assertEquals("01745606306", read("cards/boxed-stand.png"))
}
