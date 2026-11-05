package com.silooo.core.site.pages

import com.silooo.core.registration.GamertagProblem
import com.silooo.core.registration.SiteRegistration
import com.silooo.core.registration.SiteRegistrations
import com.silooo.core.site.ContentPage
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.strong
import kotlinx.html.ul

/** Les pages qui ne sont ni le formulaire ni l'état : une phrase, et où retourner. */
internal enum class PioneerMessage(val title: String, val text: String, val backUrl: String, val backLabel: String) {
    TOO_MANY("Trop d'essais", "Trop d'essais. Réessaie dans une minute.", "/pionniers/", "Retour aux pionniers"),
    TOO_BIG("Formulaire trop long", "Ce formulaire est trop long. Écris seulement ton gamertag.", "/pionniers/", "Retour aux pionniers"),
    FORM_EXPIRED("Formulaire expiré", "Le formulaire a expiré. Envoie-le de nouveau.", "/pionniers/", "Retour aux pionniers"),
    RULES_UNCHECKED("Règles", "Coche la case pour dire que tu as lu les règles.", "/pionniers/", "Retour aux pionniers"),
    DISCORD_FAILED("Relier Discord", "Relier Discord n'a pas abouti. Reprends depuis ton inscription.", "/pionniers/etat/", "Retour à mon inscription"),
    DISCORD_DOWN("Relier Discord", "Discord ne répond pas pour l'instant. Réessaie dans un moment.", "/pionniers/etat/", "Retour à mon inscription"),
    DISCORD_TAKEN("Relier Discord", "Ce compte Discord est déjà relié à un autre joueur.", "/pionniers/etat/", "Retour à mon inscription"),
}

/** La phrase que lit le visiteur dont le gamertag est refusé. */
internal fun GamertagProblem.problemText(): String = when (this) {
    GamertagProblem.EDGE_SPACE -> "Retire l'espace au début ou à la fin de ton gamertag."
    GamertagProblem.BAD_CHARACTER -> "Ton gamertag ne contient que des lettres sans accent, des chiffres et des espaces."
    GamertagProblem.DOUBLE_SPACE -> "Mets un seul espace entre deux mots."
    GamertagProblem.TOO_SHORT -> "Ton gamertag a ${SiteRegistrations.MIN_LENGTH} caractères au moins."
    GamertagProblem.TOO_LONG -> "Ton gamertag a ${SiteRegistrations.MAX_LENGTH} caractères au plus."
}

/** Ce que la page du formulaire affiche : le jeton caché, le lien des règles, le compteur (`null` si illisible), le gamertag déjà tapé et l'erreur. */
internal class PioneerFormView(
    val token: String,
    val rulesUrl: String,
    val count: Long?,
    val gamertag: String = "",
    val error: String? = null,
)

/** Ce que la page d'état affiche. Jamais l'identifiant Discord : la vue ne le porte pas. */
internal class PioneerStateView(
    val registration: SiteRegistration,
    /** Vrai quand le visiteur vient de retaper un gamertag déjà inscrit. */
    val already: Boolean,
    /** « Relier Discord » est proposé : l'application existe et le compte n'est pas encore relié. */
    val discordButton: Boolean,
    /** L'admission attend Discord, qui n'est pas encore branché. */
    val discordPending: Boolean,
)

/** Le nombre de pionniers, en une phrase ; les milliers sont séparés par une espace fine insécable. */
internal fun countText(count: Long): String = when (count) {
    0L -> "Aucun pionnier inscrit pour l'instant."
    1L -> "1 pionnier inscrit"
    else -> "${thousands(count)} pionniers inscrits"
}

private fun thousands(value: Long): String = value.toString().reversed().chunked(3).joinToString(" ").reversed()

/** `/pionniers/` : le texte du contenu, le compteur et le formulaire. Un seul bouton rouge : « M'inscrire ». */
internal fun HTML.pionniers(shell: SiteShell, page: ContentPage, view: PioneerFormView) =
    gabarit(shell, PageFrame(page.title, page.description, page.url)) {
        lecture(page.title) {
            rendered(page.html)
            view.count?.let { p(classes = "compteur") { +countText(it) } }
            form(action = "/pionniers/", method = FormMethod.post, classes = "formulaire") {
                attributes["autocomplete"] = "off"
                view.error?.let { p(classes = "formulaire__erreur") { attributes["role"] = "alert"; +it } }
                div(classes = "formulaire__champ") {
                    label { attributes["for"] = "gamertag"; +"Ton gamertag" }
                    input(type = InputType.text, name = "gamertag", classes = "formulaire__saisie") {
                        id = "gamertag"
                        value = view.gamertag
                        required = true
                        maxLength = SiteRegistrations.MAX_LENGTH.toString()
                        attributes["minlength"] = SiteRegistrations.MIN_LENGTH.toString()
                        attributes["pattern"] = "[A-Za-z0-9]+( [A-Za-z0-9]+)*"
                        attributes["autocapitalize"] = "none"
                        attributes["spellcheck"] = "false"
                        attributes["aria-describedby"] = "gamertag-aide"
                    }
                    p(classes = "formulaire__aide") {
                        id = "gamertag-aide"
                        +"${SiteRegistrations.MIN_LENGTH} à ${SiteRegistrations.MAX_LENGTH} caractères : lettres, chiffres et espaces simples."
                    }
                }
                div(classes = "formulaire__case") {
                    input(type = InputType.checkBox, name = "regles", classes = "formulaire__coche") {
                        id = "regles"
                        value = "oui"
                        required = true
                    }
                    label {
                        attributes["for"] = "regles"
                        +"J'ai lu les "
                        a(href = view.rulesUrl) { +"règles" }
                    }
                }
                input(type = InputType.hidden, name = "jeton") { value = view.token }
                button(type = ButtonType.submit, classes = "sa-bouton") { +"M'inscrire" }
            }
        }
    }

/** `/pionniers/etat/` : ce que la table sait de l'inscription, et « Relier Discord » quand c'est possible. */
internal fun HTML.pionniersEtat(shell: SiteShell, page: ContentPage, view: PioneerStateView) =
    gabarit(shell, PageFrame("Ton inscription", page.description, page.url)) {
        lecture("Ton inscription") {
            if (view.already) p { +"Tu es déjà inscrit." }
            p { +"Gamertag : "; strong { +view.registration.gamertag } }
            ul(classes = "etat") {
                li { +"Inscrit." }
                if (view.registration.linked) li { +"Compte Discord relié." }
                if (view.registration.admitted) li { +"Admis : tu peux entrer dès l'ouverture." }
            }
            if (view.discordButton) p { a(href = "/pionniers/discord/", classes = "sa-bouton sa-bouton--laiton") { +"Relier Discord" } }
            if (view.discordPending) p { +"Relier ton compte Discord arrive." }
        }
    }

/** Une phrase et le chemin du retour : refus, limite de débit, échec de Discord. */
internal fun HTML.pionniersMessage(shell: SiteShell, message: PioneerMessage) =
    gabarit(shell, PageFrame(message.title, message.text)) {
        lecture(message.title) {
            p { +message.text }
            p { a(href = message.backUrl, classes = "sa-bouton sa-bouton--fonte") { +message.backLabel } }
        }
    }
