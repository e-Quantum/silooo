package com.silooo.core.site.pages

import com.silooo.core.site.ContentPage
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.unsafe

// Les textes des écrans de connexion et d'inscription, d'après les maquettes SiteInscription (écrans 1 à 3) de Kr. Tous sont à
// valider par Kr (docs/passation.md) ; ceux qui n'ont pas de maquette (la connexion simple, la porte « bientôt ») le sont d'autant plus.
private const val CHOICE_BUBBLE = "Xbox ou Discord, au choix."
private const val XBOX_PROMISE = "Ton compte du jeu : ton gamertag est réservé tout de suite."
private const val DISCORD_PROMISE = "Le bot te prévient le jour de l'ouverture."
private const val XBOX_PLAIN = "Tu te connectes avec ton compte Microsoft."
private const val DISCORD_PLAIN = "Tu te connectes avec ton compte Discord."
private const val JOIN_LABEL = "Rejoindre aussi le Discord"
private const val UNDER_13_TITLE = "Moins de 13 ans"
private const val UNDER_13_TEXT =
    "Discord ne t'est pas ouvert : passe par Xbox. Si ton compte est un compte enfant, un parent doit d'abord l'ajouter à sa famille Microsoft."

private const val NOTED_TITLE = "C'est noté !"
private const val FOR_YOU_TITLE = "Pour toi"
private const val WAIT_XBOX = "Xbox ne nous laisse pas t'écrire. Pour savoir quand ça ouvre, garde un œil sur le chantier ou sur Hubyy."
private const val WAIT_LINK_LATER = "Tu relieras ton compte du jeu à l'ouverture de la bêta."

private const val REFUSED_TITLE = "Microsoft a dit non"
private const val REFUSED_DESCRIPTION = "Ton compte Xbox est un compte enfant : Microsoft le bloque tant qu'il n'est pas dans une famille."
private const val REFUSED_TEXT = "Ton compte Xbox est un compte enfant. Microsoft le bloque tant qu'il n'est pas dans une famille."
private const val UNLOCK_TITLE = "Pour débloquer"

/** Ce que gagne un inscrit : l'icône (sans `.png`) et la phrase de la maquette. */
private class Perk(val icon: String, val text: String)

private val PERKS = listOf(
    Perk("cadenas", "Accès à la bêta fermée"),
    Perk("etoile", "Le titre Pionnier"),
    Perk("ville", "Un objet de déco Pionnier pour ton île"),
)

private val UNLOCK_STEPS = listOf(
    "Un parent se connecte à son compte Microsoft.",
    "Dans Famille, il ajoute ton compte.",
    "Tu reviens ici et tu réessaies.",
)

/** La coche de l'état « fait » : la même que celle du chantier, dessinée dans la page sans aucun style en ligne. */
private const val OK_ICON =
    """<svg class="rang__coche" viewBox="0 0 8 8" width="16" height="16" aria-hidden="true" focusable="false" shape-rendering="crispEdges"><path d="M1 4h1v1h1v1h1V5h1V4h1V3h1V2h1v1H7v1H6v1H5v1H4v1H3V6H2V5H1z" fill="#fff6de"/></svg>"""

/** Les icônes de design/icones/, copiées par Gradle dans `/public/atelier/icones/`. */
internal const val ACCOUNT_ICON_DIR = "/public/atelier/icones"

// ------------------------------------------------------------------------------------------------------------------------
// Les briques communes des pages des comptes (Connexion.kt, Compte.kt) : le cadre, le panneau, les lignes, les boutons.
// ------------------------------------------------------------------------------------------------------------------------

/**
 * Le cadre d'une page des comptes : le contenu se pose sur le ciel (`<main class="ciel comptes">`), le titre en plaque, puis des
 * panneaux de fonte, comme les maquettes SiteInscription et SiteLier. Oooscar change d'humeur selon l'écran.
 */
internal fun accountFrame(
    title: String,
    description: String,
    url: String?,
    humeur: Humeur = Humeur.JOIE,
    bulle: String? = null,
): PageFrame = PageFrame(title, description, url, humeur = humeur, bulle = bulle, panneau = false, classe = "comptes")

/** Le `h1` de la page : une plaque. */
internal fun FlowContent.accountTitle(title: String) {
    h1(classes = "sa-plaque") { +title }
}

/** Un panneau de fonte, avec sa plaque quand [title] existe. [classes] ajoute une classe de mise en page au panneau. */
internal fun FlowContent.accountPanel(label: String, title: String? = null, classes: String = "sa-panneau", block: FlowContent.() -> Unit) {
    section(classes = classes) {
        attributes["aria-label"] = label
        if (title != null) h2(classes = "sa-plaque sa-plaque--petite") { +title }
        block()
    }
}

/** Les étapes d'une liste numérotée : un rang par étape, le numéro dans un carré rouge. */
internal fun FlowContent.accountSteps(steps: List<String>) {
    steps.forEachIndexed { index, text ->
        div(classes = "rang rang--haut") {
            span(classes = "rang__numero") { +(index + 1).toString() }
            span(classes = "rang__nom") { +text }
        }
    }
}

/**
 * Une ligne d'état : la pastille (verte avec une coche quand [done], sinon grise avec un cadenas), l'étiquette, puis la valeur.
 * Un état a toujours une icône en plus de la couleur (design/README.md).
 */
internal fun FlowContent.accountLine(label: String, value: String, done: Boolean) {
    div(classes = "rang") {
        span(classes = "rang__etat rang__etat--${if (done) "faite" else "a-venir"}") {
            if (done) {
                unsafe { +OK_ICON }
            } else {
                img(src = "$ACCOUNT_ICON_DIR/cadenas.png", alt = "") {
                    width = "20"
                    height = "20"
                }
            }
        }
        span(classes = "rang__bloc") {
            span(classes = "comptes__etiquette") { +label }
            span(classes = if (done) "comptes__valeur" else "comptes__valeur comptes__valeur--vide") { +value }
        }
    }
}

/** Les boutons posés sur le ciel, sous les panneaux : l'un sous l'autre sur téléphone, côte à côte sur ordinateur. */
internal fun FlowContent.accountActions(block: DIV.() -> Unit) {
    div(classes = "comptes__actions") { block() }
}

/** Un lien en forme de bouton. [primary] : le bouton rouge, le seul de la page ; sinon un bouton de fonte. */
internal fun FlowContent.accountLink(href: String, label: String, primary: Boolean = false) {
    a(href = href, classes = buttonClass(primary)) { +label }
}

/** Un formulaire POST d'un seul bouton, avec le jeton anti-CSRF dans le champ caché `jeton`. */
internal fun FlowContent.accountPost(action: String, csrf: String, label: String, primary: Boolean = false) {
    form(action = action, method = FormMethod.post) {
        input(type = InputType.hidden, name = "jeton") { value = csrf }
        button(type = ButtonType.submit, classes = buttonClass(primary)) { +label }
    }
}

private fun buttonClass(primary: Boolean): String = if (primary) "sa-bouton" else "sa-bouton sa-bouton--fonte"

// ------------------------------------------------------------------------------------------------------------------------
// Écran 1 : le choix
// ------------------------------------------------------------------------------------------------------------------------

/**
 * `/connexion/` et `/pionniers/` (maquette SiteInscription, écran 1) : le texte du contenu, les deux portes dans deux panneaux
 * (côte à côte sur ordinateur, l'un sous l'autre sur téléphone), puis la note « Moins de 13 ans ». Xbox est le bouton rouge,
 * Discord un bouton de fonte : c'est un formulaire GET, pour que la case « Rejoindre aussi le Discord » parte avec la demande.
 * Une porte dont l'application manque n'est pas un lien mort : le panneau dit qu'elle arrive bientôt.
 */
internal fun HTML.connexion(shell: SiteShell, page: ContentPage, view: ConnexionView) =
    gabarit(shell, accountFrame(page.title, page.description, page.url, bulle = CHOICE_BUBBLE)) {
        accountTitle(page.title)
        div(classes = "comptes__texte") { rendered(page.html) }
        div(classes = "portes") {
            xboxDoor(view)
            discordDoor(view)
        }
        accountPanel(UNDER_13_TITLE) {
            p(classes = "comptes__etiquette comptes__etiquette--laiton") { +UNDER_13_TITLE }
            p(classes = "comptes__petit") { +UNDER_13_TEXT }
        }
    }

private fun FlowContent.xboxDoor(view: ConnexionView) {
    accountPanel("Xbox", classes = "sa-panneau porte") {
        if (view.xbox) {
            a(href = AccountUrls.XBOX, classes = "sa-bouton") { +"Avec Xbox" }
            p(classes = "comptes__petit") { +(if (view.inscription) XBOX_PROMISE else XBOX_PLAIN) }
        } else {
            p(classes = "comptes__texte") { +"La connexion avec Xbox arrive bientôt." }
        }
    }
}

private fun FlowContent.discordDoor(view: ConnexionView) {
    accountPanel("Discord", classes = "sa-panneau porte") {
        if (view.discord) {
            form(action = AccountUrls.DISCORD, method = FormMethod.get, classes = "formulaire") {
                button(type = ButtonType.submit, classes = "sa-bouton sa-bouton--fonte") { +"Avec Discord" }
                div(classes = "formulaire__case") {
                    input(type = InputType.checkBox, name = "rejoindre", classes = "formulaire__coche") {
                        id = "rejoindre"
                        value = "oui"
                    }
                    label {
                        attributes["for"] = "rejoindre"
                        +JOIN_LABEL
                    }
                }
            }
            p(classes = "comptes__petit") { +(if (view.inscription) DISCORD_PROMISE else DISCORD_PLAIN) }
        } else {
            p(classes = "comptes__texte") { +"La connexion avec Discord arrive bientôt." }
        }
    }
}

// ------------------------------------------------------------------------------------------------------------------------
// Écran 2 : « C'est noté ! »
// ------------------------------------------------------------------------------------------------------------------------

/**
 * `/pionniers/etat/` (maquette SiteInscription, écran 2). Inscrit par Xbox : le gamertag est réservé, et Xbox ne laisse pas écrire
 * à l'inscrit. Inscrit par Discord : le bot prévient ; le lien « Relier mon compte du jeu » n'existe que si [InscritView.lier]
 * (en attente, le jeu n'est pas là : la page dit seulement que le compte du jeu se reliera à la bêta). Oooscar est fier, sous la page.
 */
internal fun HTML.inscrit(shell: SiteShell, page: ContentPage, view: InscritView) =
    gabarit(shell, accountFrame(NOTED_TITLE, page.description, page.url, humeur = Humeur.FIER, bulle = Bulles.REJOINDRE)) {
        accountTitle(NOTED_TITLE)
        val gamertag = view.gamertag
        p(classes = "comptes__texte") {
            if (gamertag != null) {
                +"Ton gamertag "
                strong { +gamertag }
                +" est réservé pour la bêta."
            } else {
                +DISCORD_PROMISE
            }
        }
        accountPanel("Ce qui t'attend", FOR_YOU_TITLE) {
            for (perk in PERKS) {
                div(classes = "rang") {
                    img(src = "$ACCOUNT_ICON_DIR/${perk.icon}.png", alt = "") {
                        width = "32"
                        height = "32"
                    }
                    span(classes = "rang__nom") { +perk.text }
                }
            }
        }
        if (gamertag != null) {
            p(classes = "comptes__petit") { +WAIT_XBOX }
        } else if (!view.lier) {
            p(classes = "comptes__petit") { +WAIT_LINK_LATER }
        }
        // Une action principale par page : relier le compte du jeu quand c'est possible, sinon retourner au chantier.
        val lier = gamertag == null && view.lier
        accountActions {
            if (lier) accountLink(AccountUrls.LIER, "Relier mon compte du jeu", primary = true)
            accountLink("/", "Voir le chantier", primary = !lier)
        }
    }

// ------------------------------------------------------------------------------------------------------------------------
// Écran 3 : compte enfant refusé
// ------------------------------------------------------------------------------------------------------------------------

/** « Microsoft a dit non » (maquette SiteInscription, écran 3) : le compte enfant, les trois étapes pour le débloquer, « Réessayer » (le bouton rouge). */
internal fun HTML.microsoftRefused(shell: SiteShell) =
    gabarit(shell, accountFrame(REFUSED_TITLE, REFUSED_DESCRIPTION, null, humeur = Humeur.OUPS, bulle = Bulles.ERREUR)) {
        accountTitle(REFUSED_TITLE)
        p(classes = "comptes__texte") { +REFUSED_TEXT }
        accountPanel(UNLOCK_TITLE, UNLOCK_TITLE) { accountSteps(UNLOCK_STEPS) }
        accountActions { accountLink(AccountUrls.XBOX, "Réessayer", primary = true) }
    }

// ------------------------------------------------------------------------------------------------------------------------
// Les pages d'une phrase
// ------------------------------------------------------------------------------------------------------------------------

/** Une phrase et le chemin du retour : refus, limite de débit, échec de Xbox ou de Discord. Le retour est un bouton de fonte : la page n'a pas de bouton rouge. */
internal fun HTML.accountMessage(shell: SiteShell, message: AccountMessage) =
    gabarit(shell, accountFrame(message.title, message.text, null, humeur = Humeur.OUPS, bulle = Bulles.ERREUR)) {
        accountTitle(message.title)
        accountPanel(message.title) {
            p(classes = "comptes__texte") { +message.text }
        }
        accountActions { accountLink(message.backUrl, message.backLabel) }
    }
