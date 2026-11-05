package com.silooo.core.site

import com.silooo.core.TestDefinitions
import com.silooo.core.site.pages.AccountMessage
import com.silooo.core.site.pages.AccountUrls
import com.silooo.core.site.pages.CompteView
import com.silooo.core.site.pages.ConnexionView
import com.silooo.core.site.pages.InscritView
import com.silooo.core.site.pages.LierKind
import com.silooo.core.site.pages.LierView
import com.silooo.core.site.pages.NavItem
import com.silooo.core.site.pages.NavKind
import com.silooo.core.site.pages.SiteShell
import com.silooo.core.site.pages.accountMessage
import com.silooo.core.site.pages.compte
import com.silooo.core.site.pages.connexion
import com.silooo.core.site.pages.inscrit
import com.silooo.core.site.pages.lier
import com.silooo.core.site.pages.microsoftRefused
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les pages des comptes (`site/pages/Connexion.kt` et `Compte.kt`), rendues sans serveur : le gabarit, un `SiteShell` de test et la
 * vue de chaque cas. Chaque page est lue comme le visiteur la reçoit : les textes des maquettes SiteInscription et SiteLier, un seul
 * bouton rouge au plus, un `alt` sur chaque image, aucun emoji. Les routes qui envoient ces vues ont leurs propres tests.
 */
class SiteComptesPagesTest {
    private val shell = SiteShell(
        siteUrl = "https://silooo.com",
        discordUrl = null,
        nav = listOf(NavItem("/actus/", "Actus"), NavItem("/pionniers/", "Me prévenir", NavKind.BOUTON)),
        settings = SiteSettings(),
        shop = TestDefinitions.loaded.shop,
        definitions = TestDefinitions.loaded,
        mode = SiteMode.BETA,
    )

    private fun page(name: String, title: String, url: String, body: String) =
        ContentPage(name, title, "Description de $title.", url, SiteMode.entries.toSet(), "<p>$body</p>")

    private val signUpPage = page("pionniers", "Me prévenir", "/pionniers/", "Inscris-toi pour la bêta. On ne garde que ton gamertag ou ton identifiant Discord.")
    private val loginPage = page("connexion", "Se connecter", "/connexion/", "Connecte-toi avec Xbox ou avec Discord. Silooo ne te demande aucun mot de passe.")
    private val accountPage = page("compte", "Mon compte", "/compte/", "Ici, tu vois le compte du jeu et le Discord reliés à ton compte du site.")

    private fun render(block: HTML.() -> Unit): String = createHTML(prettyPrint = false).html { block() }

    /** Le HTML lu comme un visiteur : les entités de kotlinx.html (`&#39;` pour l'apostrophe) deviennent les caractères. */
    private fun text(html: String): String =
        html.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun main(html: String): String = html.substringAfter("<main").substringBefore("</main>")

    private fun attribute(tag: String, name: String): String? = Regex("""\b$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    /** Les boutons rouges de la page, ceux de SiteStyleTest : `class="sa-bouton"` sans variante. Les boutons de la navigation sont de fonte. */
    private fun red(html: String): Int = Regex("""class="sa-bouton(?! sa-bouton--)""").findAll(html).count()

    /** La balise ouvrante `<tag ...>` dont l'attribut [name] vaut [value], ou un échec lisible. */
    private fun tag(html: String, tag: String, name: String, value: String): String =
        Regex("""<$tag\b[^>]*\b$name="${Regex.escape(value)}"[^>]*>""").find(html)?.value ?: error("pas de <$tag $name=\"$value\"> dans : ${main(html)}")

    /** Un formulaire entier, repéré par son action. */
    private fun form(html: String, action: String): String =
        Regex("""<form\b[^>]*\baction="${Regex.escape(action)}"[^>]*>.*?</form>""").find(html)?.value ?: error("pas de formulaire vers $action dans : ${main(html)}")

    /** Ce que toute page des comptes doit être, quel que soit son cas. */
    private fun assertWellFormed(html: String, where: String) {
        assertTrue(Regex("""<html[^>]*\blang="fr"""").containsMatchIn(html), "$where : lang=\"fr\"")
        assertEquals(1, Regex("<h1[ >]").findAll(html).count(), "$where : un seul h1")
        assertTrue(Regex("""<main class="ciel comptes"[^>]*>""").containsMatchIn(html), "$where : main.ciel.comptes")
        assertTrue(red(html) <= 1, "$where : un seul bouton rouge au plus")
        assertFalse(main(html).contains("style="), "$where : aucun style en ligne")
        assertFalse(html.contains("{{"), "$where : un marqueur de contenu est resté dans la page")
        assertOnlySiteScripts(html, where)
        for (image in Regex("<img[^>]*>").findAll(html)) assertTrue(attribute(image.value, "alt") != null, "$where : image sans alt : ${image.value}")
        // Les emoji hors du plan de base sont des paires de substituts ; ceux du plan de base sont dans les blocs 2600 à 27BF.
        assertTrue(html.none { it.isSurrogate() || it.code in 0x2600..0x27BF }, "$where : un emoji")
    }

    // ------------------------------------------------------------------------------------------------ les contenus

    @Test
    @DisplayName("Contenus : pionniers, connexion et compte portent leur titre, leur adresse et leurs modes")
    fun contents() {
        val pages = SiteContent.fromClasspath().pages.associateBy { it.name }
        val signUp = pages.getValue("pionniers")
        assertEquals("Me prévenir", signUp.title)
        assertEquals("/pionniers/", signUp.url)
        assertEquals(setOf(SiteMode.ATTENTE, SiteMode.BETA), signUp.modes)
        assertTrue(signUp.html.contains("Inscris-toi pour la bêta. On ne garde que ton gamertag ou ton identifiant Discord."), signUp.html)
        val login = pages.getValue("connexion")
        assertEquals("Se connecter", login.title)
        assertEquals("/connexion/", login.url)
        assertEquals(setOf(SiteMode.ATTENTE, SiteMode.BETA, SiteMode.OUVERT), login.modes)
        val account = pages.getValue("compte")
        assertEquals("Mon compte", account.title)
        assertEquals("/compte/", account.url)
        assertEquals(setOf(SiteMode.BETA, SiteMode.OUVERT), account.modes)
    }

    // ------------------------------------------------------------------------------------------------ écran 1 : le choix

    @Test
    @DisplayName("Connexion (inscription) : Xbox est le bouton rouge, Discord un formulaire GET avec la case, la promesse de chaque porte et la note des moins de 13 ans")
    fun signUpChoice() {
        val html = render { connexion(shell, signUpPage, ConnexionView(xbox = true, discord = true, inscription = true)) }
        assertWellFormed(html, "/pionniers/")
        val visible = text(main(html))
        assertTrue(visible.contains("Me prévenir"))
        assertTrue(visible.contains("Inscris-toi pour la bêta. On ne garde que ton gamertag ou ton identifiant Discord."))

        assertTrue(main(html).contains("""<a href="/connexion/xbox/" class="sa-bouton">Avec Xbox</a>"""), main(html))
        assertEquals(1, red(html), "Xbox est l'action principale : le seul bouton rouge")
        assertTrue(visible.contains("Ton compte du jeu : ton gamertag est réservé tout de suite."))

        val discord = form(html, AccountUrls.DISCORD)
        assertEquals("get", attribute(discord, "method"), "le formulaire Discord est un GET : $discord")
        assertTrue(discord.contains("""class="sa-bouton sa-bouton--fonte">Avec Discord</button>"""), discord)
        val box = Regex("""<input[^>]*name="rejoindre"[^>]*>""").find(discord)?.value ?: error("la case rejoindre manque : $discord")
        assertEquals("checkbox", attribute(box, "type"))
        assertEquals("oui", attribute(box, "value"))
        assertFalse(box.contains("checked"), "la case n'est pas cochée d'office")
        assertTrue(Regex("""<label[^>]*\bfor="${attribute(box, "id")}"[^>]*>Rejoindre aussi le Discord</label>""").containsMatchIn(discord), "la case a son libellé : $discord")
        assertTrue(visible.contains("Le bot te prévient le jour de l'ouverture."))

        assertTrue(Regex("""<section class="sa-panneau" aria-label="Moins de 13 ans">""").containsMatchIn(html))
        assertTrue(visible.contains("Moins de 13 ans"))
        assertTrue(visible.contains("Discord ne t'est pas ouvert : passe par Xbox. Si ton compte est un compte enfant, un parent doit d'abord l'ajouter à sa famille Microsoft."))
        assertEquals(2, Regex("""class="sa-panneau porte"""").findAll(html).count(), "deux portes, deux panneaux")
        // Une porte n'est un lien que vers sa route : aucun identifiant ni jeton dans la page.
        assertFalse(html.contains("jeton"))
    }

    @Test
    @DisplayName("Connexion simple : le titre du contenu, une formulation de connexion à la place des promesses, les mêmes deux portes")
    fun plainLogin() {
        val html = render { connexion(shell, loginPage, ConnexionView(xbox = true, discord = true, inscription = false)) }
        assertWellFormed(html, "/connexion/")
        val visible = text(main(html))
        assertTrue(visible.contains("Se connecter"))
        assertTrue(visible.contains("Connecte-toi avec Xbox ou avec Discord. Silooo ne te demande aucun mot de passe."))
        assertTrue(visible.contains("Tu te connectes avec ton compte Microsoft."))
        assertTrue(visible.contains("Tu te connectes avec ton compte Discord."))
        assertFalse(visible.contains("réservé tout de suite"), "pas de promesse d'inscription sur la connexion")
        assertFalse(visible.contains("te prévient le jour de l'ouverture"))
        assertEquals(1, red(html))
        assertTrue(html.contains(AccountUrls.XBOX) && html.contains(AccountUrls.DISCORD))
    }

    @Test
    @DisplayName("Connexion : une porte sans application est un panneau qui dit qu'elle arrive bientôt, sans lien ni bouton")
    fun missingDoors() {
        val noXbox = render { connexion(shell, signUpPage, ConnexionView(xbox = false, discord = true, inscription = true)) }
        assertWellFormed(noXbox, "/pionniers/ sans Xbox")
        assertFalse(noXbox.contains(AccountUrls.XBOX), "aucun lien vers une porte absente")
        assertTrue(text(main(noXbox)).contains("La connexion avec Xbox arrive bientôt."))
        assertFalse(noXbox.contains("Avec Xbox"))
        assertEquals(0, red(noXbox), "sans Xbox, la page n'a pas de bouton rouge")
        assertTrue(noXbox.contains("Avec Discord"))

        val noDiscord = render { connexion(shell, signUpPage, ConnexionView(xbox = true, discord = false, inscription = true)) }
        assertWellFormed(noDiscord, "/pionniers/ sans Discord")
        assertFalse(noDiscord.contains(AccountUrls.DISCORD))
        assertFalse(noDiscord.contains("rejoindre"), "pas de case sans porte Discord")
        assertTrue(text(main(noDiscord)).contains("La connexion avec Discord arrive bientôt."))
        assertTrue(noDiscord.contains("Avec Xbox"))
        assertEquals(1, red(noDiscord))

        val none = render { connexion(shell, signUpPage, ConnexionView(xbox = false, discord = false, inscription = true)) }
        assertWellFormed(none, "/pionniers/ sans porte")
        assertFalse(Regex("""<form[^>]*action="/connexion""").containsMatchIn(none))
        assertFalse(Regex("""<a href="/connexion/""").containsMatchIn(main(none)))
    }

    // ------------------------------------------------------------------------------------------------ écran 2 : C'est noté !

    @Test
    @DisplayName("C'est noté (Xbox) : le gamertag réservé, la liste Pour toi, la phrase sur Xbox et Voir le chantier, la seule action, en rouge")
    fun notedByXbox() {
        val html = render { inscrit(shell, signUpPage, InscritView(gamertag = "OscarLeBûcheron", lier = false)) }
        assertWellFormed(html, "/pionniers/etat/ (Xbox)")
        val visible = text(main(html))
        assertTrue(visible.contains("C'est noté !"))
        assertTrue(main(html).contains("Ton gamertag <strong>OscarLeBûcheron</strong> est réservé pour la bêta."), main(html))
        assertTrue(Regex("""<section class="sa-panneau" aria-label="Ce qui t(?:'|&#39;)attend">""").containsMatchIn(html), "le panneau Pour toi")
        assertTrue(visible.contains("Pour toi"))
        for (perk in listOf("Accès à la bêta fermée", "Le titre Pionnier", "Un objet de déco Pionnier pour ton île")) assertTrue(visible.contains(perk), perk)
        for (icon in listOf("cadenas", "etoile", "ville")) {
            val image = tag(html, "img", "src", "/public/atelier/icones/$icon.png")
            assertEquals("", attribute(image, "alt"), "l'icône est décorative : $image")
            assertEquals("32", attribute(image, "width"))
        }
        assertTrue(visible.contains("Xbox ne nous laisse pas t'écrire. Pour savoir quand ça ouvre, garde un œil sur le chantier ou sur Hubyy."))
        assertTrue(main(html).contains("""<a href="/" class="sa-bouton">Voir le chantier</a>"""), main(html))
        assertFalse(html.contains(AccountUrls.LIER), "le compte du jeu est connu : rien à relier")
        assertFalse(visible.contains("Le bot te prévient"))
        assertEquals(1, red(html), "la seule action de la page est l'action principale")
        assertTrue(html.contains("oooscar-fier.png"), "Oooscar est fier sous la page")
    }

    @Test
    @DisplayName("C'est noté (Discord) : le bot prévient, Relier mon compte du jeu est le bouton rouge quand le jeu existe")
    fun notedByDiscord() {
        val html = render { inscrit(shell, signUpPage, InscritView(gamertag = null, lier = true)) }
        assertWellFormed(html, "/pionniers/etat/ (Discord, lier)")
        val visible = text(main(html))
        assertTrue(visible.contains("C'est noté !"))
        assertTrue(visible.contains("Le bot te prévient le jour de l'ouverture."))
        assertTrue(visible.contains("Pour toi"))
        assertFalse(visible.contains("est réservé pour la bêta"), "pas de gamertag par Discord")
        assertFalse(visible.contains("Xbox ne nous laisse pas"))
        assertTrue(main(html).contains("""<a href="/compte/lier/" class="sa-bouton">Relier mon compte du jeu</a>"""), main(html))
        assertEquals(1, red(html))
        assertTrue(main(html).contains("""<a href="/" class="sa-bouton sa-bouton--fonte">Voir le chantier</a>"""))
    }

    @Test
    @DisplayName("C'est noté (Discord, avant le jeu) : pas de lien vers la liaison, une phrase qui dit que le compte du jeu se reliera à la bêta")
    fun notedByDiscordBeforeTheGame() {
        val html = render { inscrit(shell, signUpPage, InscritView(gamertag = null, lier = false)) }
        assertWellFormed(html, "/pionniers/etat/ (Discord, attente)")
        val visible = text(main(html))
        assertTrue(visible.contains("Le bot te prévient le jour de l'ouverture."))
        assertFalse(html.contains(AccountUrls.LIER), "le jeu n'existe pas encore : aucun lien vers la liaison")
        assertFalse(visible.contains("Relier mon compte du jeu"))
        assertTrue(visible.contains("Tu relieras ton compte du jeu à l'ouverture de la bêta."))
        assertTrue(main(html).contains("""<a href="/" class="sa-bouton">Voir le chantier</a>"""), "sans liaison possible, le chantier est l'action principale")
        assertEquals(1, red(html))
    }

    // ------------------------------------------------------------------------------------------------ écran 3 : Microsoft a dit non

    @Test
    @DisplayName("Microsoft a dit non : le compte enfant, les trois étapes numérotées, Réessayer vers Xbox est le seul bouton rouge")
    fun microsoftSaidNo() {
        val html = render { microsoftRefused(shell) }
        assertWellFormed(html, "Microsoft a dit non")
        val visible = text(main(html))
        assertTrue(visible.contains("Microsoft a dit non"))
        assertTrue(visible.contains("Ton compte Xbox est un compte enfant. Microsoft le bloque tant qu'il n'est pas dans une famille."))
        assertTrue(visible.contains("Pour débloquer"))
        val steps = Regex("""<span class="rang__numero">(\d)</span><span class="rang__nom">([^<]*)</span>""").findAll(main(html)).map { it.groupValues[1] to text(it.groupValues[2]) }.toList()
        assertEquals(
            listOf(
                "1" to "Un parent se connecte à son compte Microsoft.",
                "2" to "Dans Famille, il ajoute ton compte.",
                "3" to "Tu reviens ici et tu réessaies.",
            ),
            steps,
        )
        assertTrue(main(html).contains("""<a href="/connexion/xbox/" class="sa-bouton">Réessayer</a>"""), main(html))
        assertEquals(1, red(html))
        assertTrue(html.contains("oooscar-oups.png"), "Oooscar est surpris sous la page")
    }

    // ------------------------------------------------------------------------------------------------ les pages d'une phrase

    @Test
    @DisplayName("Messages : chaque cas dit sa phrase, avec un retour en bouton de fonte et aucun bouton rouge")
    fun messages() {
        for (message in AccountMessage.entries) {
            val html = render { accountMessage(shell, message) }
            assertWellFormed(html, message.name)
            val visible = text(main(html))
            assertTrue(visible.contains(message.title), "${message.name} : le titre")
            assertTrue(visible.contains(message.text), "${message.name} : la phrase")
            assertTrue(main(html).contains("""<a href="${message.backUrl}" class="sa-bouton sa-bouton--fonte">"""), "${message.name} : le retour mène à ${message.backUrl}")
            assertTrue(visible.contains(message.backLabel), "${message.name} : le libellé du retour")
            assertEquals(0, red(html), "${message.name} : le retour n'est pas rouge")
            assertTrue(Regex("<title>[^<]+</title>").containsMatchIn(html))
        }
    }

    // ------------------------------------------------------------------------------------------------ mon compte

    @Test
    @DisplayName("Mon compte : le gamertag, le Discord relié, la ligne Pionnier, et la déconnexion en POST avec le jeton, en bouton de fonte")
    fun accountComplete() {
        val html = render {
            compte(shell, accountPage, CompteView(gamertag = "OscarLeBûcheron", xbox = true, discord = true, discordButton = false, pionnier = true, csrf = "jeton-du-test"))
        }
        assertWellFormed(html, "/compte/")
        val visible = text(main(html))
        assertTrue(visible.contains("Mon compte"))
        assertTrue(visible.contains("Compte du jeu"))
        assertTrue(visible.contains("OscarLeBûcheron"))
        assertTrue(Regex("""<span class="comptes__etiquette">Discord</span><span class="comptes__valeur">Relié</span>""").containsMatchIn(main(html)), main(html))
        assertTrue(Regex("""<span class="comptes__etiquette">Inscription</span><span class="comptes__valeur">Pionnier</span>""").containsMatchIn(main(html)), main(html))
        assertFalse(html.contains(AccountUrls.DISCORD), "Discord est relié : pas de bouton Relier Discord")
        assertFalse(html.contains(AccountUrls.LIER), "le compte du jeu est relié : pas de lien vers la liaison")

        val logout = form(html, AccountUrls.LOGOUT)
        assertEquals("post", attribute(logout, "method"), logout)
        val token = Regex("""<input[^>]*name="jeton"[^>]*>""").find(logout)?.value ?: error("le jeton manque : $logout")
        assertEquals("hidden", attribute(token, "type"))
        assertEquals("jeton-du-test", attribute(token, "value"))
        assertTrue(logout.contains("""class="sa-bouton sa-bouton--fonte">Se déconnecter</button>"""), logout)
        assertEquals(0, red(html), "la déconnexion n'est pas rouge, et rien d'autre ne l'est ici")
    }

    @Test
    @DisplayName("Mon compte : sans gamertag, « Pas encore relié » et le lien vers la liaison ; avec le bouton Discord, Relier Discord est le seul bouton rouge")
    fun accountNotLinked() {
        val html = render {
            compte(shell, accountPage, CompteView(gamertag = null, xbox = false, discord = false, discordButton = true, pionnier = false, csrf = "jeton"))
        }
        assertWellFormed(html, "/compte/ (rien de relié)")
        val visible = text(main(html))
        assertTrue(visible.contains("Pas encore relié"))
        assertTrue(main(html).contains("""<a href="/compte/lier/" class="sa-bouton sa-bouton--fonte">Relier mon compte du jeu</a>"""), main(html))
        assertTrue(main(html).contains("""<a href="/connexion/discord/" class="sa-bouton">Relier Discord</a>"""), main(html))
        assertEquals(1, red(html))
        assertTrue(Regex("""<span class="comptes__valeur comptes__valeur--vide">Pas relié</span>""").containsMatchIn(main(html)), "le Discord n'est pas relié")
        assertFalse(visible.contains("Pionnier"), "la ligne Pionnier n'existe que pour un inscrit d'avant l'ouverture")
        assertTrue(html.contains(AccountUrls.LOGOUT))
        // Un état a toujours une icône en plus de la couleur : le cadenas des lignes non reliées.
        assertEquals(2, Regex("""src="/public/atelier/icones/cadenas.png"""").findAll(main(html)).count())
    }

    @Test
    @DisplayName("Mon compte : le Discord non relié sans application n'a pas de bouton")
    fun accountWithoutDiscordApplication() {
        val html = render {
            compte(shell, accountPage, CompteView(gamertag = "OscarLeBûcheron", xbox = true, discord = false, discordButton = false, pionnier = false, csrf = "jeton"))
        }
        assertWellFormed(html, "/compte/ (sans application Discord)")
        assertFalse(html.contains(AccountUrls.DISCORD))
        assertEquals(0, red(html))
    }

    // ------------------------------------------------------------------------------------------------ lier mon compte

    private fun codeView(code: String? = "K7M4PX", secondsLeft: Long? = 581) =
        LierView(kind = LierKind.CODE, code = code, secondsLeft = secondsLeft, gamertag = null, discordButton = false, csrf = "jeton-de-lier")

    @Test
    @DisplayName("Lier (code) : le code en six cases, lettre par lettre, le temps qu'il reste, les trois étapes en jeu, En attente du jeu, Actualiser et Nouveau code")
    fun linkCode() {
        val html = render { lier(shell, accountPage, codeView()) }
        assertWellFormed(html, "/compte/lier/ (code)")
        val visible = text(main(html))
        assertTrue(visible.contains("Lier mon compte"))
        assertTrue(visible.contains("Tu es connecté avec Discord. Relie maintenant ton compte du jeu."))
        assertTrue(visible.contains("Ton code"))

        val letters = Regex("""<span class="sa-compteur__chiffre">([^<]*)</span>""").findAll(main(html)).map { it.groupValues[1] }.toList()
        assertEquals(listOf("K", "7", "M", "4", "P", "X"), letters, "une case par caractère du code")
        assertTrue(main(html).contains("""aria-label="Code K 7 M 4 P X""""), "un lecteur d'écran épelle le code")
        assertTrue(main(html).contains("""role="img""""))
        assertTrue(visible.contains("Valable encore 9 min 41 s."))

        assertTrue(visible.contains("Dans le jeu"))
        val steps = Regex("""<span class="rang__numero">(\d)</span><span class="rang__nom">([^<]*)</span>""").findAll(main(html)).map { it.groupValues[1] to text(it.groupValues[2]) }.toList()
        assertEquals(listOf("1" to "Ouvre le Menu (case 9)", "2" to "Mon compte, puis Relier Discord", "3" to "Tape le code"), steps)
        assertTrue(Regex("""<span class="sa-progression__texte">En attente du jeu</span>""").containsMatchIn(main(html)))

        // Le gabarit n'a pas de point d'entrée pour une balise meta de plus : l'actualisation est un formulaire GET.
        val refresh = Regex("""<form\b[^>]*action="/compte/lier/"[^>]*method="get"[^>]*>.*?</form>""").find(html)?.value
            ?: error("le formulaire Actualiser (GET) manque : ${main(html)}")
        assertTrue(refresh.contains(">Actualiser</button>"), refresh)
        val newCode = Regex("""<form\b[^>]*action="/compte/lier/"[^>]*method="post"[^>]*>.*?</form>""").find(html)?.value ?: error("le formulaire Nouveau code (POST) manque : ${main(html)}")
        assertEquals("jeton-de-lier", attribute(Regex("""<input[^>]*name="jeton"[^>]*>""").find(newCode)?.value ?: error(newCode), "value"))
        assertTrue(newCode.contains(">Nouveau code</button>"), newCode)
        assertEquals(0, red(html), "la maquette n'a pas de bouton rouge sur l'écran du code")
        assertFalse(html.contains("Relié !"))
    }

    @Test
    @DisplayName("Lier (code) : le temps qu'il reste en minutes et secondes, en secondes seules, ou la phrase de l'expiration")
    fun linkCodeTiming() {
        fun remaining(secondsLeft: Long?): String = text(main(render { lier(shell, accountPage, codeView(secondsLeft = secondsLeft)) }))
        assertTrue(remaining(600).contains("Valable encore 10 min."))
        assertTrue(remaining(61).contains("Valable encore 1 min 1 s."))
        assertTrue(remaining(45).contains("Valable encore 45 s."))
        for (gone in listOf(0L, -3L, null)) {
            val visible = remaining(gone)
            assertTrue(visible.contains("Ce code a expiré. Demande un nouveau code."), "secondsLeft=$gone")
            assertFalse(visible.contains("Valable encore"))
        }
    }

    @Test
    @DisplayName("Lier (relié) : Relié !, le gamertag, la phrase d'un gamertag pour un compte Discord, Voir mon compte en rouge et Délier en POST de fonte")
    fun linkLinked() {
        val view = LierView(kind = LierKind.LINKED, code = null, secondsLeft = null, gamertag = "OscarLeBûcheron", discordButton = false, csrf = "jeton-delier")
        val html = render { lier(shell, accountPage, view) }
        assertWellFormed(html, "/compte/lier/ (relié)")
        val visible = text(main(html))
        assertTrue(visible.contains("Relié !"))
        assertTrue(visible.contains("Compte du jeu"))
        assertTrue(visible.contains("OscarLeBûcheron"))
        assertTrue(visible.contains("Un gamertag, un compte Discord. Pour en changer, délie d'abord celui-ci."))
        assertTrue(main(html).contains("""<a href="/compte/" class="sa-bouton">Voir mon compte</a>"""), main(html))
        assertEquals(1, red(html))
        val unlink = form(html, AccountUrls.DELIER)
        assertEquals("post", attribute(unlink, "method"), unlink)
        assertEquals("jeton-delier", attribute(Regex("""<input[^>]*name="jeton"[^>]*>""").find(unlink)?.value ?: error(unlink), "value"))
        assertTrue(unlink.contains("""class="sa-bouton sa-bouton--fonte">Délier</button>"""), unlink)
        assertFalse(html.contains("sa-compteur"), "plus de code une fois relié")
        assertTrue(html.contains("oooscar-fier.png"))
    }

    @Test
    @DisplayName("Lier (Xbox) : le gamertag connu par Xbox, Relier Discord en rouge quand l'application existe, sinon la phrase bientôt")
    fun linkByXbox() {
        fun view(discordButton: Boolean) =
            LierView(kind = LierKind.XBOX, code = null, secondsLeft = null, gamertag = "OscarLeBûcheron", discordButton = discordButton, csrf = "jeton")

        val withDiscord = render { lier(shell, accountPage, view(discordButton = true)) }
        assertWellFormed(withDiscord, "/compte/lier/ (Xbox, Discord)")
        val visible = text(main(withDiscord))
        assertTrue(visible.contains("Lier mon compte"))
        assertTrue(visible.contains("OscarLeBûcheron"))
        assertTrue(main(withDiscord).contains("""<a href="/connexion/discord/" class="sa-bouton">Relier Discord</a>"""), main(withDiscord))
        assertEquals(1, red(withDiscord))
        assertFalse(withDiscord.contains("arrive bientôt"))
        assertFalse(withDiscord.contains("sa-compteur"), "le jeu est connu : aucun code à taper")

        val without = render { lier(shell, accountPage, view(discordButton = false)) }
        assertWellFormed(without, "/compte/lier/ (Xbox, sans Discord)")
        assertFalse(without.contains(AccountUrls.DISCORD))
        assertTrue(text(main(without)).contains("Relier Discord arrive bientôt."))
        assertEquals(0, red(without))
    }

    @Test
    @DisplayName("Lier : le titre de l'onglet dit Lier mon compte")
    fun linkTitle() {
        val html = render { lier(shell, accountPage, codeView()) }
        assertTrue(html.contains("<title>Lier mon compte · Silooo</title>"), html.substringBefore("</head>"))
    }
}
