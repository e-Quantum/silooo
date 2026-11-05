package com.silooo.core.discord

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les giveaways : le lancement avec `/giveaway`, la limite du mois, le bouton Participer, le tirage (gagnant, lot en
 * base, carte, message privé, journal), le tirage sans participant, la reprise au démarrage, les refus de Discord.
 */
class GiveawayTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val seed = 7

    /** Un Discord qui refuse, à la demande, les prochaines publications ou modifications de messages. */
    private class FlakyServer(private val inner: FakeDiscordServer) : DiscordServer by inner {
        var failPosts = 0
        var failEdits = 0

        override suspend fun post(channelId: String, message: BotMessage): String {
            if (failPosts > 0) {
                failPosts--
                throw DiscordRefusal("Discord refuse le message")
            }
            return inner.post(channelId, message)
        }

        override suspend fun edit(channelId: String, messageId: String, message: BotMessage) {
            if (failEdits > 0) {
                failEdits--
                throw DiscordRefusal("Discord refuse la modification")
            }
            inner.edit(channelId, messageId, message)
        }
    }

    /** Un serveur, un temps et des giveaways à part pour chaque test ; [start] : l'heure de départ (11 h à Paris à l'heure d'hiver : 10 h UTC). */
    private inner class Setup(
        start: Instant = Instant.parse("2026-11-05T10:00:00Z"),
        withChannel: Boolean = true,
        perMonth: Int = 1,
    ) {
        val fake = FakeDiscordServer()
        val flaky = FlakyServer(fake)
        val timer = FakeTimer(start)
        val store = InMemoryGiveawayStore()
        val staff: String = runBlocking { fake.createRole(RoleSpec("staff", null, false, false)) }
        val role: String = runBlocking { fake.createRole(RoleSpec("giveaways", null, false, true)) }
        val channels: Map<String, String> = runBlocking {
            val category = fake.createCategory(CategorySpec("Le serveur en direct", null))
            buildMap {
                if (withChannel) put("giveaways", fake.createChannel(category, ChannelSpec("giveaways", ChannelKind.TEXTE, null, emptySet(), null, false)))
                put("journal-du-bot", fake.createChannel(category, ChannelSpec("journal-du-bot", ChannelKind.TEXTE, null, emptySet(), null, false)))
            }
        }
        val ids = ServerIds(mapOf("staff" to staff, "giveaways" to role), channels)
        val context = BotContext(flaky, ids, timer, ServerPhase.ATTENTE)
        val feature = GiveawayFeature(context, GiveawayRules(perMonth), zone, store, BotJournal(context), Random(seed))

        val cards: List<FakeDiscordServer.Posted> get() = fake.posted.filter { it.channelId == channels["giveaways"] }
        val journal: List<String> get() = fake.posted.filter { it.channelId == channels["journal-du-bot"] }.map { it.message.text }

        fun start() = runBlocking { feature.start() }

        fun advance(duration: Duration) = runBlocking { timer.advance(duration) }

        /** Un `/giveaway` du staff (ou de [roles]) ; une option à `null` est absente. [reply] : pour la lire même si la commande lève. */
        fun giveaway(
            title: String? = "Les 500 Gemmes de novembre",
            prize: String? = "gemmes",
            amount: String? = "500",
            cosmetic: String? = null,
            days: String? = "7",
            milestone: String? = null,
            roles: Set<String> = setOf(staff),
            reply: FakeReply = FakeReply(),
        ): FakeReply {
            val options = listOfNotNull(
                title?.let { "titre" to it },
                prize?.let { "lot" to it },
                amount?.let { "quantite" to it },
                cosmetic?.let { "cosmetique" to it },
                days?.let { "duree" to it },
                milestone?.let { "etape" to it },
            ).toMap()
            runBlocking { feature.handle(DiscordEvent.CommandUsed("giveaway", options, DiscordUser("42", "Kr", roleIds = roles), null, reply)) }
            return reply
        }

        /** Un clic sur Participer du giveaway [id] par [user] ; [raw] : un identifiant de bouton écrit à la main. */
        fun press(id: Int, user: String, bot: Boolean = false, raw: String? = null): FakeReply {
            val reply = FakeReply()
            val button = raw ?: ("giveaway-participer:" + id)
            runBlocking { feature.handle(DiscordEvent.ButtonPressed(button, DiscordUser(user, "Membre", isBot = bot), null, "1", reply)) }
            return reply
        }

        /** Les inscrits du giveaway [id], dans l'ordre. */
        fun entries(id: Int): List<String> = runBlocking { store.entries(id) }

        /** Le giveaway [id] tel que la base le garde. */
        fun stored(id: Int): Giveaway = store.giveaways.first { it.id == id }

        /** Un giveaway déjà en base avec sa carte postée, qui finit dans [endsIn] (négatif : déjà fini), et ses [entrants]. */
        fun existing(endsIn: Duration, entrants: List<String> = emptyList()): Int = runBlocking {
            val channel = checkNotNull(channels["giveaways"])
            val id = store.create("Un lot d'avant", "lingots", 2000, null, channel, timer.now() + endsIn, "42", timer.now() - Duration.ofDays(2))
            store.carded(id, fake.post(channel, BotMessage("La carte d'avant")))
            entrants.forEach { store.enter(id, it) }
            id
        }
    }

    private fun text(reply: FakeReply): String = reply.sent.single().first.text

    private val rulesLine = "Gratuit, sans achat, sans invitation ni boost. Un compte relié reçoit le lot en jeu ; sinon il attend la liaison."
    private val head = "Giveaway : Les 500 Gemmes de novembre\n\nLot : 500 Gemmes\nFin le 12 novembre 2026 à 11 h 00."

    @Test
    @DisplayName("La commande /giveaway est pour le staff, avec un titre, un lot, une quantité, un cosmétique, une durée et l'option étape")
    fun commandIsDeclared() {
        val command = Setup().feature.commands.single()
        assertEquals("giveaway", command.name)
        assertEquals("Lance un giveaway dans le salon des giveaways", command.description)
        assertTrue(command.staffOnly)
        assertEquals(listOf("titre", "lot", "quantite", "cosmetique", "duree", "etape"), command.options.map { it.name })
        assertEquals(listOf(true, true, false, false, true, false), command.options.map { it.required })
        assertEquals(listOf("cosmetique", "gemmes", "lingots"), command.options[1].choices)
        assertEquals(listOf("1", "3", "7"), command.options[4].choices)
        assertEquals(listOf("oui"), command.options[5].choices)
        assertTrue(command.options.all { it.description.length in 1..100 })
        assertEquals("giveaways", Setup().feature.name)
    }

    @Test
    @DisplayName("Le staff lance un giveaway : la carte dans #giveaways avec le rôle, le lot, la fin, la règle, le bouton, puis la réponse et le journal")
    fun staffLaunchesAGiveaway() {
        val setup = Setup()
        val reply = setup.giveaway()

        val card = setup.cards.single()
        assertEquals("<@&" + setup.role + "> " + head + "\n\n" + rulesLine + "\n\n0 participant", card.message.text)
        assertEquals(setOf(setup.role), card.message.mentionRoleIds)
        assertTrue(card.message.mentionUserIds.isEmpty())
        assertEquals(listOf("giveaway-participer:1"), card.message.buttons.map { it.id })
        assertEquals(listOf("Participer"), card.message.buttons.map { it.label })

        assertEquals("Giveaway n°1 lancé.", text(reply))
        assertTrue(reply.sent.single().second, "la réponse est pour le staff seul")
        assertEquals(
            Giveaway(
                1, "Les 500 Gemmes de novembre", "gemmes", 500, null, setup.channels["giveaways"]!!, card.messageId,
                Instant.parse("2026-11-12T10:00:00Z"), null, null, "42", Instant.parse("2026-11-05T10:00:00Z"),
            ),
            setup.stored(1),
        )
        assertEquals(
            listOf("Giveaway n°1 « Les 500 Gemmes de novembre » lancé par <@42>, fin le 12 novembre 2026 à 11 h 00."),
            setup.journal,
        )
        val line = setup.fake.posted.last().message
        assertTrue(line.mentionRoleIds.isEmpty() && line.mentionUserIds.isEmpty(), "le journal ne prévient personne")
        assertEquals(1, setup.timer.pending, "le tirage est programmé")
    }

    @Test
    @DisplayName("Des Lingots avec une quantité écrite en groupes de chiffres, un cosmétique qui ignore la quantité, une Gemme au singulier")
    fun prizeKinds() {
        val setup = Setup()
        setup.giveaway(title = "Des Lingots", prize = "lingots", amount = "2 000", days = "1")
        assertTrue(setup.cards.last().message.text.contains("Lot : 2 000 Lingots\nFin le 6 novembre 2026 à 11 h 00."))
        assertEquals(2000L, setup.stored(1).prizeAmount)

        setup.giveaway(title = "Un cosmétique", prize = "cosmetique", amount = "abc", cosmetic = "ciel-ancien", days = "3", milestone = "oui")
        assertTrue(setup.cards.last().message.text.contains("Lot : le cosmétique ciel-ancien\nFin le 8 novembre 2026 à 11 h 00."))
        assertEquals(0L, setup.stored(2).prizeAmount)
        assertEquals("ciel-ancien", setup.stored(2).prizeLabel)

        setup.giveaway(title = "Une Gemme", amount = "1", milestone = "oui")
        assertTrue(setup.cards.last().message.text.contains("Lot : 1 Gemme\n"))
    }

    @Test
    @DisplayName("Un giveaway par mois : le deuxième est refusé avec le mois suivant en lettres ; l'option étape passe ; le mois suivant le compte repart")
    fun monthlyLimit() {
        val setup = Setup()
        setup.giveaway()
        val refused = setup.giveaway(title = "Un deuxième")
        assertEquals(
            "Un giveaway par mois : le prochain à partir du 1er décembre. Pour une grande étape, ajoute l'option etape.",
            text(refused),
        )
        assertTrue(refused.sent.single().second)
        assertEquals(1, setup.cards.size)
        assertEquals(1, setup.store.giveaways.size)

        assertEquals("Giveaway n°2 lancé.", text(setup.giveaway(title = "Une grande étape", milestone = "oui")))
        assertEquals(2, setup.cards.size)

        setup.advance(Duration.ofDays(30))
        assertEquals("Giveaway n°3 lancé.", text(setup.giveaway(title = "Celui de décembre")))
    }

    @Test
    @DisplayName("Le mois de décembre renvoie au 1er janvier ; avec deux giveaways par mois, la limite parle au pluriel")
    fun limitWraps() {
        val december = Setup(start = Instant.parse("2026-12-20T10:00:00Z"))
        december.giveaway()
        assertEquals(
            "Un giveaway par mois : le prochain à partir du 1er janvier. Pour une grande étape, ajoute l'option etape.",
            text(december.giveaway(title = "Un autre")),
        )

        val twice = Setup(perMonth = 2)
        assertEquals("Giveaway n°1 lancé.", text(twice.giveaway()))
        assertEquals("Giveaway n°2 lancé.", text(twice.giveaway(title = "Le deuxième")))
        assertEquals(
            "2 giveaways par mois : le prochain à partir du 1er décembre. Pour une grande étape, ajoute l'option etape.",
            text(twice.giveaway(title = "Le troisième")),
        )
    }

    @Test
    @DisplayName("Un membre sans le rôle staff reçoit un refus et rien n'est créé")
    fun nonStaffIsRefused() {
        val setup = Setup()
        val reply = setup.giveaway(roles = emptySet())
        assertEquals("Cette commande est pour le staff.", text(reply))
        assertTrue(reply.sent.single().second)
        assertTrue(setup.cards.isEmpty() && setup.store.giveaways.isEmpty() && setup.journal.isEmpty())
    }

    @Test
    @DisplayName("Sans salon #giveaways sur le serveur, le staff le lit et rien n'est créé")
    fun missingChannel() {
        val setup = Setup(withChannel = false)
        assertEquals("Le salon des giveaways n'existe pas sur le serveur.", text(setup.giveaway()))
        assertTrue(setup.store.giveaways.isEmpty())
    }

    @Test
    @DisplayName("Chaque option manquante ou mauvaise donne un refus au staff seul, et rien n'est créé")
    fun badOptionsAreRefused() {
        val setup = Setup()
        val cases = listOf(
            setup.giveaway(title = null) to "Le titre est vide : rien n'est lancé.",
            setup.giveaway(title = "   ") to "Le titre est vide : rien n'est lancé.",
            setup.giveaway(title = "x".repeat(81)) to "Le titre fait 81 caractères : 80 au plus. Rien n'est lancé.",
            setup.giveaway(prize = null) to "Le lot doit être cosmetique, gemmes ou lingots. Rien n'est lancé.",
            setup.giveaway(prize = "euros") to "Le lot doit être cosmetique, gemmes ou lingots. Rien n'est lancé.",
            setup.giveaway(amount = null) to "La quantité doit être un entier positif : le nombre de Gemmes ou de Lingots. Rien n'est lancé.",
            setup.giveaway(amount = "0") to "La quantité doit être un entier positif : le nombre de Gemmes ou de Lingots. Rien n'est lancé.",
            setup.giveaway(amount = "-5") to "La quantité doit être un entier positif : le nombre de Gemmes ou de Lingots. Rien n'est lancé.",
            setup.giveaway(amount = "beaucoup") to "La quantité doit être un entier positif : le nombre de Gemmes ou de Lingots. Rien n'est lancé.",
            setup.giveaway(prize = "cosmetique", amount = null) to "Un cosmétique demande son identifiant dans l'option cosmetique. Rien n'est lancé.",
            setup.giveaway(prize = "cosmetique", cosmetic = "y".repeat(81)) to
                "L'identifiant du cosmétique fait 81 caractères : 80 au plus. Rien n'est lancé.",
            setup.giveaway(days = null) to "La durée doit être de 1, 3 ou 7 jours. Rien n'est lancé.",
            setup.giveaway(days = "2") to "La durée doit être de 1, 3 ou 7 jours. Rien n'est lancé.",
            setup.giveaway(days = "sept") to "La durée doit être de 1, 3 ou 7 jours. Rien n'est lancé.",
        )
        for ((reply, expected) in cases) {
            assertEquals(expected, text(reply))
            assertTrue(reply.sent.single().second)
        }
        assertTrue(setup.cards.isEmpty() && setup.store.giveaways.isEmpty() && setup.journal.isEmpty())
        assertEquals(0, setup.timer.pending)
    }

    @Test
    @DisplayName("Si Discord refuse la carte, le staff le lit, le giveaway est clos et ne compte pas dans le mois : on peut relancer")
    fun refusedCardDoesNotCount() {
        val setup = Setup()
        setup.flaky.failPosts = 1
        val reply = FakeReply()
        assertThrows(DiscordRefusal::class.java) { setup.giveaway(reply = reply) }
        assertEquals("Discord a refusé la carte : le giveaway n'est pas lancé. Relance la commande.", text(reply))
        assertTrue(setup.cards.isEmpty())
        assertNotNull(setup.stored(1).drawnAt, "le giveaway sans carte est clos")
        assertEquals(0, setup.timer.pending)

        assertEquals("Giveaway n°2 lancé.", text(setup.giveaway()))
        assertEquals(1, setup.cards.size)
    }

    @Test
    @DisplayName("Participer : la réponse donne la date du tirage, la carte compte les participants (« 1 participant » puis « 37 participants »)")
    fun enteringUpdatesTheCard() {
        val setup = Setup()
        setup.giveaway()

        val first = setup.press(1, "100")
        assertEquals("Tu participes. Tirage le 12 novembre 2026 à 11 h 00.", text(first))
        assertTrue(first.sent.single().second, "la réponse est pour le membre seul")
        val edit = setup.fake.edits.last()
        assertEquals(setup.cards.single().messageId, edit.messageId)
        assertEquals("<@&" + setup.role + "> " + head + "\n\n" + rulesLine + "\n\n1 participant", edit.message.text)
        assertEquals(listOf("giveaway-participer:1"), edit.message.buttons.map { it.id })
        assertTrue(edit.message.mentionRoleIds.isEmpty(), "modifier la carte ne prévient personne")

        for (user in 101..136) setup.press(1, user.toString())
        assertTrue(setup.fake.edits.last().message.text.endsWith("\n\n37 participants"))
        assertEquals(37, setup.entries(1).size)
    }

    @Test
    @DisplayName("Participer deux fois, c'est refusé sans rien changer ; un bot, un giveaway inconnu ou illisible aussi")
    fun refusedEntries() {
        val setup = Setup()
        setup.giveaway()
        setup.press(1, "100")
        val edits = setup.fake.edits.size

        val twice = setup.press(1, "100")
        assertEquals("Tu participes déjà.", text(twice))
        assertTrue(twice.sent.single().second)
        assertEquals(edits, setup.fake.edits.size, "la carte ne bouge pas")

        assertEquals("Les bots ne participent pas.", text(setup.press(1, "55", bot = true)))
        assertEquals("Ce giveaway est fini.", text(setup.press(99, "101")))
        assertEquals("Ce giveaway est fini.", text(setup.press(1, "101", raw = "giveaway-participer:abc")))
        assertEquals(listOf("100"), setup.entries(1))
    }

    @Test
    @DisplayName("Si Discord refuse de modifier la carte, la participation tient quand même")
    fun entryHoldsWhenTheCardEditIsRefused() {
        val setup = Setup()
        setup.giveaway()
        setup.flaky.failEdits = 1
        val reply = setup.press(1, "100")
        assertEquals("Tu participes. Tirage le 12 novembre 2026 à 11 h 00.", text(reply))
        assertEquals(listOf("100"), setup.entries(1))
        assertTrue(setup.fake.edits.isEmpty())
        setup.press(1, "101")
        assertTrue(setup.fake.edits.single().message.text.endsWith("\n\n2 participants"), "la carte rattrape au suivant")
    }

    @Test
    @DisplayName("À l'heure dite, un gagnant est tiré au hasard : la carte, le lot en base, le message privé, le journal et les participants")
    fun drawsAWinner() {
        val setup = Setup()
        setup.giveaway()
        val entrants = listOf("101", "102", "103")
        entrants.forEach { setup.press(1, it) }
        val winner = entrants[Random(seed).nextInt(entrants.size)]

        setup.advance(Duration.ofDays(7) - Duration.ofSeconds(1))
        assertNull(setup.stored(1).drawnAt, "pas avant l'heure")
        setup.advance(Duration.ofSeconds(1))

        val stored = setup.stored(1)
        assertEquals(Instant.parse("2026-11-12T10:00:00Z"), stored.drawnAt)
        assertEquals(winner, stored.winnerId)
        assertEquals(
            listOf(InMemoryGiveawayStore.Prize(winner, 1, "gemmes", 500, null, Instant.parse("2026-11-12T10:00:00Z"))),
            setup.store.prizes,
            "le lot est gardé sur l'identifiant Discord, pas encore crédité",
        )

        val card = setup.fake.edits.last()
        assertEquals(setup.cards.single().messageId, card.messageId)
        assertEquals(head + "\n\nGagné par <@" + winner + ">.", card.message.text)
        assertEquals(setOf(winner), card.message.mentionUserIds)
        assertTrue(card.message.buttons.isEmpty() && card.message.mentionRoleIds.isEmpty())

        val direct = setup.fake.directs.single()
        assertEquals(winner, direct.first)
        assertEquals(
            "Tu as gagné le giveaway « Les 500 Gemmes de novembre » : 500 Gemmes. " +
                "Le lot arrive en jeu sur ton compte relié ; sinon, relie ton compte avec /lier et il suivra.",
            direct.second.text,
        )

        assertEquals(
            listOf(
                "Giveaway n°1 « Les 500 Gemmes de novembre » lancé par <@42>, fin le 12 novembre 2026 à 11 h 00.",
                "Giveaway n°1 « Les 500 Gemmes de novembre » : 3 participants, gagné par <@" + winner + ">.",
                "<@101> <@102> <@103>",
            ),
            setup.journal,
        )
        assertEquals(0, setup.timer.pending)

        assertEquals("Ce giveaway est fini.", text(setup.press(1, "104")))
        setup.advance(Duration.ofDays(3))
        assertEquals(1, setup.store.prizes.size, "un seul tirage")
    }

    @Test
    @DisplayName("Sans participant : pas de tirage, la carte le dit sans bouton, rien en base, aucun message privé, le journal le dit")
    fun drawWithoutEntrants() {
        val setup = Setup()
        setup.giveaway()
        setup.advance(Duration.ofDays(7))

        val stored = setup.stored(1)
        assertEquals(Instant.parse("2026-11-12T10:00:00Z"), stored.drawnAt)
        assertNull(stored.winnerId)
        assertTrue(setup.store.prizes.isEmpty())
        assertTrue(setup.fake.directs.isEmpty())
        val card = setup.fake.edits.single()
        assertEquals(head + "\n\nPersonne n'a participé : pas de tirage.", card.message.text)
        assertTrue(card.message.buttons.isEmpty() && card.message.mentionUserIds.isEmpty())
        assertEquals("Giveaway n°1 « Les 500 Gemmes de novembre » : 0 participant, pas de tirage.", setup.journal.last())
        assertEquals(2, setup.journal.size)
    }

    @Test
    @DisplayName("Un cosmétique est gardé par son identifiant ; des messages privés fermés ne changent rien au reste")
    fun cosmeticWithClosedDirects() {
        val setup = Setup()
        setup.giveaway(title = "Le ciel ancien", prize = "cosmetique", cosmetic = "ciel-ancien", days = "1")
        setup.press(1, "101")
        setup.fake.closedDirects += "101"
        setup.advance(Duration.ofDays(1))

        assertEquals(
            listOf(InMemoryGiveawayStore.Prize("101", 1, "cosmetique", 0, "ciel-ancien", Instant.parse("2026-11-06T10:00:00Z"))),
            setup.store.prizes,
        )
        assertTrue(setup.fake.directs.isEmpty(), "le message privé est fermé : le bot n'insiste pas")
        assertTrue(setup.fake.edits.last().message.text.contains("Lot : le cosmétique ciel-ancien\n"))
        assertEquals("Giveaway n°1 « Le ciel ancien » : 1 participant, gagné par <@101>.", setup.journal[1])
        assertEquals("<@101>", setup.journal[2])
    }

    @Test
    @DisplayName("Trois cents participants vont au journal en mentions, par messages de 2 000 caractères au plus")
    fun participantsAreSplit() {
        val ids = (1..300).map { (100_000_000_000_000_000L + it).toString() }
        val messages = GiveawayTexts.participantMessages(ids)
        assertTrue(messages.size > 1)
        assertTrue(messages.all { it.length <= 2000 })
        assertEquals(ids.map { "<@" + it + ">" }, messages.flatMap { it.split(" ") })
        assertEquals(emptyList<String>(), GiveawayTexts.participantMessages(emptyList()))

        val setup = Setup()
        setup.giveaway()
        ids.forEach { id -> runBlocking { setup.store.enter(1, id) } }
        setup.advance(Duration.ofDays(7))
        val posted = setup.journal.drop(2)
        assertEquals(messages, posted)
        val winner = setup.stored(1).winnerId
        assertTrue(winner in ids)
    }

    @Test
    @DisplayName("Au démarrage, un giveaway ouvert reprend sa minuterie et un giveaway échu est tiré tout de suite ; un giveaway tiré est laissé")
    fun resumesAtStart() {
        val setup = Setup()
        val upcoming = setup.existing(Duration.ofDays(2), listOf("201"))
        val overdue = setup.existing(Duration.ofHours(-1), listOf("202"))
        val finished = setup.existing(Duration.ofHours(-5), listOf("203"))
        runBlocking { setup.store.drawn(finished, "203", setup.timer.now() - Duration.ofHours(4)) }
        assertEquals(0, setup.timer.pending)

        setup.start()
        assertEquals(2, setup.timer.pending, "une minuterie par giveaway ouvert")
        setup.advance(Duration.ZERO)
        assertEquals("202", setup.stored(overdue).winnerId, "l'échu est tiré tout de suite")
        assertNull(setup.stored(upcoming).drawnAt)
        assertEquals(listOf("202"), setup.fake.directs.map { it.first })

        setup.advance(Duration.ofDays(2))
        assertEquals("201", setup.stored(upcoming).winnerId)
        assertEquals("203", setup.stored(finished).winnerId)
        assertEquals(listOf("202", "201"), setup.store.prizes.map { it.userId })
        assertEquals(0, setup.timer.pending)
    }

    @Test
    @DisplayName("Si Discord refuse la carte au tirage, le bot reprend une minute plus tard sans tirer deux fois ni doubler un message")
    fun retriesAfterARefusal() {
        val setup = Setup()
        setup.giveaway()
        setup.press(1, "101")
        setup.press(1, "102")
        val editsBefore = setup.fake.edits.size
        val winner = setup.entries(1).let { it[Random(seed).nextInt(it.size)] }
        setup.flaky.failEdits = 2

        setup.advance(Duration.ofDays(7))
        assertEquals(winner, setup.stored(1).winnerId, "le gagnant est en base avant les messages")
        assertEquals(1, setup.store.prizes.size)
        assertEquals(editsBefore, setup.fake.edits.size, "la carte n'a pas pu être modifiée")
        assertTrue(setup.fake.directs.isEmpty())
        assertEquals(1, setup.journal.size, "le journal garde seulement le lancement")
        assertEquals(1, setup.timer.pending, "une nouvelle tentative est programmée")

        setup.advance(Duration.ofSeconds(59))
        assertEquals(editsBefore, setup.fake.edits.size)
        setup.advance(Duration.ofSeconds(1))
        assertEquals(editsBefore, setup.fake.edits.size, "la deuxième tentative est refusée aussi")
        assertEquals(1, setup.timer.pending)

        setup.advance(Duration.ofMinutes(1))
        assertEquals(editsBefore + 1, setup.fake.edits.size)
        assertEquals(head + "\n\nGagné par <@" + winner + ">.", setup.fake.edits.last().message.text)
        assertEquals(listOf(winner), setup.fake.directs.map { it.first })
        assertEquals(3, setup.journal.size)
        assertEquals(1, setup.store.prizes.size, "le lot n'est pas doublé")
        assertEquals(winner, setup.stored(1).winnerId, "le gagnant ne change pas")
        assertEquals(0, setup.timer.pending)

        setup.advance(Duration.ofMinutes(10))
        assertEquals(1, setup.fake.directs.size)
        assertEquals(3, setup.journal.size)
    }
}
