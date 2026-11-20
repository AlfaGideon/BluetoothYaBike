package ru.alfagideon.bluetoothyabike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CharInfoTest {

    private val customService = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val batteryService = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val dfuService = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb")
    private val dfuCharacteristic = UUID.fromString("8ec90001-f315-4f60-9fb8-838830daea50")

    private fun char(
        service: UUID = customService,
        uuid: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
        write: Boolean = true,
        notify: Boolean = false,
    ) = CharInfo(
        serviceUuid = service,
        uuid = uuid,
        properties = (if (write) 0x04 else 0) or (if (notify) 0x10 else 0),
        instance = 0,
    )

    @Test
    fun flagsAreReadable() {
        val c = char(write = true, notify = true)
        assertTrue(c.flags.contains("Wnr"))
        assertTrue(c.flags.contains("N"))
        assertFalse(c.canRead)
    }

    @Test
    fun writeNotifyChannelIsPreferred() {
        val suggestion = BikeBleClient.suggestTarget(
            listOf(
                char(service = batteryService, notify = true),
                char(write = true, notify = true),
            ),
        )
        assertEquals(customService, suggestion?.serviceUuid)
    }

    @Test
    fun dfuServicesAreDangerous() {
        assertTrue(BikeBleClient.isDangerous(dfuService))
        val suggestion = BikeBleClient.suggestTarget(
            listOf(char(service = dfuService, uuid = dfuCharacteristic, write = true, notify = true)),
        )
        assertNull(suggestion)
    }

    @Test
    fun readOnlyChannelsMeanNoSuggestion() {
        assertNull(
            BikeBleClient.suggestTarget(
                listOf(
                    char(write = false, notify = false),
                    char(service = batteryService, write = false, notify = false),
                ),
            ),
        )
    }

    @Test
    fun standardServiceAloneIsNotEnough() {
        // Battery-сервис с записью — не канал протокола: балл не больше нуля.
        assertNull(BikeBleClient.suggestTarget(listOf(char(service = batteryService, write = true))))
    }
}
