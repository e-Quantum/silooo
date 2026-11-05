package com.silooo.core.site.pages

import com.silooo.core.definitions.Definitions
import com.silooo.core.site.SiteMode
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.img
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Les cinq visages d'Oooscar, ceux de design/mascotte/ : il n'y en a pas d'autre (design/README.md). [width] est la
 * largeur du PNG à 120 px de haut (les fichiers font 512 px de haut), pour que la page ne bouge pas au chargement.
 */
internal enum class Humeur(val word: String, val alt: String, val width: String) {
    JOIE("joie", "Oooscar, la mascotte de Silooo, content", "109"),
    FIER("fier", "Oooscar, la mascotte de Silooo, fier", "116"),
    OUPS("oups", "Oooscar, la mascotte de Silooo, surpris", "101"),
    PANIQUE("panique", "Oooscar, la mascotte de Silooo, affolé", "95"),
    DODO("dodo", "Oooscar, la mascotte de Silooo, endormi", "94"),
    ;

    val image: String get() = "/public/atelier/oooscar/oooscar-$word.png"
}

/**
 * Ce qu'Oooscar dit en arrivant sur chaque page (docs/site.md, « Oooscar réagit ») : six mots au plus, à valider par Kr.
 * Une page qui n'a pas encore sa phrase (vote, mentions, actus, pionniers, fiches) reçoit celle de l'accueil.
 */
internal object Bulles {
    const val ATTENTE = "On construit. Reviens vite !"
    const val ACCUEIL = "Salut ! Moi, c'est Oooscar."
    const val REJOINDRE = "Je t'attends sur ton île !"
    const val BOUTIQUE = "Les Gemmes arrivent en jeu."
    const val CLASSEMENTS = "Moi, je vise la salle 1."
    const val INTROUVABLE = "Cette page s'est envolée."
    const val ERREUR = "Réessaie dans un moment."

    /** Le guide : le premier palier ×2 des niveaux, lu dans economie.json (`niveaux.paliersX2`), jamais écrit ici. */
    fun guide(definitions: Definitions): String {
        val milestones = definitions.economy.levels.doublingMilestones
        return "Niveau ${milestones.fixed.firstOrNull() ?: milestones.thenEvery} : production ×2 !"
    }

    fun pour(url: String?, mode: SiteMode, definitions: Definitions): String = when {
        url == "/" && mode == SiteMode.ATTENTE -> ATTENTE
        url == "/rejoindre/" -> REJOINDRE
        url == "/boutique/" || url == "/ce-qui-se-vend/" -> BOUTIQUE
        url == "/classements/" -> CLASSEMENTS
        url != null && url.startsWith("/wiki/") -> guide(definitions)
        else -> ACCUEIL
    }
}

/**
 * Ce qu'Oooscar dit quand on le touche, l'attrape, le réveille, ou quand la Vote party est atteinte : les phrases du kit
 * (docs/references/site/oooscar-vivant.html), à valider par Kr. Elles vivent ici, en Kotlin, pour que `tools/anti_ia.py`
 * les relise ; `oooscar.js` les lit sur `data-textes` et n'en contient aucune.
 */
internal object Reactions {
    val touche = listOf("Hé, ça chatouille !", "Encore une fois !", "Doucement, je suis en tôle.", "Ta Scierie t'attend !", "Mon toit glisse, là.", "Tu me cherches ?")
    const val TOIT = "Mon toit !"
    const val NEUF = "Comme neuf."
    const val REPARE = "Je remets mon toit."
    const val ATTRAPE = "Repose-moi !"
    const val CHUTE = "Ouille."
    const val SALUT = "Salut !"
    const val REVEIL = "Je dormais pas !"
    const val NUIT = "Les usines tournent. Moi, je dors."
    const val VOTE_PARTY = "Production ×2 pour tout le monde !"

    /** Le JSON posé sur `data-textes`, une fois pour toutes. */
    val json: String = buildJsonObject {
        putJsonArray("touche") { for (text in touche) add(text) }
        put("toit", TOIT)
        put("neuf", NEUF)
        put("repare", REPARE)
        put("attrape", ATTRAPE)
        put("chute", CHUTE)
        put("salut", SALUT)
        put("reveil", REVEIL)
        put("nuit", NUIT)
        put("voteParty", VOTE_PARTY)
    }.toString()
}

/**
 * La scène sous chaque page : trois nuages, Oooscar debout sur le sol avec sa bulle. Sans script, l'image et la bulle de
 * la page restent telles quelles ; `oooscar.js` remplace l'image par le SVG articulé et lit l'humeur, la bulle et les
 * textes des réactions sur les attributs `data-`. La bulle est une zone de statut : un lecteur d'écran entend ce qu'il dit.
 */
internal fun FlowContent.scene(humeur: Humeur, bulle: String) {
    div(classes = "scene") {
        div(classes = "boite scene__boite") {
            for (n in 1..3) span(classes = "nuage nuage--$n") { attributes["aria-hidden"] = "true" }
            div(classes = "oooscar") {
                attributes["data-humeur"] = humeur.word
                attributes["data-bulle"] = bulle
                attributes["data-textes"] = Reactions.json
                img(src = humeur.image, alt = humeur.alt, classes = "sa-oooscar") {
                    width = humeur.width
                    height = "120"
                }
                p(classes = "sa-bulle") {
                    attributes["role"] = "status"
                    +bulle
                }
            }
        }
        div(classes = "sol") { attributes["aria-hidden"] = "true" }
    }
}
