package organizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigTest {
    @Test
    fun `the machine ID can be given, as Docker needs`() {
        assertEquals("1234-abcd", AppConfig.fromEnv(mapOf("ORGANIZER_MACHINE_ID" to " 1234-abcd ")).machineId)
        assertNull(AppConfig.fromEnv(mapOf("ORGANIZER_MACHINE_ID" to " ")).machineId)
        assertNull(AppConfig.fromEnv(emptyMap()).machineId)
    }

    @Test
    fun `HTTPS is off unless turned on`() {
        assertFalse(AppConfig.fromEnv(emptyMap()).https)
        assertFalse(AppConfig.fromEnv(mapOf("ORGANIZER_HTTPS" to "false")).https)
        assertTrue(AppConfig.fromEnv(mapOf("ORGANIZER_HTTPS" to " True ")).https)
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(mapOf("ORGANIZER_HTTPS" to "yes please")) }
    }
}
