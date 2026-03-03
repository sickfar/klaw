package io.github.klaw.engine.workspace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScheduleDeliverSinkTest {
    @Test
    fun `deliver then consumeMessage returns the message`() {
        val sink = ScheduleDeliverSink()
        sink.deliver("Buy milk")
        assertEquals("Buy milk", sink.consumeMessage())
    }

    @Test
    fun `consumeMessage without deliver returns null`() {
        val sink = ScheduleDeliverSink()
        assertNull(sink.consumeMessage())
    }

    @Test
    fun `second consumeMessage returns null (consume once)`() {
        val sink = ScheduleDeliverSink()
        sink.deliver("Hello")
        sink.consumeMessage()
        assertNull(sink.consumeMessage())
    }

    @Test
    fun `deliver twice overwrites first (last-write-wins)`() {
        val sink = ScheduleDeliverSink()
        sink.deliver("First")
        sink.deliver("Second")
        assertEquals("Second", sink.consumeMessage())
    }
}
