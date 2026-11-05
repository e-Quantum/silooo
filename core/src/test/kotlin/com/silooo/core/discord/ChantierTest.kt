package com.silooo.core.discord

import com.silooo.core.site.ChantierState
import com.silooo.core.site.ChantierStep
import com.silooo.core.site.NewsArticles
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le chantier en direct : le message des coulisses, ses trois groupes, les adresses, l'épingle posée une seule fois, le message modifié sur place. */
class ChantierTest {
    private val steps = listOf(
        ChantierStep("Plans du jeu", ChantierState.FAITE, "/actus/plans-du-jeu/"),
        ChantierStep("Moteur du jeu", ChantierState.FAITE, "/actus/moteur-du-jeu/"),
        ChantierStep("La première heure se joue", ChantierState.EN_COURS, null),
        ChantierStep("La bêta fermée", ChantierState.A_VENIR, null),
    )

    private val expected =
        "Le chantier en direct\n\nFaites\nPlans du jeu : https://silooo.com/actus/plans-du-jeu/\n" +
            "Moteur du jeu : https://silooo.com/actus/moteur-du-jeu/\n\nEn cours\nLa première heure se joue\n\nÀ venir\nLa bêta fermée"

    private inner class Setup(
        withCoulisses: Boolean = true,
        val messages: InMemoryBotMessageStore = InMemoryBotMessageStore(),
        val fake: FakeDiscordServer = FakeDiscordServer(),
    ) {
        val timer = FakeTimer()
        private val category = runBlocking { fake.createCategory(CategorySpec("Coulisses", null)) }

        private fun channel(name: String): String =
            runBlocking { fake.createChannel(category, ChannelSpec(name, ChannelKind.TEXTE, null, null, null, false)) }

        val coulisses: String? = if (withCoulisses) channel("coulisses") else null
        val journalChannel = channel("journal-du-bot")
        val ids = ServerIds(emptyMap(), listOfNotNull(coulisses?.let { "coulisses" to it }, "journal-du-bot" to journalChannel).toMap())

        /** La fonction sur le faux serveur, ou sur [server] (qui peut en dériver un refus). */
        fun feature(
            server: DiscordServer = fake,
            read: () -> List<ChantierStep> = { steps },
            siteUrl: String = "https://silooo.com",
        ): ChantierFeature {
            val context = BotContext(server, ids, timer, ServerPhase.ATTENTE)
            return ChantierFeature(context, messages, BotJournal(context), siteUrl, read)
        }

        fun start(feature: ChantierFeature) = runBlocking { feature.start() }

        fun posts() = fake.posted.filter { it.channelId == coulisses }

        fun edits() = fake.edits.filter { it.channelId == coulisses }
    }

    @Test
    @DisplayName("Le message montre le titre puis les étapes faites avec leur adresse, en cours et à venir, en carte et sans mention")
    fun postsTheThreeGroups() {
        val setup = Setup()
        setup.start(setup.feature())

        val post = setup.posts().single()
        assertEquals(expected, post.message.text)
        assertTrue(post.message.card)
        assertTrue(post.message.mentionUserIds.isEmpty())
        assertTrue(post.message.mentionRoleIds.isEmpty())
        assertEquals(BotMessageRef(setup.coulisses!!, post.messageId), setup.messages.refs["chantier"])
    }

    @Test
    @DisplayName("Le message est épinglé à la première publication, et seulement elle")
    fun pinsOnlyTheFirstTime() {
        val setup = Setup()
        setup.start(setup.feature())
        val post = setup.posts().single()
        assertEquals(listOf(setup.coulisses!! to post.messageId), setup.fake.pinned)

        setup.start(setup.feature())
        assertEquals(1, setup.posts().size)
        assertEquals(1, setup.edits().size)
        assertEquals(1, setup.fake.pinned.size, "une modification n'épingle pas de nouveau")
    }

    @Test
    @DisplayName("Après un déploiement qui change les actus, le message est modifié sur place avec les étapes neuves")
    fun editsWhenTheStepsChange() {
        val setup = Setup()
        setup.start(setup.feature())
        val changed = steps.map { if (it.title == "La première heure se joue") ChantierStep(it.title, ChantierState.FAITE, "/actus/premiere-heure/") else it }
        setup.start(setup.feature(read = { changed.dropLast(1) + ChantierStep("La bêta fermée", ChantierState.EN_COURS, null) }))

        assertEquals(1, setup.posts().size)
        assertTrue(setup.edits().single().message.text.contains("En cours\nLa bêta fermée"))
        assertFalse(setup.edits().single().message.text.contains("À venir"), "un groupe vide n'est pas écrit")
        assertTrue(setup.edits().single().message.text.contains("La première heure se joue : https://silooo.com/actus/premiere-heure/"))
    }

    @Test
    @DisplayName("Un message supprimé à la main est reposté, noté de nouveau et épinglé de nouveau")
    fun repostsAMessageThatIsGone() {
        val setup = Setup()
        setup.messages.refs["chantier"] = BotMessageRef(setup.coulisses!!, "999")
        setup.start(setup.feature())

        val post = setup.posts().single()
        assertEquals(expected, post.message.text)
        assertEquals(BotMessageRef(setup.coulisses, post.messageId), setup.messages.refs["chantier"])
        assertEquals(listOf(setup.coulisses to post.messageId), setup.fake.pinned)
        assertTrue(setup.edits().isEmpty())
    }

    @Test
    @DisplayName("Un épinglage refusé laisse le message posté et le dit au journal du bot")
    fun survivesAPinRefusal() {
        val setup = Setup()
        val refusing = object : DiscordServer by setup.fake {
            override suspend fun pin(channelId: String, messageId: String) {
                throw DiscordRefusal("trop de messages épinglés")
            }
        }
        setup.start(setup.feature(server = refusing))

        assertEquals(1, setup.posts().size)
        assertTrue(setup.fake.pinned.isEmpty())
        assertEquals(
            listOf("Le chantier en direct n'a pas pu être épinglé dans les coulisses."),
            setup.fake.posted.filter { it.channelId == setup.journalChannel }.map { it.message.text },
        )
    }

    @Test
    @DisplayName("L'adresse d'un site réglé avec une barre finale ne double pas la barre, et une adresse complète reste telle quelle")
    fun buildsAddresses() {
        val setup = Setup()
        val feature = setup.feature(
            read = {
                listOf(
                    ChantierStep("Relative", ChantierState.FAITE, "/actus/a/"),
                    ChantierStep("Complète", ChantierState.FAITE, "https://exemple.org/b/"),
                )
            },
            siteUrl = "https://beta.silooo.com/",
        )
        setup.start(feature)

        assertEquals(
            "Le chantier en direct\n\nFaites\nRelative : https://beta.silooo.com/actus/a/\nComplète : https://exemple.org/b/",
            setup.posts().single().message.text,
        )
    }

    @Test
    @DisplayName("Trop d'étapes pour un message de Discord : le message est coupé entre deux lignes, sous la limite")
    fun staysUnderTheLimit() {
        val setup = Setup()
        val many = (1..300).map { ChantierStep("Étape " + it + " " + "x".repeat(40), ChantierState.FAITE, "/actus/etape-" + it + "/") }
        setup.start(setup.feature(read = { many }))

        val text = setup.posts().single().message.text
        assertTrue(text.length <= BotMessage.MAX_TEXT)
        assertTrue(text.lines().all { it.isEmpty() || it.startsWith("Étape ") || it == "Le chantier en direct" || it == "Faites" })
    }

    @Test
    @DisplayName("Sans le salon des coulisses sur le serveur, les actus ne sont même pas lues et rien n'est posté")
    fun doesNothingWithoutCoulisses() {
        val setup = Setup(withCoulisses = false)
        setup.start(setup.feature(read = { error("les actus ne devraient pas être lues") }))

        assertTrue(setup.fake.posted.isEmpty())
        assertTrue(setup.fake.pinned.isEmpty())
        assertTrue(setup.messages.refs.isEmpty())
    }

    @Test
    @DisplayName("Par défaut le message vient des actus du jar : le titre, et toutes les étapes du site")
    fun readsTheBundledNews() {
        val setup = Setup()
        val context = BotContext(setup.fake, setup.ids, setup.timer, ServerPhase.ATTENTE)
        runBlocking { ChantierFeature(context, setup.messages, BotJournal(context)).start() }

        val text = setup.posts().single().message.text
        val bundled = NewsArticles.chantier(NewsArticles.fromClasspath())
        assertTrue(bundled.isNotEmpty())
        assertTrue(text.startsWith("Le chantier en direct\n\nFaites\n"))
        for (step in bundled) assertTrue(text.contains(step.title), "l'étape " + step.title)
        for (step in bundled.filter { it.url != null }) assertTrue(text.contains("https://silooo.com" + step.url), "l'adresse de " + step.title)
        assertTrue(text.length <= BotMessage.MAX_TEXT)
    }
}
