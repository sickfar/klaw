package io.github.klaw.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeliveryConfigTest {
    @Test
    fun `default DeliveryConfig has sensible values`() {
        val config = DeliveryConfig()
        assertEquals(0, config.maxReconnectAttempts)
        assertEquals(30, config.drainBudgetSeconds)
        assertEquals(30, config.channelDrainBudgetSeconds)
    }

    @Test
    fun `negative maxReconnectAttempts throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { DeliveryConfig(maxReconnectAttempts = -1) }
    }

    @Test
    fun `negative drainBudgetSeconds throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { DeliveryConfig(drainBudgetSeconds = -1) }
    }

    @Test
    fun `negative channelDrainBudgetSeconds throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { DeliveryConfig(channelDrainBudgetSeconds = -1) }
    }

    @Test
    fun `zero values are valid`() {
        val config =
            DeliveryConfig(
                maxReconnectAttempts = 0,
                drainBudgetSeconds = 0,
                channelDrainBudgetSeconds = 0,
            )
        assertEquals(0, config.maxReconnectAttempts)
        assertEquals(0, config.drainBudgetSeconds)
        assertEquals(0, config.channelDrainBudgetSeconds)
    }

    @Test
    fun `GatewayConfig parses with delivery section`() {
        val json =
            """
{
  "channels": {
    "localWs": { "enabled": true, "port": 37474 }
  },
  "delivery": {
    "maxReconnectAttempts": 5,
    "drainBudgetSeconds": 60,
    "channelDrainBudgetSeconds": 15
  }
}
            """.trimIndent()
        val config = parseGatewayConfig(json)
        assertEquals(5, config.delivery.maxReconnectAttempts)
        assertEquals(60, config.delivery.drainBudgetSeconds)
        assertEquals(15, config.delivery.channelDrainBudgetSeconds)
    }

    @Test
    fun `GatewayConfig parses without delivery section uses defaults`() {
        val json =
            """
{
  "channels": {
    "localWs": { "enabled": true, "port": 37474 }
  }
}
            """.trimIndent()
        val config = parseGatewayConfig(json)
        assertEquals(0, config.delivery.maxReconnectAttempts)
        assertEquals(30, config.delivery.drainBudgetSeconds)
        assertEquals(30, config.delivery.channelDrainBudgetSeconds)
    }

    @Test
    fun `GatewayConfig parses with partial delivery section`() {
        val json =
            """
{
  "channels": {
    "localWs": { "enabled": true }
  },
  "delivery": {
    "maxReconnectAttempts": 10
  }
}
            """.trimIndent()
        val config = parseGatewayConfig(json)
        assertEquals(10, config.delivery.maxReconnectAttempts)
        assertEquals(30, config.delivery.drainBudgetSeconds)
        assertEquals(30, config.delivery.channelDrainBudgetSeconds)
    }
}
