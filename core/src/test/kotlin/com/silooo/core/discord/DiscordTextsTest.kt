package com.silooo.core.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les textes Discord : les phrases exactes (toutes à valider par Kr), les accords du français, et les règles de
 * design/README.md (« Écrire sans slop ») qui se vérifient à la machine. Les chiffres viennent des paramètres.
 */
class DiscordTextsTest {
    private val nbsp = " "

    @Test
    @DisplayName("prestige : le nom, le rang de la course, les Lingots gagnés ; 1er puis 2e, 1 Lingot puis 2 Lingots")
    fun prestigeSentences() {
        assertEquals("Les Forges a fait son 1er prestige et gagne 120 Lingots.", DiscordTexts.prestige("Les Forges", 120, 1))
        assertEquals("Les Forges a fait son 2e prestige et gagne 1 Lingot.", DiscordTexts.prestige("Les Forges", 1, 2))
        assertEquals("Les Forges a fait son 12e prestige et gagne 2 Lingots.", DiscordTexts.prestige("Les Forges", 2, 12))
    }

    @Test
    @DisplayName("prestige : sans Lingot gagné, la phrase s'arrête au prestige ; les milliers sont séparés par une espace insécable")
    fun prestigeWithoutLingotsAndBigNumbers() {
        assertEquals("Les Forges a fait son 3e prestige.", DiscordTexts.prestige("Les Forges", 0, 3))
        assertEquals(
            "Les Forges a fait son 1${nbsp}000e prestige et gagne 1${nbsp}234${nbsp}567 Lingots.",
            DiscordTexts.prestige("Les Forges", 1_234_567, 1000),
        )
    }

    @Test
    @DisplayName("voteParty : le nombre de votes atteint, et la récompense seulement si l'événement la donne")
    fun voteParty() {
        assertEquals("Vote party : le serveur a atteint 500 votes, chaque joueur présent reçoit 5 Lingots.", DiscordTexts.voteParty(500, 5))
        assertEquals("Vote party : le serveur a atteint 1${nbsp}500 votes, chaque joueur présent reçoit 1 Lingot.", DiscordTexts.voteParty(1500, 1))
        assertEquals("Vote party : le serveur a atteint 500 votes.", DiscordTexts.voteParty(500, null))
        assertEquals("Vote party : le serveur a atteint 500 votes.", DiscordTexts.voteParty(500, 0))
    }

    @Test
    @DisplayName("collectiveBoost : l'acheteur s'il y en a un, le bonus sans zéro inutile (virgule française), la durée en mots")
    fun collectiveBoost() {
        assertEquals(
            "Zoé offre un boost collectif : production +25 % pour tout le monde pendant une heure.",
            DiscordTexts.collectiveBoost("Zoé", 25.0, 60),
        )
        assertEquals(
            "Boost collectif : production +12,5 % pour tout le monde pendant 1 h 30.",
            DiscordTexts.collectiveBoost(null, 12.5, 90),
        )
        assertEquals(
            "Boost collectif : production +7 % pour tout le monde pendant 15 minutes.",
            DiscordTexts.collectiveBoost(null, 7.04, 15),
        )
    }

    @Test
    @DisplayName("Les durées : minutes, une heure, des heures, heures et minutes")
    fun durations() {
        assertEquals("1 minute", DiscordTexts.durationText(1))
        assertEquals("15 minutes", DiscordTexts.durationText(15))
        assertEquals("59 minutes", DiscordTexts.durationText(59))
        assertEquals("une heure", DiscordTexts.durationText(60))
        assertEquals("1 h 01", DiscordTexts.durationText(61))
        assertEquals("1 h 30", DiscordTexts.durationText(90))
        assertEquals("2 heures", DiscordTexts.durationText(120))
        assertEquals("25 heures", DiscordTexts.durationText(1500))
    }

    @Test
    @DisplayName("noPower : l'usine, son joueur, et le fait")
    fun noPower() {
        assertEquals("L'usine Verrerie de Zoé est à l'arrêt faute de courant.", DiscordTexts.noPower("Zoé", "Verrerie"))
    }

    @Test
    @DisplayName("Les nombres : espaces insécables entre les milliers, rang en lettres collées (1er, 2e, 1 000e), pourcentage sans zéro inutile")
    fun numbers() {
        assertEquals("999", DiscordTexts.number(999))
        assertEquals("1${nbsp}000", DiscordTexts.number(1000))
        assertEquals("1${nbsp}234${nbsp}567", DiscordTexts.number(1_234_567))
        assertEquals("-1${nbsp}000", DiscordTexts.number(-1000))
        assertEquals("1er", DiscordTexts.ordinal(1))
        assertEquals("2e", DiscordTexts.ordinal(2))
        assertEquals("25", DiscordTexts.percentText(25.0))
        assertEquals("12,5", DiscordTexts.percentText(12.5))
        assertEquals("1${nbsp}000", DiscordTexts.percentText(1000.0))
        assertEquals("1 Lingot", DiscordTexts.lingotsText(1))
        assertEquals("0 Lingots", DiscordTexts.lingotsText(0))
    }

    @Test
    @DisplayName("name : un nom de joueur devient une seule ligne propre, coupée à 48 caractères ; rien ne reste d'un nom vide")
    fun names() {
        assertEquals("Les Forges", DiscordTexts.name("  Les   Forges\n"))
        assertEquals("Zoé Forges", DiscordTexts.name("Zoé\r\n\tForges"))
        assertEquals("A B", DiscordTexts.name("A\u0000B"))
        assertEquals("@everyone", DiscordTexts.name("@everyone"), "le texte reste ; les mentions sont coupées à l'envoi, pas ici")
        assertNull(DiscordTexts.name(""))
        assertNull(DiscordTexts.name(" \n\t "))
        val long = DiscordTexts.name("x".repeat(200))!!
        assertEquals(DiscordTexts.MAX_NAME, long.length)
        assertEquals("a".repeat(47), DiscordTexts.name("a".repeat(47) + " " + "b".repeat(30)), "la coupure ne laisse pas d'espace au bout")
    }

    @Test
    @DisplayName("Aucun texte ne contient de point d'exclamation, de question, de points de suspension, de tiret cadratin, d'emoji ni de mot-réclame")
    fun noAiTics() {
        val texts = listOf(
            DiscordTexts.prestige("Les Forges", 120, 1),
            DiscordTexts.prestige("Les Forges", 0, 7),
            DiscordTexts.voteParty(500, 5),
            DiscordTexts.voteParty(500, null),
            DiscordTexts.collectiveBoost("Zoé", 25.0, 60),
            DiscordTexts.collectiveBoost(null, 12.5, 90),
            DiscordTexts.noPower("Zoé", "Verrerie"),
        )
        val banned = listOf(
            "épique", "incroyable", "ultime", "légendaire", "plonge", "découvre", "aventure", "univers", "expérience",
            "immersif", "sans limites", "portée de main", "n'attend que", "meilleur serveur", "félicitations", "bravo",
        )
        for (text in texts) {
            assertFalse(text.contains('!'), text)
            assertFalse(text.contains('?'), text)
            assertFalse(text.contains('…') || text.contains("..."), text)
            assertFalse(text.contains('—') || text.contains('–'), text)
            assertTrue(text.none { Character.isSurrogate(it) || it.code in 0x2190..0x27BF || it.code in 0xFE00..0xFE0F }, "emoji ou pictogramme : $text")
            assertTrue(banned.none { text.lowercase().contains(it) }, text)
            assertTrue(text.endsWith("."), "une phrase, un point final : $text")
            assertEquals(1, text.count { it == '.' }, "une seule phrase : $text")
            assertTrue(text.length < 200, "une ligne de salon, pas un paragraphe : $text")
        }
    }
}
