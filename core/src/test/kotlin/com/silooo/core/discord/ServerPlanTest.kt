package com.silooo.core.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le plan du serveur : celui du dépôt est bon, et chaque incohérence que le schéma ne voit pas est refusée. */
class ServerPlanTest {
    private val real = ServerPlan.fromClasspath()

    @Test
    @DisplayName("Le plan du dépôt se lit, passe ses contrôles, a son journal et des entrées de chaque phase")
    fun realPlanIsValid() {
        assertEquals(emptyList<String>(), real.check())
        val journal = checkNotNull(real.journal)
        assertEquals(ChannelKind.TEXTE, journal.type)
        assertEquals("📒・journal-du-bot", journal.discordName)
        assertTrue(real.roles.any { it.phase == PlanPhase.ATTENTE })
        assertTrue(real.categories.any { it.phase == PlanPhase.BETA })
        assertTrue(real.channels.any { it.type == ChannelKind.VOCAL && it.verrouille })
    }

    @Test
    @DisplayName("Le nom Discord : emoji, point médian et nom pour un salon écrit ; emoji, espace et nom pour un vocal")
    fun discordNames() {
        assertEquals("👋・bienvenue", PlannedChannel("a", "👋", "bienvenue", ChannelKind.TEXTE).discordName)
        assertEquals("➕ Crée ton vocal", PlannedChannel("b", "➕", "Crée ton vocal", ChannelKind.VOCAL).discordName)
    }

    @Test
    @DisplayName("Une phase du plan existe dans la phase courante et les suivantes, pas avant")
    fun phases() {
        assertTrue(PlanPhase.ATTENTE.existsIn(ServerPhase.ATTENTE))
        assertTrue(PlanPhase.ATTENTE.existsIn(ServerPhase.OUVERT))
        assertTrue(PlanPhase.BETA.existsIn(ServerPhase.BETA))
        assertTrue(!PlanPhase.BETA.existsIn(ServerPhase.ATTENTE))
        assertTrue(!PlanPhase.OUVERT.existsIn(ServerPhase.BETA))
    }

    @Test
    @DisplayName("Un identifiant ou un nom en double est refusé")
    fun duplicates() {
        val roles = """[{"id":"staff","nom":"Staff"},{"id":"staff","nom":"Autre"}]"""
        assertTrue(refused(plan(roles = roles)).contains("« staff » est en double"))
        val names = """[{"id":"a","nom":"Staff"},{"id":"b","nom":"Staff"}]"""
        assertTrue(refused(plan(roles = names)).contains("« Staff » est en double"))
    }

    @Test
    @DisplayName("Un rôle cité par un salon ou une catégorie doit exister")
    fun unknownRole() {
        val categories = """[$JOURNAL_CATEGORY, {"id":"jeu","nom":"Le jeu","salons":[
            {"id":"tchat","emoji":"🎮","nom":"tchat","type":"texte","ecriture":["fantome"]}]}]"""
        assertTrue(refused(plan(categories = categories)).contains("rôle inconnu « fantome »"))
    }

    @Test
    @DisplayName("L'emoji va dans sa clé, jamais dans le nom ; un nom de salon écrit reste en minuscules sans espace")
    fun names() {
        val emojiInName = """[$JOURNAL_CATEGORY, {"id":"jeu","nom":"Le jeu","salons":[
            {"id":"tchat","emoji":"🎮","nom":"🎮tchat","type":"texte"}]}]"""
        assertTrue(refused(plan(categories = emojiInName)).contains("contient un emoji"))
        val upper = """[$JOURNAL_CATEGORY, {"id":"jeu","nom":"Le jeu","salons":[
            {"id":"tchat","emoji":"🎮","nom":"Tchat du jeu","type":"texte"}]}]"""
        assertTrue(refused(plan(categories = upper)).contains("minuscules"))
        val vocal = """[$JOURNAL_CATEGORY, {"id":"jeu","nom":"Le jeu","salons":[
            {"id":"vocal","emoji":"🎮","nom":"Crée ton vocal","type":"vocal"}]}]"""
        assertEquals(emptyList<String>(), ServerPlan.parse(plan(categories = vocal)).check())
    }

    @Test
    @DisplayName("Un salon n'apparaît pas avant sa catégorie, une couleur s'écrit #rrggbb, et le journal est obligatoire")
    fun consistency() {
        val early = """[$JOURNAL_CATEGORY, {"id":"jeu","nom":"Le jeu","phase":"beta","salons":[
            {"id":"tchat","emoji":"🎮","nom":"tchat","type":"texte"}]}]"""
        assertTrue(refused(plan(categories = early)).contains("apparaît avant sa catégorie"))
        assertTrue(refused(plan(roles = """[{"id":"staff","nom":"Staff","couleur":"rouge"}]""")).contains("#rrggbb"))
        val noJournal = """[{"id":"jeu","nom":"Le jeu","salons":[{"id":"tchat","emoji":"🎮","nom":"tchat","type":"texte"}]}]"""
        assertTrue(refused(plan(categories = noJournal)).contains(ServerPlan.JOURNAL_ID))
    }

    @Test
    @DisplayName("Une clé inconnue ou un JSON cassé sont refusés avec l'origine du plan")
    fun unknownKey() {
        assertTrue(refused("""{"roles": [], "categories": [$JOURNAL_CATEGORY], "couleur": "x"}""").contains("plan-de-test"))
        assertTrue(refused("{ pas du json").contains("plan-de-test"))
    }

    @Test
    @DisplayName("L'accueil guidé : deux questions dans le dépôt ; un rôle inconnu, un rôle en double ou un emoji hors de sa clé sont refusés")
    fun onboarding() {
        val onboarding = checkNotNull(real.accueil)
        assertEquals(listOf("plateforme", "notifications"), onboarding.questions.map { it.id })
        assertTrue(onboarding.questions[0].unique && onboarding.questions[0].obligatoire)
        assertTrue(onboarding.questions.all { question -> question.options.all { option -> real.roles.any { it.id == option.role } } })

        fun accueil(options: String, titre: String = "Q ?") = """{"questions":[{"id":"q","titre":"$titre","options":[$options]}]}"""
        assertTrue(refused(plan(accueil = accueil("""{"role":"fantome","emoji":"🎮","titre":"A"}"""))).contains("cite le rôle inconnu « fantome »"))
        val twice = """{"role":"staff","emoji":"🎮","titre":"A"},{"role":"staff","emoji":"📱","titre":"B"}"""
        assertTrue(refused(plan(accueil = accueil(twice))).contains("le rôle « staff » est en double"))
        assertTrue(refused(plan(accueil = accueil("""{"role":"staff","emoji":"🎮","titre":"A"}""", titre = "Q 🎮 ?"))).contains("emoji dans son titre"))
        assertTrue(refused(plan(accueil = accueil("""{"role":"staff","emoji":"🎮","titre":"A 🎮"}"""))).contains("il va dans la clé emoji"))
        assertTrue(refused(plan(accueil = """{"questions":[{"id":"q","titre":"Q ?","options":[]}]}""")).contains("n'a pas de réponse"))
    }

    private fun plan(roles: String = """[{"id":"staff","nom":"Staff"}]""", categories: String = "[$JOURNAL_CATEGORY]", accueil: String? = null) =
        """{"roles": $roles, "categories": $categories""" + (if (accueil == null) "" else """, "accueil": $accueil""") + "}"

    private fun refused(text: String): String =
        assertThrows(PlanException::class.java) { ServerPlan.parse(text, "plan-de-test") }.message.orEmpty()

    private companion object {
        const val JOURNAL_CATEGORY =
            """{"id":"staff","nom":"Staff","salons":[{"id":"journal-du-bot","emoji":"📒","nom":"journal-du-bot","type":"texte"}]}"""
    }
}
