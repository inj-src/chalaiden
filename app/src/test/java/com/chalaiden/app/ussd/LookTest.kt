package com.chalaiden.app.ussd

import com.chalaiden.app.helper.PhoneDialog.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the helper (root, Shizuku) and the accessibility service see, as the driver reads it. */
class LookTest {
    @Test fun noPhoneWindowIsNoDialog() = assertNull(Seen(win = false).toLook())

    @Test fun aBoxMakesItAPrompt() = assertEquals(
        Look(Dialog.Prompt("Enter Amount:"), "", false),
        Seen(win = true, message = " Enter Amount: ", input = true).toLook(),
    )

    @Test fun noBoxMakesItANotice() = assertEquals(
        Look(Dialog.Notice("Your balance is ..."), null, false),
        Seen(win = true, message = "Your balance is ...", field = "ignored").toLook(),
    )

    @Test fun progressWinsOverItsText() = assertEquals(
        Look(Dialog.Progress, null, false),
        Seen(win = true, progress = true, message = "USSD code running…").toLook(),
    )

    @Test fun aPinBoxKeepsItsTextOut() = assertEquals(
        Look(Dialog.Prompt("Enter PIN"), "", true),
        Seen(win = true, message = "Enter PIN", input = true, password = true).toLook(),
    )

    /** A window that is still drawing: up, but nothing to read yet. */
    @Test fun anEmptyWindowIsUpWithoutADialog() = assertEquals(Look(null, null, false), Seen(win = true).toLook())
}
