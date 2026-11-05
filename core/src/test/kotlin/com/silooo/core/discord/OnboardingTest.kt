package com.silooo.core.discord

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** L'accueil guidé : le plan en fait deux questions et les salons publics, posé une fois, repris sans doublon, dit à blanc. */
class OnboardingTest {
    private val plan = ServerPlan.fromClasspath()

    private fun apply(fake: FakeDiscordServer, dryRun: Boolean = false): PlanReport =
        runBlocking { PlanApplier(plan, fake, ServerPhase.ATTENTE, dryRun).run() }

    @Test
    @DisplayName("Sur un serveur vide, le plan pose les deux questions, leurs rôles et les salons publics de l'attente")
    fun placesTheOnboarding() {
        val fake = FakeDiscordServer()
        val report = apply(fake)
        assertEquals(emptyList<String>(), report.failures)

        val state = fake.onboarding
        assertTrue(state.enabled)
        assertEquals(OnboardingState.MODE_DEFAULT, state.mode)
        assertEquals(listOf("Tu joues sur quoi ?", "Tu veux être prévenu de quoi ?"), state.prompts.map { it.title })
        val platform = state.prompts[0]
        assertTrue(platform.singleSelect && platform.required && platform.inOnboarding)
        assertEquals(listOf("Console", "Téléphone", "PC"), platform.options.map { it.title })
        val console = fake.roles.first { it.name == "Console" }
        assertEquals(setOf(console.id), platform.options[0].roleIds)
        assertEquals("🎮", platform.options[0].emoji)
        val notifications = state.prompts[1]
        assertFalse(notifications.singleSelect || notifications.required)
        assertEquals(6, notifications.options.size)

        val public = OnboardingPlanner.publicChannels(plan, ServerPhase.ATTENTE)
        assertEquals(public.map { it.discordName }, state.defaultChannelIds.map { id -> fake.channels.first { it.id == id }.name })
        val journal = fake.channels.first { it.name == "📒・journal-du-bot" }
        assertFalse(state.defaultChannelIds.contains(journal.id), "le journal du staff n'est pas un salon par défaut")
        assertTrue(public.size >= OnboardingPlanner.MIN_DEFAULT_CHANNELS)
        assertTrue(public.count(OnboardingPlanner::everyoneWrites) >= OnboardingPlanner.MIN_WRITABLE_CHANNELS)

        assertTrue(state.prompts.all { prompt -> prompt.id.length > 5 && prompt.options.all { it.id.length > 5 } }, "Discord a remplacé les numéros provisoires")
        assertEquals("posé", report.onboarding?.status)
        val text = fake.messages.single().second
        assertTrue(text.contains("J'ai posé l'accueil guidé : 2 question(s), 9 réponse(s), " + public.size + " salon(s) par défaut dont"), text)
        assertTrue(report.summary().endsWith("accueil guidé posé"), report.summary())
    }

    @Test
    @DisplayName("Un second passage ne renvoie rien ; un salon par défaut en moins est remis, avec les identifiants connus")
    fun secondRunKeepsIds() {
        val fake = FakeDiscordServer()
        apply(fake)
        val first = fake.onboarding
        val again = apply(fake)
        assertEquals(1, fake.onboardingWrites.size)
        assertEquals("en place", again.onboarding?.status)
        assertTrue(fake.messages.last().second.contains("L'accueil guidé est en place"))

        fake.onboarding = first.copy(defaultChannelIds = first.defaultChannelIds.drop(1))
        apply(fake)
        assertEquals(2, fake.onboardingWrites.size)
        val sent = fake.onboardingWrites.last()
        assertEquals(first.prompts.map { it.id }, sent.prompts.map { it.id })
        assertEquals(first.prompts.flatMap { prompt -> prompt.options.map { it.id } }, sent.prompts.flatMap { prompt -> prompt.options.map { it.id } })
        assertEquals(first.defaultChannelIds, fake.onboarding.defaultChannelIds)
    }

    @Test
    @DisplayName("À blanc sur un serveur vide, l'accueil attend les rôles et les salons ; sur un serveur prêt, il dit ce qu'il poserait")
    fun dryRun() {
        val empty = FakeDiscordServer()
        val waiting = apply(empty, dryRun = true)
        assertEquals("en attente", waiting.onboarding?.status)
        assertTrue(empty.onboardingWrites.isEmpty())
        val line = checkNotNull(waiting.onboarding?.line)
        assertTrue(line.startsWith("L'accueil guidé attend : les rôles sans identifiant Discord : console, telephone, pc"), line)
        assertTrue(line.contains(" ; les salons sans identifiant Discord : "), line)
        assertTrue(empty.messages.single().second.contains("L'accueil guidé attend"))

        val ready = FakeDiscordServer()
        apply(ready)
        ready.onboarding = OnboardingState.NONE
        ready.onboardingWrites.clear()
        val report = apply(ready, dryRun = true)
        assertEquals("à blanc", report.onboarding?.status)
        assertTrue(ready.onboardingWrites.isEmpty())
        assertTrue(ready.messages.last().second.contains("À blanc, je poserais l'accueil guidé : 2 question(s)"))
    }

    @Test
    @DisplayName("Un refus de Discord va dans les échecs du compte rendu, sans arrêter le reste")
    fun refusal() {
        val fake = FakeDiscordServer()
        fake.refusedOnboarding = "Discord a refusé de poser l'accueil guidé (HTTP 400) : Invalid Form Body (code 50035)"
        val report = apply(fake)
        assertEquals("refusé", report.onboarding?.status)
        assertEquals(listOf("l'accueil guidé : " + fake.refusedOnboarding), report.failures)
        val text = fake.messages.single().second
        assertTrue(text.contains("Je ne supprime jamais rien.\nÉchecs : l'accueil guidé : Discord a refusé"), text)
    }

    @Test
    @DisplayName("Un plan où trop peu de salons sont ouverts à tout le monde est arrêté avant l'envoi, avec le compte")
    fun tooFewWritableChannels() {
        val channels = (1..8).joinToString(",") { """{"id":"s$it","emoji":"🔧","nom":"s$it","type":"texte"${if (it == 1) "" else ",\"ecriture\":[]"}}""" }
        val text = """{"roles":[{"id":"pc","nom":"PC"}],"categories":[{"id":"c","nom":"C","salons":[$channels]},""" +
            """{"id":"staff","nom":"Staff","visible":["pc"],"salons":[{"id":"journal-du-bot","emoji":"📒","nom":"journal-du-bot","type":"texte"}]}],""" +
            """"accueil":{"questions":[{"id":"q","titre":"Q ?","options":[{"role":"pc","emoji":"💻","titre":"PC"}]}]}}"""
        val small = ServerPlan.parse(text, "plan-de-test")
        val ids = ServerIds(mapOf("pc" to "1"), (1..8).associate { "s$it" to (100 + it).toString() })
        val build = OnboardingPlanner.build(small, ServerPhase.ATTENTE, ids)
        assertEquals(null, build.state)
        assertEquals(listOf("Discord veut 5 salons par défaut où tout le monde écrit, le plan en a 1"), build.problems)
        assertEquals("1 question(s), 1 réponse(s), 8 salon(s) par défaut dont 1 où tout le monde écrit", build.summary)
    }

    @Test
    @DisplayName("La comparaison ignore le sélecteur de variante des emoji, une description vide et l'ordre des salons ; un titre changé fait une réponse neuve")
    fun syncNormalizes() {
        val option = OnboardingOption("900000000000000002", "A", "", "🏗", setOf("1"))
        val current = OnboardingState(listOf(OnboardingPrompt("900000000000000001", "Q", false, false, true, listOf(option))), listOf("5", "6"), enabled = true)
        val wanted = current.copy(
            prompts = listOf(current.prompts[0].copy(id = "1", options = listOf(option.copy(id = "1", description = null, emoji = "🏗️")))),
            defaultChannelIds = listOf("6", "5"),
        )
        assertEquals(null, OnboardingSync.changes(current, wanted))

        val renamed = wanted.copy(prompts = listOf(wanted.prompts[0].copy(options = listOf(wanted.prompts[0].options[0].copy(title = "B")))))
        val sent = checkNotNull(OnboardingSync.changes(current, renamed))
        assertEquals("900000000000000001", sent.prompts[0].id)
        assertEquals("1", sent.prompts[0].options[0].id)
        assertEquals(null, OnboardingSync.changes(current, current.copy(enabled = true)))
        assertTrue(OnboardingSync.changes(current, current.copy(enabled = false)) != null)
    }
}
