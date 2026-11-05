package com.silooo.core.wording

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Les cas de tools/tests/anti-ia/ (signaler, propre, grade-ultime) et des cas courts par règle. Les textes de ces tests
 * contiennent des mots bannis exprès : le garde de fichiers ne lit pas core/src/test.
 */
class AntiIaTest {
    private fun rules(text: String) = AntiIa.check(text).map { it.rule }

    private fun motsBannis(text: String) = rules(text).filter { it.startsWith("mot-banni") }

    // ---------------------------------------------------------------- tools/tests/anti-ia/signaler/

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "Un monde épique t'attend.",
            "C'est incroyable.",
            "Le pack ultime.",
            "Une quête légendaire.",
            "Plonge dans la partie.",
            "Découvre la Fonderie.",
            "Une aventure sur ton île.",
            "Tout l'univers des usines.",
            "Une expérience unique.",
            "Un décor immersif.",
            "Des usines sans limites.",
            "Ta Scierie à portée de main.",
            "Le Silo n'attend que toi.",
            "Le meilleur serveur de Bedrock.",
        ],
    )
    @DisplayName("signaler/mot-banni.txt : chaque ligne donne un mot banni, et un seul")
    fun motBanniParLigne(line: String) {
        val found = rules(line)
        assertEquals(1, found.count { it.startsWith("mot-banni (« ") && it.endsWith(" »)") }, found.toString())
        assertEquals(1, found.size, found.toString())
    }

    @Test
    @DisplayName("signaler/mot-banni.txt en bloc : quatorze mots bannis, un par ligne")
    fun motBanniEnBloc() {
        val block = """
            Un monde épique t'attend.
            C'est incroyable.
            Le pack ultime.
            Une quête légendaire.
            Plonge dans la partie.
            Découvre la Fonderie.
            Une aventure sur ton île.
            Tout l'univers des usines.
            Une expérience unique.
            Un décor immersif.
            Des usines sans limites.
            Ta Scierie à portée de main.
            Le Silo n'attend que toi.
            Le meilleur serveur de Bedrock.
        """.trimIndent()
        assertEquals(14, rules(block).count { it.startsWith("mot-banni") })
    }

    @Test
    @DisplayName("signaler/emoji.txt")
    fun emojiFichier() {
        assertEquals(listOf("emoji"), rules("Ta Scierie 🔥 produit 4 200 Donar/s."))
        assertEquals(listOf("emoji"), rules("Prestige disponible ⭐"))
    }

    @Test
    @DisplayName("signaler/exclamations-en-serie.txt")
    fun exclamationsFichier() {
        assertEquals(listOf("exclamations-en-serie"), rules("Fonderie débloquée !!"))
    }

    @Test
    @DisplayName("signaler/majuscules-d-insistance.txt et la mention de Mojang modifiée")
    fun majusculesFichiers() {
        assertEquals(listOf("majuscules-d-insistance"), rules("Achète MAINTENANT ta Scierie."))
        val modifiee = "Silooo est un serveur indépendant. NOT AN OFFICIAL SERVICE. NOT APPROVED BY OR ASSOCIATED " +
            "WITH MOJANG OR MICROSOFT. Minecraft est une marque de Mojang AB."
        assertTrue("majuscules-d-insistance" in rules(modifiee), "l'exception ne couvre que le texte exact")
    }

    @Test
    @DisplayName("signaler/mot-banni--ultime-hors-grade.txt")
    fun ultimeHorsGrade() {
        assertEquals(listOf("mot-banni (« Ultime »)"), rules("Le pack Ultime est arrivé."))
    }

    @Test
    @DisplayName("signaler/points-de-suspension.txt, que-tu-sois.txt, question-de-pub.txt, tiret-cadratin.txt")
    fun autresFichiers() {
        assertEquals(listOf("points-de-suspension"), rules("Chargement..."))
        assertEquals(listOf("points-de-suspension"), rules("Attends… la Fonderie arrive."))
        assertEquals(listOf("que-tu-sois"), rules("Que tu sois novice ou expert, la Scierie produit."))
        assertEquals(listOf("question-de-pub"), rules("Prêt à bâtir ton empire ?"))
        assertEquals(listOf("question-de-pub"), rules("Tout est en place. Prête à produire ?"))
        assertEquals(listOf("tiret-cadratin"), rules("La Scierie — ta première usine."))
    }

    @Test
    @DisplayName("signaler/*.kt et *.ts, *.json : les chaînes de ces fichiers donnent les mêmes règles")
    fun litteraux() {
        assertEquals(
            listOf("mot-banni (« Plonge »)", "mot-banni (« aventure »)", "exclamations-en-serie"),
            rules("Plonge dans l'aventure !!"),
        )
        assertEquals(
            listOf("mot-banni (« expérience »)"),
            rules("Une première ligne propre.\n  Une expérience unique."),
        )
        assertEquals(listOf("mot-banni (« Plonge »)"), rules("Plonge dans la Fonderie"))
    }

    // ---------------------------------------------------------------- tools/tests/anti-ia/propre/

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "Construis des usines sur ton île.",
            "Fonderie débloquée.",
            "Production ×2 pendant 15 min.",
            "Il te manque 240 k Donar.",
            "Ta Scierie produit 4 200 Donar/s.",
            "Tu as 3 usines à améliorer.",
            "La Fonderie t'attend !",
            "Le Silo est prêt à être récolté.",
            "SCIERIE",
            "NIV. 47 · ÉPOQUE 1",
            "Le HUD affiche le gain par seconde.",
            "Le PDG invite ses associés VIP et MVIP.",
            "Grade Ultime : bonus de Donar maximal.",
            "Le grade Légende coûte 3 500 Gemmes.",
            "Silooo est un serveur indépendant. NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED " +
                "WITH MOJANG OR MICROSOFT. Minecraft est une marque de Mojang AB et de Microsoft.",
        ],
    )
    @DisplayName("propre/ : ces lignes ne donnent rien")
    fun propre(line: String) {
        assertEquals(emptyList<Finding>(), AntiIa.check(line))
    }

    @Test
    @DisplayName("propre/propre.txt en bloc : rien, titres en capitales compris")
    fun propreEnBloc() {
        val block = """
            Construis des usines sur ton île.
            Fonderie débloquée.
            SCIERIE
            NIV. 47 · ÉPOQUE 1
            Le HUD affiche le gain par seconde.
            Grade Ultime : bonus de Donar maximal.
        """.trimIndent()
        assertEquals(emptyList<Finding>(), AntiIa.check(block))
    }

    // ---------------------------------------------------------------- tools/tests/anti-ia/grade-ultime/

    @Test
    @DisplayName("grade-ultime/ : « grade Ultime » et « grade Légendaire » ne donnent pas de mot banni")
    fun gradeUltime() {
        for (text in listOf(
            "Le grade Ultime donne le plus gros bonus de Donar.",
            "Grade Ultime",
            "grade ultime",
            "Le grade Légendaire est ouvert.",
            "Le grade « Ultime » est ouvert.",
            "Le grade \"Légendaire\" est ouvert.",
            "Le grade\n  Ultime est ouvert.",
        )) {
            assertEquals(emptyList<String>(), rules(text), text)
        }
    }

    @Test
    @DisplayName("Hors de « grade », Ultime et Légendaire restent signalés, y compris seuls")
    fun ultimeSansGrade() {
        assertEquals(listOf("mot-banni (« Ultime »)"), rules("Ultime"))
        assertEquals(listOf("mot-banni (« Légendaire »)"), rules("Un trésor Légendaire."))
        assertEquals(listOf("mot-banni (« Ultime »)"), rules("Pack Ultime"))
        assertEquals(listOf("mot-banni (« Ultime »)"), rules("Le grade Rare. Ultime en vue."))
    }

    // ---------------------------------------------------------------- mots bannis : casse, accents, bords

    @Test
    @DisplayName("mot-banni : la casse et les accents ne changent rien, le mot est rendu tel qu'écrit")
    fun motBanniCasseEtAccents() {
        for (variante in listOf("épique", "Épique", "ÉPIQUE", "epique", "EPIQUE", "épiques", "Épiques")) {
            assertEquals(listOf("mot-banni (« $variante »)"), motsBannis("Un $variante monde."), variante)
        }
        assertEquals(listOf("mot-banni (« DÉCOUVRE »)"), motsBannis("DÉCOUVRE la Fonderie."))
        assertEquals(listOf("mot-banni (« Immersive »)"), rules("Une carte Immersive."))
        assertEquals(listOf("mot-banni (« LÉGENDAIRES »)"), motsBannis("Des objets LÉGENDAIRES."))
        // Les capitales d'insistance sont signalées en plus, par leur propre règle.
        assertEquals(listOf("mot-banni (« ÉPIQUE »)", "majuscules-d-insistance"), rules("Un ÉPIQUE monde."))
    }

    @Test
    @DisplayName("mot-banni : les mots à bornes, pas les mots voisins")
    fun motBanniBornes() {
        assertEquals(emptyList<String>(), rules("Le plongeur répare l'universel."))
        assertEquals(emptyList<String>(), rules("Les aventuriers des usines."))
        assertEquals(emptyList<String>(), rules("Une ultimatum, des incroyablement."))
        assertEquals(listOf("mot-banni (« univers »)"), rules("L'univers des usines."))
        assertEquals(listOf("mot-banni (« univers »)"), rules("L’univers des usines."))
        assertEquals(listOf("mot-banni (« plongez »)"), rules("Plus que jamais, plongez ici."))
    }

    @Test
    @DisplayName("mot-banni : espaces insécables, apostrophe courbe, expressions de plusieurs mots")
    fun motBanniExpressions() {
        assertEquals(listOf("mot-banni (« sans limites »)"), rules("Des usines sans limites."))
        assertEquals(listOf("mot-banni (« sans   limite »)"), rules("Des usines sans   limite."))
        assertEquals(listOf("mot-banni (« n’attend que toi »)"), rules("Le Silo n’attend que toi."))
        assertEquals(listOf("mot-banni (« à portée de main »)"), rules("Ta Scierie à portée de main."))
        assertEquals(
            listOf("mot-banni (« LE MEILLEUR SERVEUR »)"),
            motsBannis("Chez nous, LE MEILLEUR SERVEUR de Bedrock."),
        )
    }

    // ---------------------------------------------------------------- les autres règles

    @Test
    @DisplayName("question-de-pub : en début de ligne ou de phrase seulement, avec la casse et les accents")
    fun questionDePub() {
        for (text in listOf(
            "Prêt à jouer ?",
            "PRÊTE À JOUER ?",
            "Prets a jouer ?",
            "Prêts à jouer ?",
            "Bienvenue.\nPrêt à jouer ?",
            "Bienvenue. Prêt à jouer ?",
            "Bienvenue :\r\nPrêt à jouer ?",
            "- Prêt à jouer ?",
            "> Prêt à jouer ?",
            "“Prêt à jouer ?”",
            "(Prêt à jouer ?)",
            "Viens. Prêt à jouer ?",
        )) {
            assertEquals(listOf("question-de-pub"), rules(text), text)
        }
        assertEquals(emptyList<String>(), rules("Le Silo est prêt à être récolté."))
        assertEquals(emptyList<String>(), rules("Es-tu prêt à jouer ?"))
        assertEquals(emptyList<String>(), rules("Un client est prêtre à Paris."))
        // Le guillemet ouvrant « n'est pas dans la liste du Python : seuls les signes qui ferment une phrase comptent.
        assertEquals(emptyList<String>(), rules("« Prêt à jouer ? »"))
    }

    @Test
    @DisplayName("que-tu-sois : tutoiement et vouvoiement, casse, espaces")
    fun queTuSois() {
        assertEquals(listOf("que-tu-sois", "majuscules-d-insistance"), rules("QUE TU SOIS novice ou expert."))
        assertEquals(listOf("que-tu-sois"), rules("Que vous soyez novice ou expert."))
        assertEquals(listOf("que-tu-sois"), rules("Bien que  tu sois là."))
        assertEquals(listOf("que-tu-sois"), rules("Je veux que tu sois là."), "la règle est large, comme en Python")
        assertEquals(emptyList<String>(), rules("Tu sois sage, que toi."))
    }

    @Test
    @DisplayName("points-de-suspension : trois points et plus, ou le caractère unique")
    fun pointsDeSuspension() {
        assertEquals(listOf("points-de-suspension"), rules("Chargement...."))
        assertEquals(listOf("points-de-suspension"), rules("Chargement…"))
        assertEquals(emptyList<String>(), rules("Voir page 3.. ou plus loin."))
        assertEquals(emptyList<String>(), rules("Chargement."))
    }

    @Test
    @DisplayName("exclamations-en-serie : deux points d'exclamation et plus")
    fun exclamationsEnSerie() {
        assertEquals(listOf("exclamations-en-serie"), rules("Bravo !!!"))
        assertEquals(listOf("exclamations-en-serie"), rules("Bravo!!"))
        assertEquals(emptyList<String>(), rules("Bravo !"))
        assertEquals(emptyList<String>(), rules("Bravo ! Encore !"))
    }

    @Test
    @DisplayName("emoji : pictogrammes, symboles, sélecteur de variante et liant")
    fun emoji() {
        for (text in listOf("Bravo 🔥", "Bravo ✨", "Bravo ⭐", "Bravo ❤️", "Bravo ‍", "Bravo 🏆", "Bravo ✅")) {
            assertEquals(listOf("emoji"), rules(text), text)
        }
        assertEquals(emptyList<String>(), rules("Production ×2 · niveau 3 → 4 – ok"))
    }

    @Test
    @DisplayName("tiret-cadratin : le long tiret, pas le trait d'union ni le demi-cadratin")
    fun tiretCadratin() {
        assertEquals(listOf("tiret-cadratin"), rules("Bravo — encore."))
        assertEquals(emptyList<String>(), rules("Peut-être – ou non - encore."))
    }

    @Test
    @DisplayName("majuscules-d-insistance : quatre lettres et plus, accents compris, si la ligne a une minuscule")
    fun majuscules() {
        assertEquals(listOf("majuscules-d-insistance"), rules("C'est ÉNORME."))
        assertEquals(listOf("majuscules-d-insistance"), rules("Achète UNIQUEMENT ici."))
        assertEquals(emptyList<String>(), rules("Achète TOU ici."))
        assertEquals(emptyList<String>(), rules("UNIQUEMENT"))
        assertEquals(emptyList<String>(), rules("ÉNORME ET UNIQUE"))
    }

    @Test
    @DisplayName("majuscules-d-insistance : sigles permis, mention de Mojang masquée pour cette seule règle")
    fun majusculesExceptions() {
        assertEquals(emptyList<String>(), rules("Envoie un POST au HUD, via HTTPS, en JSON."))
        assertEquals(emptyList<String>(), rules("Ton UUID et ton XUID sont dans le TODO."))
        assertEquals(listOf("majuscules-d-insistance"), rules("Envoie un PUTS au HUD."))
        val mention = "NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."
        assertEquals(emptyList<String>(), rules("Silooo est indépendant. $mention"))
        assertEquals(
            listOf("mot-banni (« épique »)", "exclamations-en-serie"),
            rules("Silooo est indépendant. $mention C'est épique !!"),
        )
        assertEquals(listOf("emoji"), rules("Silooo est indépendant. $mention 🔥"))
    }

    @Test
    @DisplayName("majuscules-d-insistance : un titre en capitales seul sur sa ligne passe, au milieu d'une phrase non")
    fun majusculesParLigne() {
        assertEquals(emptyList<String>(), rules("MAINTENANCE\nLe serveur redémarre à 22 h."))
        assertEquals(listOf("majuscules-d-insistance"), rules("Info : MAINTENANCE ce soir."))
        assertEquals(listOf("majuscules-d-insistance"), rules("Titre\nInfo : MAINTENANCE ce soir.\nFin."))
    }

    // ---------------------------------------------------------------- extraits, ordre, doublons

    @Test
    @DisplayName("L'extrait est la ligne qui contient la trouvaille, sans les blancs autour")
    fun extraitEstLaLigne() {
        val found = AntiIa.check("Première ligne propre.\r\n   Un monde épique t'attend.   \r\nTroisième ligne.")
        assertEquals(listOf(Finding("mot-banni (« épique »)", "Un monde épique t'attend.")), found)
    }

    @Test
    @DisplayName("L'extrait d'une ligne de plus de 100 caractères est rogné autour de la trouvaille")
    fun extraitRogne() {
        val avant = "a".repeat(60) + " "
        val apres = " " + "b".repeat(60)
        val found = AntiIa.check(avant + "épique" + apres).single()
        assertEquals("…" + avant.takeLast(35) + "épique" + apres.take(35) + "…", found.excerpt)
        val enDebut = AntiIa.check("épique" + apres + apres).single()
        assertEquals("épique" + apres.take(35) + "…", enDebut.excerpt)
        val enFin = AntiIa.check(avant + avant + "épique").single()
        assertEquals("…" + avant.takeLast(35) + "épique", enFin.excerpt)
    }

    @Test
    @DisplayName("Les trouvailles suivent l'ordre du texte, un mot répété sur la même ligne n'est signalé qu'une fois")
    fun ordreEtDoublons() {
        val found = AntiIa.check("Un 🔥 monde épique !! et épique, Épique.")
        assertEquals(
            listOf("emoji", "mot-banni (« épique »)", "exclamations-en-serie", "mot-banni (« Épique »)"),
            found.map { it.rule },
        )
        assertEquals(
            listOf("mot-banni (« épique »)", "mot-banni (« épique »)"),
            AntiIa.check("Un monde épique.\nUn autre monde épique.").map { it.rule },
        )
    }

    @Test
    @DisplayName("Le pliage garde la longueur du texte")
    fun pliageGardeLaLongueur() {
        val text = "Épique ’ œuvre 🔥 Crème brûlée ÀÉÎÕÜ"
        assertEquals(text.length, AntiIa.fold(text).length)
        assertEquals("Epique ' œuvre 🔥 Creme brulee AEIOU", AntiIa.fold(text))
    }

    @Test
    @DisplayName("Un texte vide ou propre ne donne rien")
    fun texteVide() {
        assertEquals(emptyList<Finding>(), AntiIa.check(""))
        assertEquals(emptyList<Finding>(), AntiIa.check("Le serveur redémarre à 22 h."))
    }

    // ---------------------------------------------------------------- summary

    @Test
    @DisplayName("summary : une phrase avec les règles distinctes, dans l'ordre du texte")
    fun summaryUnePhrase() {
        assertEquals("Tics d'IA : mot-banni (« épique »), emoji.", AntiIa.summary(AntiIa.check("Un monde épique 🔥")))
        assertEquals("Tics d'IA : emoji.", AntiIa.summary(AntiIa.check("🔥 Bravo 🔥 encore 🔥")))
    }

    @Test
    @DisplayName("summary : un texte avec plusieurs règles")
    fun summaryPlusieursRegles() {
        val text = "Prêt à jouer ? Un monde épique… 🔥 Achète MAINTENANT — vite !! Que tu sois là, découvre-le."
        assertEquals(
            "Tics d'IA : question-de-pub, mot-banni (« épique »), points-de-suspension, emoji, " +
                "majuscules-d-insistance, tiret-cadratin, exclamations-en-serie, que-tu-sois, mot-banni (« découvre »).",
            AntiIa.summary(AntiIa.check(text)),
        )
    }

    @Test
    @DisplayName("summary : sans doublon, et une phrase pour un texte propre")
    fun summarySansDoublon() {
        val twice = listOf(Finding("emoji", "a"), Finding("tiret-cadratin", "b"), Finding("emoji", "c"))
        assertEquals("Tics d'IA : emoji, tiret-cadratin.", AntiIa.summary(twice))
        assertEquals("Aucun tic d'IA.", AntiIa.summary(emptyList()))
    }
}
