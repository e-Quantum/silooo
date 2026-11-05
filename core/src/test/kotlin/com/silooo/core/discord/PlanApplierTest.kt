package com.silooo.core.discord

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le plan appliqué au faux Discord : tout se crée une fois, rien ne se supprime, un refus n'arrête pas les autres. */
class PlanApplierTest {
    private val plan = ServerPlan.fromClasspath()

    private fun apply(server: DiscordServer, phase: ServerPhase = ServerPhase.ATTENTE, dryRun: Boolean = false): PlanReport =
        runBlocking { PlanApplier(plan, server, phase, dryRun).run() }

    private fun expectedCategories(phase: ServerPhase) = plan.categories.filter { it.phase.existsIn(phase) }

    private fun expectedChannels(phase: ServerPhase) =
        expectedCategories(phase).flatMap { category -> category.salons.filter { it.phase.existsIn(phase) } }

    @Test
    @DisplayName("Sur un serveur vide, la phase d'attente crée ses rôles, catégories et salons, avec les bons droits, puis écrit au journal")
    fun createsEverythingOfThePhase() {
        val fake = FakeDiscordServer()
        val report = apply(fake)

        assertEquals(plan.roles.filter { it.phase.existsIn(ServerPhase.ATTENTE) }.map { it.nom }, fake.roles.map { it.name })
        assertEquals(expectedCategories(ServerPhase.ATTENTE).map { it.nom }, fake.categories.map { it.name })
        assertEquals(expectedChannels(ServerPhase.ATTENTE).map { it.discordName }, fake.channels.map { it.name })
        assertFalse(fake.categories.any { it.name == "Le jeu" }, "la catégorie de la bêta n'existe pas encore")
        assertEquals(emptyList<String>(), report.failures)

        val staff = fake.roles.first { it.name == "Staff" }
        val moderation = fake.roles.first { it.name == "Modération" }
        assertEquals("#e0614f", staff.color)
        assertTrue(fake.roleSpecs.getValue(staff.id).hoisted)
        val staffCategory = fake.categories.first { it.name == "Staff" }
        assertEquals(setOf(staff.id, moderation.id), fake.categorySpecs.getValue(staffCategory.id).viewers)
        val reports = fake.channels.first { it.name == "🚩・signalements" }
        assertEquals(setOf(moderation.id), fake.channelSpecs.getValue(reports.id).viewers)
        assertEquals(emptySet<String>(), fake.channelSpecs.getValue(reports.id).writers)
        val discussion = fake.channels.first { it.name == "💬・discussion" }
        assertEquals(null, fake.channelSpecs.getValue(discussion.id).writers)
        assertEquals(null, fake.channelSpecs.getValue(discussion.id).viewers)
        val online = fake.channels.first { it.kind == ChannelKind.VOCAL && it.name.endsWith("en ligne") }
        assertTrue(fake.channelSpecs.getValue(online.id).locked)

        val journal = fake.channels.first { it.name == plan.journal!!.discordName }
        assertEquals(listOf(journal.id), fake.messages.map { it.first })
        val text = fake.messages.single().second
        assertTrue(text.startsWith("Plan du serveur, phase attente.\nJ'ai créé "), text)
        assertTrue(text.contains("Je ne supprime jamais rien."), text)
        assertFalse(text.contains("Échecs"), text)
    }

    @Test
    @DisplayName("Un second passage ne crée rien et le dit")
    fun secondRunChangesNothing() {
        val fake = FakeDiscordServer()
        apply(fake)
        val roles = fake.roles.size
        val channels = fake.channels.size
        val report = apply(fake)
        assertTrue(report.nothingToDo)
        assertEquals(roles, fake.roles.size)
        assertEquals(channels, fake.channels.size)
        assertEquals(2, fake.messages.size)
        assertTrue(fake.messages.last().second.contains("Tout est en place"))
    }

    @Test
    @DisplayName("À blanc, rien ne change, sauf le journal créé pour y écrire l'écart")
    fun dryRunOnlyCreatesJournal() {
        val fake = FakeDiscordServer()
        val report = apply(fake, dryRun = true)
        assertEquals(emptyList<ExistingRole>(), fake.roles)
        assertEquals(listOf("Staff"), fake.categories.map { it.name })
        assertEquals(listOf(plan.journal!!.discordName), fake.channels.map { it.name })
        assertEquals(plan.roles.count { it.phase.existsIn(ServerPhase.ATTENTE) }, report.missingRoles.size)
        assertEquals(expectedChannels(ServerPhase.ATTENTE).size, report.missingChannels.size)
        assertEquals(emptyList<String>(), report.failures)
        val text = fake.messages.single().second
        assertTrue(text.startsWith("Plan du serveur, phase attente, à blanc.\nIl manque "), text)
        assertTrue(text.contains("sauf le salon du journal"), text)
        assertTrue(text.length <= 2000)
    }

    @Test
    @DisplayName("Passer en bêta ajoute les entrées de la bêta et ne touche pas à ce que Kr a créé à la main")
    fun betaAddsWithoutDeleting() {
        val fake = FakeDiscordServer()
        apply(fake)
        val handMade = ExistingChannel("main", "🧪・essai", ChannelKind.TEXTE, fake.categories.first().id, null)
        fake.channels += handMade
        val before = fake.channels.size

        val report = apply(fake, ServerPhase.BETA)
        assertTrue(fake.channels.contains(handMade))
        assertTrue(fake.categories.any { it.name == "Le jeu" })
        assertEquals(before + expectedChannels(ServerPhase.BETA).size - expectedChannels(ServerPhase.ATTENTE).size, fake.channels.size)
        assertEquals(plan.roles.filter { it.phase == PlanPhase.BETA }.map { it.nom }, report.createdRoles)
        assertEquals(emptyList<String>(), report.failures)
        val relied = fake.roles.first { it.name == "Joueur relié" }
        val chat = fake.channels.first { it.name == "🎮・tchat-du-jeu" }
        assertEquals(setOf(relied.id), fake.channelSpecs.getValue(chat.id).writers)
    }

    @Test
    @DisplayName("Un refus de Discord va dans le compte rendu et n'arrête pas les autres créations")
    fun refusalIsIsolated() {
        val fake = FakeDiscordServer(community = false)
        val report = apply(fake)
        val refused = expectedChannels(ServerPhase.ATTENTE).filter { it.type == ChannelKind.FORUM || it.type == ChannelKind.ANNONCES }
        assertTrue(refused.isNotEmpty())
        assertEquals(refused.size, report.failures.size, report.failures.toString())
        assertTrue(report.failures.all { it.contains("mode Communauté") })
        assertTrue(fake.channels.any { it.name == "💬・discussion" })
        assertFalse(fake.channels.any { it.name == "📢・annonces" })
        val text = fake.messages.single().second
        assertTrue(text.contains("Échecs : le salon « 📢・annonces »"), text)
    }

    @Test
    @DisplayName("Un sujet ou une couleur qui a dérivé est remis comme dans le plan")
    fun driftIsCorrected() {
        val fake = FakeDiscordServer()
        apply(fake)
        val channel = fake.channels.indexOfFirst { it.name == "💬・discussion" }
        fake.channels[channel] = fake.channels[channel].copy(topic = "autre chose")
        val role = fake.roles.indexOfFirst { it.name == "Staff" }
        fake.roles[role] = fake.roles[role].copy(color = null)

        val dry = apply(fake, dryRun = true)
        assertEquals(listOf("💬・discussion"), dry.changedChannels)
        assertEquals(listOf("Staff"), dry.changedRoles)
        assertEquals("autre chose", fake.channels[channel].topic)

        val report = apply(fake)
        assertEquals(listOf("💬・discussion"), report.changedChannels)
        assertEquals(plan.channels.first { it.id == "discussion" }.sujet, fake.channels[channel].topic)
        assertEquals("#e0614f", fake.roles[role].color)
        assertTrue(fake.messages.last().second.contains("J'ai modifié le sujet de 1 salon"))
    }

    @Test
    @DisplayName("Les droits du staff dérivés sont signalés à blanc puis corrigés sans nouveau salon")
    fun repairsPrivatePermissions() {
        val fake = FakeDiscordServer()
        apply(fake)
        val staff = fake.categories.first { it.name == "Staff" }
        val journal = fake.channels.first { it.name == "📒・journal-du-bot" }
        val reports = fake.channels.first { it.name == "🚩・signalements" }
        fake.permissionSpecs[staff.id] = Triple(emptySet(), null, false)
        fake.permissionSpecs[journal.id] = Triple(null, emptySet(), false)
        fake.permissionSpecs[reports.id] = Triple(null, emptySet(), false)
        fake.permissionWrites.clear()
        val dry = apply(fake, dryRun = true)
        assertTrue(dry.changedPermissions.containsAll(listOf("Staff", journal.name, reports.name)))
        assertTrue(fake.permissionWrites.isEmpty())
        assertTrue(dry.failures.any { it.contains("journal est visible") })
        val result = apply(fake)
        assertTrue(result.failures.isEmpty())
        assertEquals(setOf(staff.id, journal.id, reports.id), fake.permissionWrites.toSet())
        assertFalse(runBlocking { fake.publicChannelAccess(journal.id) }.view)
        fake.permissionWrites.clear()
        apply(fake)
        assertTrue(fake.permissionWrites.isEmpty())
    }

    @Test
    @DisplayName("Sans journal possible, le compte rendu reste dans les journaux du service et rien ne casse")
    fun noJournal() {
        val fake = FakeDiscordServer(refused = setOf("Staff"))
        val report = apply(fake)
        assertTrue(fake.messages.isEmpty())
        assertTrue(report.failures.any { it.startsWith("la catégorie « Staff »") })
        assertTrue(report.failures.any { it.contains("pas de salon du journal") })
        assertTrue(fake.channels.any { it.name == "💬・discussion" })
    }

    @Test
    @DisplayName("Le compte rendu tient dans un message Discord, même avec des centaines d'échecs")
    fun reportStaysShort() {
        val report = PlanReport(ServerPhase.ATTENTE, dryRun = false)
        repeat(300) { report.failures += "le salon « salon-$it » : refusé" }
        val text = report.text()
        assertTrue(text.length <= PlanReport.MAX_MESSAGE)
        assertTrue(text.endsWith(PlanReport.CUT))
        assertTrue(report.summary().contains("300 échec(s)"))
    }
}
