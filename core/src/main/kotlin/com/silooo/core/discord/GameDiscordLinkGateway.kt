package com.silooo.core.discord

import com.silooo.core.discord.linking.GameDiscordLinks
import com.silooo.core.discord.linking.VerifiedDiscordLink

/** L'identité Discord est celle de l'interaction, jamais un identifiant saisi par le membre. */
class GameDiscordLinkGateway(private val service: GameDiscordLinks) : DiscordLinkGateway {
    override suspend fun redeem(discordId: String, typed: String): LinkOutcome {
        val result = service.redeem(discordId, typed)
        return LinkOutcome(LinkStatus.valueOf(result.status.name), result.link?.identity())
    }
    override suspend fun findByDiscord(discordId: String): LinkIdentity? = service.findByDiscord(discordId)?.identity()
    override suspend fun unlink(discordId: String, expected: LinkIdentity): LinkIdentity? =
        service.unlink(discordId, expected.xuid, expected.linkedAt)?.identity()
    private fun VerifiedDiscordLink.identity() = LinkIdentity(xuid, gamertag, linkedAt)
}
