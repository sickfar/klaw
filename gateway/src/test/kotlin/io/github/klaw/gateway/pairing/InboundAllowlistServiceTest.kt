package io.github.klaw.gateway.pairing

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.AllowedGuild
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.DiscordChannelConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramChannelConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InboundAllowlistServiceTest {
    private fun config(vararg chats: AllowedChat): GatewayConfig =
        GatewayConfig(
            channels =
                ChannelsConfig(
                    telegram =
                        mapOf(
                            "default" to
                                TelegramChannelConfig(
                                    agentId = "default",
                                    token = "test-token",
                                    allowedChats = chats.toList(),
                                ),
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

    // --- Discord tests ---

    private fun discordConfig(vararg guilds: AllowedGuild): GatewayConfig =
        GatewayConfig(
            channels =
                ChannelsConfig(
                    discord =
                        mapOf(
                            "default" to
                                DiscordChannelConfig(
                                    agentId = "default",
                                    token = "discord-token",
                                    allowedGuilds = guilds.toList(),
                                ),
                        ),
                ),
        )

    @Nested
    inner class DiscordIsAllowed {
        @Test
        fun `discord config absent denies`() {
            val service = InboundAllowlistService(GatewayConfig(channels = ChannelsConfig()))
            assertFalse(service.isAllowed("discord", "chat1", "user1"))
        }

        @Test
        fun `discord guild with userId allowed`() {
            val service =
                InboundAllowlistService(
                    discordConfig(AllowedGuild("guild1", allowedUserIds = listOf("user1"))),
                )
            assertTrue(service.isAllowed("discord", "discord_guild1_chan1", "user1"))
        }

        @Test
        fun `discord guild without userId denied`() {
            val service =
                InboundAllowlistService(
                    discordConfig(AllowedGuild("guild1", allowedUserIds = listOf("user1"))),
                )
            assertFalse(service.isAllowed("discord", "discord_guild1_chan1", "user2"))
        }

        @Test
        fun `discord null userId denied`() {
            val service =
                InboundAllowlistService(
                    discordConfig(AllowedGuild("guild1", allowedUserIds = listOf("user1"))),
                )
            assertFalse(service.isAllowed("discord", "discord_guild1_chan1", null))
        }

        @Test
        fun `discord empty allowedUserIds denied`() {
            val service =
                InboundAllowlistService(
                    discordConfig(AllowedGuild("guild1", allowedUserIds = emptyList())),
                )
            assertFalse(service.isAllowed("discord", "discord_guild1_chan1", "user1"))
        }

        @Test
        fun `discord userId in second guild allowed`() {
            val service =
                InboundAllowlistService(
                    discordConfig(
                        AllowedGuild("guild1", allowedUserIds = listOf("user1")),
                        AllowedGuild("guild2", allowedUserIds = listOf("user2")),
                    ),
                )
            assertTrue(service.isAllowed("discord", "discord_guild2_chan1", "user2"))
        }
    }

    @Nested
    inner class DiscordIsStartAllowed {
        @Test
        fun `discord config absent returns NewChat`() {
            val service = InboundAllowlistService(GatewayConfig(channels = ChannelsConfig()))
            assertEquals(PairingStatus.NewChat, service.isStartAllowed("discord", "chat1", "user1"))
        }

        @Test
        fun `discord guild with userId returns AlreadyPaired`() {
            val service =
                InboundAllowlistService(
                    discordConfig(AllowedGuild("guild1", allowedUserIds = listOf("user1"))),
                )
            assertEquals(
                PairingStatus.AlreadyPaired,
                service.isStartAllowed("discord", "discord_guild1_chan1", "user1"),
            )
        }

        @Test
        fun `discord guild without userId returns NewUserInExistingChat`() {
            val service =
                InboundAllowlistService(
                    discordConfig(AllowedGuild("guild1", allowedUserIds = listOf("user1"))),
                )
            assertEquals(
                PairingStatus.NewUserInExistingChat,
                service.isStartAllowed("discord", "discord_guild1_chan1", "user2"),
            )
        }

        @Test
        fun `discord no guilds returns NewChat`() {
            val service = InboundAllowlistService(discordConfig())
            assertEquals(PairingStatus.NewChat, service.isStartAllowed("discord", "chat1", "user1"))
        }

        @Test
        fun `discord null userId returns NewUserInExistingChat when guilds exist`() {
            val service =
                InboundAllowlistService(
                    discordConfig(AllowedGuild("guild1", allowedUserIds = listOf("user1"))),
                )
            assertEquals(
                PairingStatus.NewUserInExistingChat,
                service.isStartAllowed("discord", "discord_guild1_chan1", null),
            )
        }
    }

    @Nested
    inner class DiscordIsChatAllowed {
        @Test
        fun `discord config absent returns false`() {
            val service = InboundAllowlistService(GatewayConfig(channels = ChannelsConfig()))
            assertFalse(service.isChatAllowed("discord", "discord_guild1_chan1"))
        }

        @Test
        fun `discord config with guilds returns true`() {
            val service =
                InboundAllowlistService(
                    discordConfig(AllowedGuild("guild1", allowedUserIds = listOf("user1"))),
                )
            assertTrue(service.isChatAllowed("discord", "discord_guild1_chan1"))
        }

        @Test
        fun `discord config with empty guilds returns false`() {
            val service = InboundAllowlistService(discordConfig())
            assertFalse(service.isChatAllowed("discord", "discord_guild1_chan1"))
        }
    }
}
