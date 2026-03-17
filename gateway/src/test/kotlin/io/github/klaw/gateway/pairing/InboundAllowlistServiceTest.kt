package io.github.klaw.gateway.pairing

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InboundAllowlistServiceTest {
    private fun config(vararg chats: AllowedChat): GatewayConfig =
        GatewayConfig(
            channels =
                ChannelsConfig(
                    telegram =
                        TelegramConfig(
                            token = "test-token",
                            allowedChats = chats.toList(),
                        ),
                ),
        )

    @Test
    fun `paired chat and user allowed`() {
        val service = InboundAllowlistService(config(AllowedChat("chat1", listOf("user1"))))
        assertTrue(service.isAllowed("telegram", "chat1", "user1"))
    }

    @Test
    fun `unpaired chat denied`() {
        val service = InboundAllowlistService(config(AllowedChat("chat1", listOf("user1"))))
        assertFalse(service.isAllowed("telegram", "chat999", "user1"))
    }

    @Test
    fun `paired chat unpaired user denied`() {
        val service = InboundAllowlistService(config(AllowedChat("chat1", listOf("user1"))))
        assertFalse(service.isAllowed("telegram", "chat1", "user2"))
    }

    @Test
    fun `local_ws channel always allowed`() {
        val service = InboundAllowlistService(config())
        assertTrue(service.isAllowed("local_ws", "any-chat", null))
    }

    @Test
    fun `empty allowedChats denies all telegram messages`() {
        val service = InboundAllowlistService(config())
        assertFalse(service.isAllowed("telegram", "chat1", "user1"))
    }

    @Test
    fun `empty allowedUserIds denies all users for that chat`() {
        val service = InboundAllowlistService(config(AllowedChat("chat1", emptyList())))
        assertFalse(service.isAllowed("telegram", "chat1", "user1"))
    }

    @Test
    fun `reload updates allowlist`() {
        val service = InboundAllowlistService(config())
        assertFalse(service.isAllowed("telegram", "chat1", "user1"))

        service.reload(config(AllowedChat("chat1", listOf("user1"))))
        assertTrue(service.isAllowed("telegram", "chat1", "user1"))
    }

    @Test
    fun `isStartAllowed returns AlreadyPaired when chat and user paired`() {
        val service = InboundAllowlistService(config(AllowedChat("chat1", listOf("user1"))))
        assertEquals(PairingStatus.AlreadyPaired, service.isStartAllowed("telegram", "chat1", "user1"))
    }

    @Test
    fun `isStartAllowed returns NewChat when chatId not in allowedChats`() {
        val service = InboundAllowlistService(config(AllowedChat("chat1", listOf("user1"))))
        assertEquals(PairingStatus.NewChat, service.isStartAllowed("telegram", "chat999", "user1"))
    }

    @Test
    fun `isStartAllowed returns NewUserInExistingChat when chatId exists but userId not`() {
        val service = InboundAllowlistService(config(AllowedChat("chat1", listOf("user1"))))
        assertEquals(
            PairingStatus.NewUserInExistingChat,
            service.isStartAllowed("telegram", "chat1", "user2"),
        )
    }

    @Test
    fun `null userId for telegram denied when allowedUserIds is empty`() {
        val service = InboundAllowlistService(config(AllowedChat("chat1", emptyList())))
        assertFalse(service.isAllowed("telegram", "chat1", null))
    }

    @Test
    fun `null userId for telegram denied even when allowedUserIds has entries`() {
        val service = InboundAllowlistService(config(AllowedChat("chat1", listOf("user1"))))
        assertFalse(service.isAllowed("telegram", "chat1", null))
    }

    @Test
    fun `isStartAllowed local_ws always returns AlreadyPaired`() {
        val service = InboundAllowlistService(config())
        assertEquals(PairingStatus.AlreadyPaired, service.isStartAllowed("local_ws", "any", null))
    }

    @Test
    fun `isStartAllowed null userId returns NewUserInExistingChat when chat exists`() {
        val service = InboundAllowlistService(config(AllowedChat("chat1", listOf("user1"))))
        assertEquals(
            PairingStatus.NewUserInExistingChat,
            service.isStartAllowed("telegram", "chat1", null),
        )
    }

    @Test
    fun `no telegram config denies all`() {
        val service = InboundAllowlistService(GatewayConfig(channels = ChannelsConfig()))
        assertFalse(service.isAllowed("telegram", "chat1", "user1"))
    }

    @Test
    fun `unknown channel denied`() {
        val service = InboundAllowlistService(config(AllowedChat("chat1", listOf("user1"))))
        assertFalse(service.isAllowed("unknown", "chat1", "user1"))
    }
}
