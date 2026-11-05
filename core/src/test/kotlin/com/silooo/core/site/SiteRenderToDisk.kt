package com.silooo.core.site

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.db.FakeDatabase
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.registration.DiscordLinks
import com.silooo.core.registration.FakeDiscordIdentity
import com.silooo.core.registration.FakeXboxIdentity
import com.silooo.core.registration.InMemoryBetaAccessStore
import com.silooo.core.registration.InMemoryDiscordLinkStore
import com.silooo.core.registration.InMemorySiteRegistrationStore
import com.silooo.core.registration.InMemoryWaitlistStore
import com.silooo.core.registration.InMemoryWebAccountStore
import com.silooo.core.registration.InMemoryWebSessionStore
import com.silooo.core.server.ServerPresence
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.sitedata.CompanyCard
import com.silooo.core.sitedata.CompanyMember
import com.silooo.core.sitedata.InMemorySiteStatsStore
import com.silooo.core.sitedata.MemberRole
import com.silooo.core.vote.InMemoryVoteStore
import com.silooo.core.vote.VoteRow
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import com.silooo.core.registration.XboxFailure
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Outil de relecture, pas un test : écrit les pages rendues et le dossier `public/` dans le dossier que désigne
 * `SILOOO_SITE_RENDER_DIR`. Gradle pose la variable à chaque passage des tests (`core/build/site-rendu/`) :
 * `tools/verifier_site.mjs` ouvre ces pages dans Chromium, et les captures de relecture (360 et 1 280 px) en partent.
 * Il ne vérifie rien : les pages se contrôlent dans [SiteStyleTest] et [SiteModuleTest].
 *
 * Les dossiers : `/` (attente), `discord/` (attente avec le Discord), `ouvert/` (tout le mode ouvert, Discord posé),
 * `ouvert-complet/` (boutique, sites de vote, actus d'exemple), `ouvert-vivant/` (classement et fiche d'entreprise
 * avec des données), les comptes dans chaque mode (`connexion/`, `pionniers/` et `pionniers/etat/`, `beta/compte/`,
 * `beta/compte/lier/`, `connexion/refus/` : voir `renderAccountsAndNotFound`), `introuvable/` (le 404), et
 * `site/direct.json` (la réponse du bandeau direct avec trois joueurs et deux votes : le vérificateur sert la racine du rendu,
 * `direct.js` y relit `/site/direct.json`).
 */
@EnabledIfEnvironmentVariable(named = "SILOOO_SITE_RENDER_DIR", matches = ".+")
class SiteRenderToDisk {
    private val out: Path = Path.of(System.getenv("SILOOO_SITE_RENDER_DIR"))
    private val clock = MutableClock(Instant.parse("2026-11-05T12:00:00Z"))

    private fun write(file: String, html: String) {
        val target = out.resolve(file)
        Files.createDirectories(target.parent)
        Files.writeString(target, html)
    }

    @Test
    @DisplayName("Écrit les pages du site sur le disque pour les relire dans un navigateur")
    fun render() {
        copyPublic()
        renderLiving()
        renderModes()
        renderAccountsAndNotFound()
    }

    /** `public/` tel que Gradle l'a composé (les copies de design/, composants.css dérivé, les polices). */
    private fun copyPublic() {
        val publicSource = Path.of(checkNotNull(SiteRenderToDisk::class.java.classLoader.getResource("site/public")).toURI())
        Files.walk(publicSource).use { paths ->
            for (source in paths) {
                val target = out.resolve("public").resolve(publicSource.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** Le classement et une fiche, avec un faux dépôt et une présence : les données vivantes que le mode ouvert montre. */
    private fun renderLiving() {
        val stats = InMemorySiteStatsStore().apply {
            entries += InMemorySiteStatsStore.Entry("2535400000000001", "Kr Test", 1L, "Les Silos du Ciel", 1_250_000)
            entries += InMemorySiteStatsStore.Entry("2535400000000002", "Oooscar", 1L, "Les Silos du Ciel", 98_400)
            entries += InMemorySiteStatsStore.Entry("2535400000000003", "Joueuse a un nom assez long pour passer à la ligne", 2L, "Atelier des Nuages avec un nom long", 4_200)
            entries += InMemorySiteStatsStore.Entry("2535400000000004", "Sans Toit", null, null, 35)
            companies[1L] = CompanyCard(
                1L, "Les Silos du Ciel", era = 2, stars = 14, createdAt = Instant.parse("2026-10-12T09:00:00Z"), prestiges = 3,
                members = listOf(CompanyMember("Kr Test", MemberRole.PDG), CompanyMember("Oooscar", MemberRole.ASSOCIE)),
            )
        }
        val presence = ServerPresence(clock).apply { record("bds-1", listOf("1", "2", "3")) }
        // Deux votes du jour : le bandeau direct montre la Vote party avec un compte.
        val votes = InMemoryVoteStore(InMemorySessionStore()).apply {
            rows += VoteRow(1, null, "Kr Test", "premier", clock.instant(), false)
            rows += VoteRow(2, null, "Oooscar", "second", clock.instant(), false)
        }
        testApplication {
            application {
                siteModule(
                    Dependencies(
                        PassThroughDatabase(), "cle", serverPresence = presence, definitions = TestDefinitions.loaded, clock = clock,
                        siteMode = SiteMode.OUVERT, siteStatsStore = stats, voteStore = votes,
                    ),
                )
            }
            val client = createClient { followRedirects = false }
            for ((route, file) in listOf("/" to "ouvert-vivant/index.html", "/classements/" to "ouvert-vivant/classements/index.html", "/entreprise/1/" to "ouvert-vivant/entreprise/1/index.html")) {
                write(file, client.get(route).bodyAsText())
            }
            write("site/direct.json", client.get("/site/direct.json").bodyAsText())
        }
    }

    /** Les deux modes, avec et sans Discord, avec et sans boutique, sites de vote et actus. */
    private fun renderModes() {
        // Deux actus d'exemple au plan fixe, pour voir la liste et un article remplis ; elles ne sont écrites nulle part dans le dépôt.
        val sample = listOf(
            NewsArticles.parse(
                "exemple-semaine-deux.md",
                "---\ntitre: Une semaine de plus\ndescription: Exemple.\ndate: 2026-12-01\n---\nTexte d'introduction d'exemple.\n\n" +
                    "## Nouveau\n\n- Un premier point d'exemple.\n- Un second point d'exemple.\n- Un troisième point d'exemple.\n\n" +
                    "## Changements\n\n- Un changement d'exemple.\n- Un autre changement d'exemple.\n\n" +
                    "## Corrections\n\n- Une correction d'exemple.\n",
            ),
            NewsArticles.parse(
                "exemple-ouverture.md",
                "---\ntitre: Le serveur ouvre\ndescription: Exemple.\ndate: 2026-11-20\n---\n\n## Nouveau\n\n- Un point d'exemple.\n\n## Corrections\n\n- Une correction d'exemple.\n",
            ),
        )
        val pages = listOf(
            Triple(
                SiteMode.ATTENTE,
                null as String?,
                listOf(
                    "/" to "index.html",
                    "/actus/" to "actus/index.html",
                    "/actus/plans-du-jeu/" to "actus/plans-du-jeu/index.html",
                    "/mentions-legales/" to "mentions-legales/index.html",
                    "/cgv/" to "cgv/index.html",
                    "/confidentialite/" to "confidentialite/index.html",
                    "/parents/" to "parents/index.html",
                    "/discord/" to "discord/index.html",
                    "/wiki/" to "introuvable/index.html",
                ),
            ),
            // La page du Discord avec l'invitation configurée (sans elle, discord/index.html ci-dessus).
            Triple(SiteMode.ATTENTE, "https://discord.gg/exemple", listOf("/discord/" to "discord-invitation/index.html")),
            Triple(
                SiteMode.OUVERT,
                null,
                listOf(
                    "/" to "ouvert/index.html",
                    "/mentions-legales/" to "ouvert/mentions-legales/index.html",
                    "/rejoindre/" to "ouvert/rejoindre/index.html",
                    "/ce-qui-se-vend/" to "ouvert/ce-qui-se-vend/index.html",
                    "/boutique/" to "ouvert/boutique/index.html",
                    "/vote/" to "ouvert/vote/index.html",
                    "/actus/" to "ouvert/actus/index.html",
                ) + WikiContent.fromClasspath().map { it.url to "ouvert${it.url}index.html" },
            ),
            // Avec la boutique, deux sites de vote et le Discord posés : les liens et les boutons que le mode ouvert montre alors.
            Triple(
                SiteMode.OUVERT,
                "https://discord.gg/exemple",
                listOf(
                    "/" to "ouvert-complet/index.html",
                    "/boutique/" to "ouvert-complet/boutique/index.html",
                    "/vote/" to "ouvert-complet/vote/index.html",
                    "/actus/" to "ouvert-complet/actus/index.html",
                    "/discord/" to "ouvert-complet/discord/index.html",
                    sample[0].url to "ouvert-complet${sample[0].url}index.html",
                ),
            ),
        )
        val settings = SiteSettings(
            shopUrl = "https://boutique.exemple.test/",
            voteSites = listOf(VoteSite("Premier site", "https://un.exemple.test/"), VoteSite("Second site", "https://deux.exemple.test/")),
            sileneMcUrl = "https://silenemc.exemple.test/",
            hubyyUrl = "https://hubyy.exemple.test/",
        )
        for ((mode, discord, routes) in pages) {
            val complete = routes.first().second.startsWith("ouvert-complet")
            testApplication {
                application {
                    siteModule(
                        Dependencies(
                            FakeDatabase(), "cle", definitions = TestDefinitions.loaded, siteMode = mode, discordUrl = discord,
                            siteSettings = if (complete) settings else SiteSettings(),
                        ),
                        // Les actus livrées (le chantier de l'attente, l'article des plans du jeu), et les deux d'exemple en plus pour le mode complet.
                        articles = if (complete) sample + NewsArticles.fromClasspath() else NewsArticles.fromClasspath(),
                    )
                }
                val client = createClient { followRedirects = false }
                for ((route, file) in routes) write(file, client.get(route).bodyAsText())
            }
        }
    }

    /**
     * Les pages des comptes dans chaque mode : la connexion, l'inscription et « C'est noté ! » (par Xbox, par Discord) tant que
     * le site n'est pas ouvert, « Mon compte » et « Lier mon compte » (le code, le compte Xbox, « Relié ! ») dès la bêta, les
     * refus de Xbox (compte enfant, panne) ; puis la page introuvable.
     */
    private fun renderAccountsAndNotFound() {
        for (mode in SiteMode.entries) {
            val prefix = when (mode) {
                SiteMode.ATTENTE -> ""
                SiteMode.BETA -> "beta/"
                SiteMode.OUVERT -> "ouvert/"
            }
            testApplication {
                val xbox = FakeXboxIdentity()
                val accounts = InMemoryWebAccountStore()
                val links = InMemoryDiscordLinkStore()
                val registrations = InMemorySiteRegistrationStore()
                val database = PassThroughDatabase()
                application {
                    siteModule(
                        Dependencies(
                            database, "cle", definitions = TestDefinitions.loaded, clock = clock, siteMode = mode,
                            siteUrl = "https://silooo.test", siteRegistrationStore = registrations, discordUrl = "https://discord.gg/exemple",
                            discordIdentity = FakeDiscordIdentity("123"), xboxIdentity = xbox,
                            webAccountStore = accounts, waitlistStore = InMemoryWaitlistStore(), discordLinkStore = links,
                            betaAccessStore = InMemoryBetaAccessStore(), webSessionStore = InMemoryWebSessionStore(),
                        ),
                    )
                }
                val client = createClient { followRedirects = false }
                fun cookie(response: HttpResponse, name: String): String =
                    checkNotNull(response.headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("$name=") }) { "le cookie $name manque" }.substringBefore(';')
                /** Le départ puis le retour d'une porte : la réponse du retour. */
                suspend fun back(door: String): HttpResponse {
                    val start = client.get("/connexion/$door/")
                    val location = checkNotNull(start.headers[HttpHeaders.Location]) { "pas de Location au départ vers $door" }
                    val state = checkNotNull(Regex("state=([0-9a-f]+)").find(location)) { "pas d'état dans $location" }.groupValues[1]
                    return client.get("/connexion/$door/retour/?state=$state&code=code-de-rendu") { header(HttpHeaders.Cookie, cookie(start, "silooo_porte")) }
                }
                suspend fun fetch(path: String, session: String? = null): String = client.get(path) { session?.let { header(HttpHeaders.Cookie, it) } }.bodyAsText()

                write("${prefix}connexion/index.html", fetch("/connexion/"))
                val byXbox = cookie(back("xbox"), "silooo_session")
                val byDiscord = cookie(back("discord"), "silooo_session")
                if (mode != SiteMode.OUVERT) {
                    write("${prefix}pionniers/index.html", fetch("/pionniers/"))
                    write("${prefix}pionniers/etat/index.html", fetch("/pionniers/etat/", byXbox))
                    write("${prefix}pionniers/etat-discord/index.html", fetch("/pionniers/etat/", byDiscord))
                }
                if (mode != SiteMode.ATTENTE) {
                    write("${prefix}compte/index.html", fetch("/compte/", byXbox))
                    write("${prefix}compte/discord/index.html", fetch("/compte/", byDiscord))
                    write("${prefix}compte/lier/index.html", fetch("/compte/lier/", byDiscord))
                    write("${prefix}compte/lier-xbox/index.html", fetch("/compte/lier/", byXbox))
                    // Le jeu consomme le code (sa route viendra avec la direction du jeu) : la page « Relié ! ».
                    val discordAccount = checkNotNull(database.transaction { accounts.findByDiscord(it, "123") })
                    val code = checkNotNull(database.transaction { links.findByAccount(it, discordAccount.id) }).code
                    database.transaction { DiscordLinks(accounts, links, registrations, clock).redeem(it, code, "Kr Discord", admit = true, pioneer = false) }
                    write("${prefix}compte/lier-relie/index.html", fetch("/compte/lier/", byDiscord))
                }
                xbox.failure = XboxFailure.CHILD_ACCOUNT
                write("${prefix}connexion/refus/index.html", back("xbox").bodyAsText())
                xbox.failure = XboxFailure.UNAVAILABLE
                write("${prefix}connexion/panne/index.html", back("xbox").bodyAsText())
                write("${prefix}introuvable/index.html", fetch("/nulle-part/"))
            }
        }
    }
}
