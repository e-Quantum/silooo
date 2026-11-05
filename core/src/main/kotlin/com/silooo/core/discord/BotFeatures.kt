package com.silooo.core.discord

import com.silooo.core.db.Database
import com.silooo.core.registration.JdbcSiteRegistrationStore
import java.nio.file.Path

/**
 * Les fonctions du bot Oooscar, assemblées pour la production : construites sur le fil du bot une fois le plan
 * appliqué, avec les chiffres de `definitions/discord.json` lus à ce moment (un fichier abîmé arrête le bot, pas le
 * jeu). [onlineCount] : les joueurs en jeu, pour le vocal « N en ligne ».
 */
object BotFeatures {
    fun build(definitionsDir: Path, database: Database, onlineCount: () -> Int): (BotContext) -> List<Feature> = { context ->
        val definitions = DiscordDefinitions.loadChecked(definitionsDir, ServerPlan.fromClasspath())
        val members = JdbcMemberStore(database)
        val journal = BotJournal(context)
        listOf(
            WelcomeFeature(context, definitions.welcome, members),
            StatusFeature(context, definitions.status),
            InfoVoiceFeature(context, definitions.infoVoice, onlineCount),
            SpoilFeature(context, definitions.spoils, definitions.zone, JdbcSpoilStore(database)),
            AdmissionFeature(context, definitions.admissions, definitions.zone, JdbcAdmissionStore(database), JdbcSiteRegistrationStore(), database, journal),
            AnnounceFeature(context, journal),
            XpFeature(context, definitions.xp, definitions.report, definitions.zone, JdbcXpStore(database)),
            GiveawayFeature(context, definitions.giveaways, definitions.zone, JdbcGiveawayStore(database), journal),
            TicketFeature(context, JdbcTicketStore(database), JdbcBotMessageStore(database), journal, definitions.zone),
            VoiceRoomFeature(context),
        )
    }
}
