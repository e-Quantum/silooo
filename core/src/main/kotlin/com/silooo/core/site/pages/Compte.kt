package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.p
import kotlinx.html.span

// Les textes de « Mon compte » et de « Lier mon compte », d'après la maquette SiteLier (écrans 1 et 2) de Kr. Tous sont à valider par
// Kr (docs/passation.md) ; « Mon compte » n'a pas de maquette pour cette branche (le tableau de l'entreprise vient avec claude/site-compte).
private const val ACCOUNT_LABEL = "Ton compte"
private const val GAME_LABEL = "Compte du jeu"
private const val GAME_MISSING = "Pas encore relié"
private const val DISCORD_LABEL = "Discord"
private const val DISCORD_LINKED = "Relié"
private const val DISCORD_MISSING = "Pas relié"
private const val SIGN_UP_LABEL = "Inscription"
private const val SIGN_UP_VALUE = "Pionnier"
private const val LINK_GAME_LABEL = "Relier mon compte du jeu"
private const val LINK_DISCORD_LABEL = "Relier Discord"
private const val LOGOUT_LABEL = "Se déconnecter"

private const val LIER_TITLE = "Lier mon compte"
private const val LIER_DESCRIPTION = "Relie ton compte du jeu à ton compte du site avec un code de six caractères, valable dix minutes."
private const val LIER_LINKED_TITLE = "Relié !"
private const val LIER_INTRO = "Tu es connecté avec Discord. Relie maintenant ton compte du jeu."
private const val LIER_XBOX_INTRO = "Tu es connecté avec Xbox. Ton compte du jeu est déjà relié : tu n'as rien à taper en jeu."
private const val LIER_ONE_EACH = "Un gamertag, un compte Discord. Pour en changer, délie d'abord celui-ci."
private const val LIER_DISCORD_SOON = "Relier Discord arrive bientôt."
private const val CODE_TITLE = "Ton code"
private const val CODE_EXPIRED = "Ce code a expiré. Demande un nouveau code."
private const val IN_GAME_TITLE = "Dans le jeu"
private const val WAITING_LABEL = "En attente du jeu"

private val IN_GAME_STEPS = listOf(
    "Ouvre le Menu (case 9)",
    "Mon compte, puis Relier Discord",
    "Tape le code",
)

/**
 * `/compte/` : le compte du jeu (le gamertag, ou « pas encore relié » avec un lien vers [AccountUrls.LIER]), le Discord (relié, ou
 * « Relier Discord » quand l'application existe), la ligne Pionnier pour un inscrit d'avant l'ouverture, et la déconnexion.
 * « Relier Discord » est le bouton rouge de la page ; la déconnexion est un bouton de fonte, jamais rouge. Aucun identifiant
 * Discord ni XUID : la vue n'en porte pas.
 */
internal fun HTML.compte(shell: SiteShell, page: ContentPage, view: CompteView) =
    gabarit(shell, accountFrame(page.title, page.description, page.url)) {
        accountTitle(page.title)
        div(classes = "comptes__texte") { rendered(page.html) }
        accountPanel(ACCOUNT_LABEL, ACCOUNT_LABEL) {
            accountLine(GAME_LABEL, view.gamertag ?: GAME_MISSING, done = view.gamertag != null)
            accountLine(DISCORD_LABEL, if (view.discord) DISCORD_LINKED else DISCORD_MISSING, done = view.discord)
            if (view.pionnier) accountLine(SIGN_UP_LABEL, SIGN_UP_VALUE, done = true)
        }
        accountActions {
            if (view.gamertag == null) accountLink(AccountUrls.LIER, LINK_GAME_LABEL)
            if (view.discordButton) accountLink(AccountUrls.DISCORD, LINK_DISCORD_LABEL, primary = true)
            accountPost(AccountUrls.LOGOUT, view.csrf, LOGOUT_LABEL)
        }
    }

/**
 * `/compte/lier/`, selon [LierView.kind]. [LierKind.CODE] : le code en six cases, le temps qu'il lui reste, les trois étapes en
 * jeu, « En attente du jeu », « Actualiser » et « Nouveau code ». [LierKind.LINKED] : « Relié ! », le gamertag, « Voir mon compte »
 * et « Délier ». [LierKind.XBOX] : le jeu est déjà connu par Xbox, il reste le Discord.
 *
 * La page devrait se rafraîchir seule (maquette, écran 2), mais le gabarit n'offre pas de balise `<meta>` de plus dans le `head` :
 * « Actualiser » est un formulaire GET, qui fait la même chose à la main.
 */
internal fun HTML.lier(shell: SiteShell, page: ContentPage, view: LierView) {
    val linked = view.kind == LierKind.LINKED
    val frame = accountFrame(
        LIER_TITLE,
        LIER_DESCRIPTION,
        page.url,
        humeur = if (linked) Humeur.FIER else Humeur.JOIE,
        bulle = if (linked) Bulles.REJOINDRE else null,
    )
    gabarit(shell, frame) {
        accountTitle(if (linked) LIER_LINKED_TITLE else LIER_TITLE)
        when (view.kind) {
            LierKind.CODE -> lierCode(view)
            LierKind.LINKED -> lierLinked(view)
            LierKind.XBOX -> lierXbox(view)
        }
    }
}

private fun FlowContent.lierCode(view: LierView) {
    p(classes = "comptes__texte") { +LIER_INTRO }
    accountPanel(CODE_TITLE, classes = "sa-panneau lier__code") {
        p(classes = "comptes__etiquette") { +CODE_TITLE }
        val code = view.code.orEmpty()
        // Une case par caractère : un lecteur d'écran lit le code lettre par lettre, pas comme un mot.
        div(classes = "sa-compteur") {
            attributes["role"] = "img"
            attributes["aria-label"] = "Code " + code.toList().joinToString(" ")
            for (character in code) span(classes = "sa-compteur__chiffre") { +character.toString() }
        }
        p(classes = "comptes__petit") { +remainingText(view.secondsLeft) }
    }
    accountPanel(IN_GAME_TITLE, IN_GAME_TITLE) { accountSteps(IN_GAME_STEPS) }
    div(classes = "sa-progression lier__attente") {
        div(classes = "sa-progression__plein lier__plein") { attributes["aria-hidden"] = "true" }
        span(classes = "sa-progression__texte") { +WAITING_LABEL }
    }
    accountActions {
        form(action = AccountUrls.LIER, method = FormMethod.get) {
            button(type = ButtonType.submit, classes = "sa-bouton sa-bouton--fonte") { +"Actualiser" }
        }
        accountPost(AccountUrls.LIER, view.csrf, "Nouveau code")
    }
}

private fun FlowContent.lierLinked(view: LierView) {
    accountPanel("Compte relié") {
        accountLine(GAME_LABEL, view.gamertag.orEmpty(), done = true)
        p(classes = "comptes__petit") { +LIER_ONE_EACH }
    }
    accountActions {
        accountLink(AccountUrls.COMPTE, "Voir mon compte", primary = true)
        accountPost(AccountUrls.DELIER, view.csrf, "Délier")
    }
}

private fun FlowContent.lierXbox(view: LierView) {
    p(classes = "comptes__texte") { +LIER_XBOX_INTRO }
    accountPanel("Compte relié") {
        accountLine(GAME_LABEL, view.gamertag.orEmpty(), done = true)
    }
    if (!view.discordButton) p(classes = "comptes__texte") { +LIER_DISCORD_SOON }
    accountActions {
        if (view.discordButton) accountLink(AccountUrls.DISCORD, LINK_DISCORD_LABEL, primary = true)
        accountLink(AccountUrls.COMPTE, "Voir mon compte")
    }
}

/** « Valable encore 9 min 41 s. » (« 10 min. » à la seconde ronde), ou la phrase de l'expiration quand il ne reste rien (ou que la vue ne dit pas). */
internal fun remainingText(secondsLeft: Long?): String {
    if (secondsLeft == null || secondsLeft <= 0) return CODE_EXPIRED
    val minutes = secondsLeft / 60
    val seconds = secondsLeft % 60
    return when {
        minutes > 0 && seconds == 0L -> "Valable encore $minutes min."
        minutes > 0 -> "Valable encore $minutes min $seconds s."
        else -> "Valable encore $seconds s."
    }
}
