package com.silooo.core.discord

import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Une configuration Staff absente refuse le geste avant tout accès au serveur, au dépôt ou à la base. */
class StaffAuthorizationTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val staffUser = DiscordUser("42", "Kr", roleIds = setOf("staff-role"))

    private fun context(roles: Map<String, String> = emptyMap(), timer: Timer = forbiddenDiscordDependency()) =
        BotContext(
            forbiddenDiscordDependency(),
            ServerIds(roles, mapOf("annonces" to "10", "coulisses" to "11", "giveaways" to "12", "admissions" to "13")),
            timer, ServerPhase.ATTENTE,
        )

    private fun assertMissingRole(reply: FakeReply) {
        assertEquals("Le rôle Staff n'est pas configuré sur le serveur.", reply.sent.single().first.text)
        assertTrue(reply.sent.single().second)
        assertTrue(reply.edits.isEmpty())
    }

    @Test
    @DisplayName("Sans identifiant du rôle Staff, /annonce ne garde aucun aperçu ni minuterie")
    fun missingStaffRoleRefusesAnnouncement() = runBlocking {
        val context = context()
        val feature = AnnounceFeature(context, BotJournal(context))
        val reply = FakeReply()
        feature.handle(DiscordEvent.CommandUsed("annonce", mapOf("titre" to "Le silo", "texte" to "Un étage de plus."), staffUser, "10", reply))
        assertMissingRole(reply)
        val publish = FakeReply()
        feature.handle(DiscordEvent.ButtonPressed("annonce-publier", staffUser, "10", "1", publish))
        assertMissingRole(publish)
    }

    @Test
    @DisplayName("Sans identifiant du rôle Staff, /spoil ne lit ni n'écrit son dépôt")
    fun missingStaffRoleRefusesSpoil() = runBlocking {
        val feature = SpoilFeature(context(), SpoilRules(0.4, 60, "silo", 22, 7), zone, forbiddenDiscordDependency())
        val reply = FakeReply()
        feature.handle(DiscordEvent.CommandUsed("spoil", mapOf("texte" to "Un étage de plus."), staffUser, "11", reply))
        assertMissingRole(reply)
    }

    @Test
    @DisplayName("Sans identifiant du rôle Staff, /giveaway ne lit ni n'écrit son dépôt")
    fun missingStaffRoleRefusesGiveaway() = runBlocking {
        val context = context()
        val feature = GiveawayFeature(context, GiveawayRules(1), zone, forbiddenDiscordDependency(), BotJournal(context))
        val reply = FakeReply()
        feature.handle(DiscordEvent.CommandUsed("giveaway", mapOf("titre" to "Le lot", "lot" to "gemmes", "quantite" to "500", "duree" to "1"), staffUser, "12", reply))
        assertMissingRole(reply)
    }

    @Test
    @DisplayName("Sans identifiant du rôle Staff, aucun des trois boutons d'admission n'accède aux dépôts ni à la base")
    fun missingStaffRoleRefusesAllAdmissionActions() = runBlocking {
        val context = context()
        val feature = AdmissionFeature(
            context, AdmissionRules(60), zone, forbiddenDiscordDependency(), forbiddenDiscordDependency(),
            forbiddenDiscordDependency(), BotJournal(context),
        )
        for (prefix in listOf(AdmissionTexts.ADMIT_PREFIX, AdmissionTexts.REFUSE_PREFIX, AdmissionTexts.LATER_PREFIX)) {
            val reply = FakeReply()
            feature.handle(DiscordEvent.ButtonPressed(prefix + "inscrit", staffUser, "13", "1", reply))
            assertMissingRole(reply)
        }
    }

    @Test
    @DisplayName("Si le rôle Staff disparaît de la configuration après l'aperçu, Publier ne touche pas au serveur")
    fun missingStaffRoleRefusesAnExistingAnnouncementDraft() = runBlocking {
        val roles = mutableMapOf("staff" to "staff-role")
        val timer = FakeTimer()
        val context = context(roles, timer)
        val feature = AnnounceFeature(context, BotJournal(context))
        val preview = FakeReply()
        feature.handle(DiscordEvent.CommandUsed("annonce", mapOf("titre" to "Le silo", "texte" to "Un étage de plus."), staffUser, "10", preview))
        assertEquals(1, timer.pending)
        assertEquals(listOf("annonce-publier", "annonce-annuler"), preview.sent.single().first.buttons.map { it.id })

        roles.remove("staff")
        val publish = FakeReply()
        feature.handle(DiscordEvent.ButtonPressed("annonce-publier", staffUser, "10", "1", publish))
        assertMissingRole(publish)
        assertEquals(1, timer.pending, "le refus ne consomme pas le brouillon")
    }
}
