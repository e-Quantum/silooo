package com.silooo.core.discord

import com.silooo.core.Dependencies
import com.silooo.core.Config
import com.silooo.core.db.Database
import com.silooo.core.registration.JdbcSiteRegistrationStore
import java.nio.file.Path

/**
 * Les fonctions du bot Oooscar, assemblées pour la production : construites sur le fil du bot une fois le plan
 * appliqué, avec les chiffres de `definitions/discord.json` lus à ce moment (un fichier abîmé arrête le bot, pas le
 * jeu). [onlineCount] : les joueurs en jeu, pour le vocal « N en ligne ». [siteUrl] : l'adresse du site, pour les
 * liens que le bot écrit (le chantier en direct).
 */
object BotFeatures {
    fun build(
        definitionsDir: Path,
        database: Database,
        siteUrl: String = Config.DEFAULT_SITE_URL,
        game: Dependencies? = null,
        onlineCount: () -> Int,
    ): (BotContext) -> List<Feature> = { context ->
        val definitions = DiscordDefinitions.loadChecked(definitionsDir, ServerPlan.fromClasspath())
        val links = com.silooo.core.discord.linking.GameDiscordLinks(database, game?.clock ?: java.time.Clock.systemUTC())
        val managedRoles = JdbcManagedGameRoleStore(database)
        val members = JdbcMemberStore(database)
        val journal = BotJournal(context)
        val messages = JdbcBotMessageStore(database)
        val games = JdbcGameStore(database)
        val reports = JdbcReportStore(database)
        val records = RecordsBoard(context, games, messages, definitions.zone)
        val signalements = JdbcSignalementStore(database)
        listOf(
            ManagementFeature(context),
            ModerationFeature(context, definitions.moderation, JdbcModerationStore(database, definitions.moderation.retentionDays), ServerModerationGateway(context), journal),
            SignalementFeature(context, signalements, definitions.reporting.rules(), context.server::canReadChannel),
            WelcomeFeature(context, definitions.welcome, members, reports, remindersAvailable = true),
            ReminderFeature(context, definitions.reminders, definitions.zone, JdbcReminderStore(database), links),
            LinkingFeature(context, GameDiscordLinkGateway(links), context.server::nickname),
            ReportFeature(context, definitions.report, definitions.zone, definitions.xp.channels, reports, reportCount = signalements::count),
            StatusFeature(context, definitions.status),
            InfoVoiceFeature(context, definitions.infoVoice, onlineCount),
            SpoilFeature(context, definitions.spoils, definitions.zone, JdbcSpoilStore(database)),
            AdmissionFeature(context, definitions.admissions, definitions.zone, JdbcAdmissionStore(database), JdbcSiteRegistrationStore(), database, journal,
                hasGameProof = { id -> links.findByDiscord(id) != null },
                claimInvitation = { id -> managedRoles.claimInvitation(id, context.timer.now()) }),
            AnnounceFeature(context, journal),
            XpFeature(context, definitions.xp, definitions.report, definitions.zone, JdbcXpStore(database)),
            GiveawayFeature(context, definitions.giveaways, definitions.zone, JdbcGiveawayStore(database), journal),
            TicketFeature(context, JdbcTicketStore(database), messages, journal, definitions.zone),
            VoiceRoomFeature(context),
            AutomodFeature(context, journal),
            BienvenueFeature(context, definitions.banner, definitions.zone, messages, journal),
            QgInfoFeature(context, messages, siteUrl),
            ReactionFeature(context, definitions.reactions, definitions.zone, JdbcReactionStore(database), messages, journal),
            HighlightFeature(context, definitions.highlights, JdbcHighlightStore(database), journal),
            HelpFeature(context, definitions.zone, JdbcHelpStore(database), messages, journal),
            ChantierFeature(context, messages, journal, siteUrl),
            CountingFeature(context, games, records, journal),
            ElevatorFeature(context, definitions.elevator, definitions.zone, games, messages, records, journal),
        ) + if (game == null) emptyList() else listOf(
            GameProfileFeature(context, JdbcGameProfileQueries(database), managedRoles,
                definitions.profiles.roleDefinitions(), java.time.Duration.ofSeconds(definitions.profiles.syncSeconds.toLong()),
                positionRoles = {
                    context.ids.role("entrepreneur")?.let { above -> context.server.positionGameRoles(
                        definitions.profiles.prestigeTiers.mapNotNull { context.ids.role(it.role) }, above)
                    }
                }),
            GameCommandsFeature(context, JdbcGameCommandQueries(database, gameCompanyService(game), game.siteStatsStore,
                game.voteService, game.clock), GameDiscordLinkGateway(links)),
            PollsFeature(context, JdbcPollStore(database), links),
            BureauxFeature(context, game, links, JdbcBureauStore(database), EnsureCompanyBureau { company, name, channel, writers ->
                ensureCompanyBureau(context, company, name, channel, writers)
            }),
            CommunityGameBoardsFeature(context, game, links, messages, JdbcGameEventCursorStore(database), siteUrl),
            PatchNotesFeature(context, siteUrl, JdbcCommunityStateStore(database)),
            SuggestionStatusFeature(context, JdbcCommunityStateStore(database), messages),
        )
    }
}
