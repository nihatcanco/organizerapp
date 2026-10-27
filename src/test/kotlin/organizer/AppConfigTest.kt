package organizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppConfigTest {
    @Test
    fun `the machine ID can be given, as Docker needs`() {
        assertEquals("1234-abcd", AppConfig.fromEnv(mapOf("ORGANIZER_MACHINE_ID" to " 1234-abcd ")).machineId)
        assertNull(AppConfig.fromEnv(mapOf("ORGANIZER_MACHINE_ID" to " ")).machineId)
        assertNull(AppConfig.fromEnv(emptyMap()).machineId)
    }
}
