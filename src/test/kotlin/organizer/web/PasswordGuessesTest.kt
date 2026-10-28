package organizer.web

import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordGuessesTest {
    private var now = 1_000_000L
    private val guesses = PasswordGuesses { now }

    @Test
    fun `an address waits until its oldest wrong password is a minute old`() {
        repeat(PasswordGuesses.MAX_WRONG - 1) { guesses.failed("10.0.0.2") }
        assertEquals(0, guesses.waitSeconds("10.0.0.2"))

        now += 20_000
        guesses.failed("10.0.0.2")
        assertEquals(40, guesses.waitSeconds("10.0.0.2"))
        assertEquals(0, guesses.waitSeconds("10.0.0.3"), "other addresses don't wait")

        now += 40_000
        assertEquals(0, guesses.waitSeconds("10.0.0.2"))
    }

    @Test
    fun `the right password clears the wrong ones`() {
        repeat(PasswordGuesses.MAX_WRONG - 1) { guesses.failed("10.0.0.2") }
        guesses.succeeded("10.0.0.2")
        guesses.failed("10.0.0.2")
        assertEquals(0, guesses.waitSeconds("10.0.0.2"))
    }
}
