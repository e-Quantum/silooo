package com.silooo.core.discord

import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.registration.InMemorySiteRegistrationStore
import com.silooo.core.registration.SiteRegistrations
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les admissions : une carte par inscription, une seule, les trois boutons du staff, le rôle Bêta et l'invitation en
 * privé, le journal. La base est un faux en mémoire ; le SQL se teste sur MariaDB (`AdmissionSqlTest`).
 */
class AdmissionTest {
    private val rules = AdmissionRules(refreshSeconds = 60)
    private val zone = ZoneId.of("Europe/Paris")

    /** 17 h 10 UTC : 18 h 10 à Paris en novembre. */
    private val registeredAt = Instant.parse("2026-11-05T17:10:00Z")

    /** Un serveur, un temps, une base et des inscriptions à part pour chaque test. */
    private inner class Setup(withChannel: Boolean = true, withJournal: Boolean = true, withBeta: Boolean = true) {
        val fake = FakeDiscordServer()
        val timer = FakeTimer()
        val store = InMemoryAdmissionStore()
        val site = InMemorySiteRegistrationStore()
        val database = PassThroughDatabase()
        val staff: String = runBlocking { fake.createRole(RoleSpec("staff", null, false, false)) }
        val beta: String = runBlocking { fake.createRole(RoleSpec("beta", null, false, false)) }
        val channels: Map<String, String> = runBlocking {
            val category = fake.createCategory(CategorySpec("Staff", null))
            buildMap {
                if (withChannel) put("admissions", fake.createChannel(category, ChannelSpec("admissions", ChannelKind.TEXTE, null, emptySet(), null, false)))
                if (withJournal) put("journal-du-bot", fake.createChannel(category, ChannelSpec("journal-du-bot", ChannelKind.TEXTE, null, emptySet(), null, false)))
            }
        }
        val ids = ServerIds(if (withBeta) mapOf("staff" to staff, "beta" to beta) else mapOf("staff" to staff), channels)
        val context = BotContext(fake, ids, timer, ServerPhase.ATTENTE)
        val feature = AdmissionFeature(context, rules, zone, store, site, database, BotJournal(context), hasGameProof = { true }, claimInvitation = { true })

        val cards: List<FakeDiscordServer.Posted> get() = fake.posted.filter { it.channelId == channels["admissions"] }
        val journal: List<String> get() = fake.posted.filter { it.channelId == channels["journal-du-bot"] }.map { it.message.text }

        /** La ligne de [key] dans `discord_admissions`, ou rien. */
        fun admission(key: String): Admission? = runBlocking { store.admission(key) }

        fun start() = runBlocking { feature.start() }

        fun advance(seconds: Long) = runBlocking { timer.advance(Duration.ofSeconds(seconds)) }

        /** Une inscription du site, avec le compte Discord relié ou non ; rend sa clé. */
        fun register(gamertag: String, discord: String? = null, at: Instant = registeredAt): String {
            val key = SiteRegistrations.key(gamertag)
            site.rows[key] = InMemorySiteRegistrationStore.Row(gamertag, discord = discord)
            store.registrations += Registration(key, gamertag, discord, at)
            return key
        }

        /** Un bouton pressé par [user] (le staff par défaut) sur la carte de [key] ; rend la réponse. */
        fun press(prefix: String, key: String, roles: Set<String> = setOf(staff), user: String = "42"): FakeReply {
            val reply = FakeReply()
            val messageId = store.admissions[key]?.messageId ?: "1"
            runBlocking {
                feature.handle(DiscordEvent.ButtonPressed(prefix + key, DiscordUser(user, "Kr", roleIds = roles), channels["admissions"], messageId, reply))
            }
            return reply
        }
    }

    private val card = "Inscription de Kr Test, le 5 novembre 2026 à 18 h 10.\nDiscord : "

    @Test
    @DisplayName("Une inscription donne une carte dans #admissions avec trois boutons, une seule, même après d'autres tics et un redémarrage")
    fun oneCardPerRegistration() {
        val setup = Setup()
        val key = setup.register("Kr Test")
        setup.start()
        setup.advance(59)
        assertTrue(setup.cards.isEmpty(), "pas avant le premier tic")
        setup.advance(1)

        val post = setup.cards.single()
        assertEquals(card + "pas encore relié.", post.message.text)
        assertTrue(post.message.card)
        assertEquals(
            listOf("admission-admettre:kr test", "admission-refuser:kr test", "admission-plus-tard:kr test"),
            post.message.buttons.map { it.id },
        )
        assertEquals(listOf("Admettre", "Refuser", "Plus tard"), post.message.buttons.map { it.label })
        assertTrue(post.message.mentionUserIds.isEmpty() && post.message.mentionRoleIds.isEmpty())
        assertEquals(Admission(key, post.messageId, "attente", null, null), setup.admission(key))
        assertEquals(setup.timer.now(), setup.store.cardedAt[key])

        setup.advance(180)
        assertEquals(1, setup.cards.size, "une carte déjà postée n'est jamais repostée")

        val restarted = AdmissionFeature(setup.context, rules, zone, setup.store, setup.site, setup.database, BotJournal(setup.context), hasGameProof = { true }, claimInvitation = { true })
        runBlocking { restarted.start() }
        setup.advance(120)
        assertEquals(1, setup.cards.size, "un redémarrage ne double rien")
        assertTrue(setup.journal.isEmpty(), "poster une carte n'écrit pas au journal")
    }

    @Test
    @DisplayName("Une inscription reliée le dit sur sa carte")
    fun linkedCard() {
        val setup = Setup()
        setup.register("Kr Test", discord = "555")
        setup.start()
        setup.advance(60)
        assertEquals(card + "relié.", setup.cards.single().message.text)
    }

    @Test
    @DisplayName("Au plus 20 cartes par tic, les plus anciennes d'abord ; le tic suivant poste le reste")
    fun batchesOfTwenty() {
        val setup = Setup()
        repeat(25) { setup.register("Joueur " + (it + 1), at = registeredAt + Duration.ofMinutes(it.toLong())) }
        setup.start()
        setup.advance(60)
        assertEquals(20, setup.cards.size)
        assertTrue(setup.cards.first().message.text.startsWith("Inscription de Joueur 1,"))
        assertTrue(setup.cards.last().message.text.startsWith("Inscription de Joueur 20,"))
        setup.advance(60)
        assertEquals(25, setup.cards.size)
        assertTrue(setup.cards.last().message.text.startsWith("Inscription de Joueur 25,"))
        assertEquals(25, setup.cards.map { it.message.text }.distinct().size)
    }

    @Test
    @DisplayName("Une inscription arrivée après le démarrage a sa carte au tic suivant")
    fun lateRegistration() {
        val setup = Setup()
        setup.start()
        setup.advance(60)
        assertTrue(setup.cards.isEmpty())
        setup.register("Kr Test")
        setup.advance(60)
        assertEquals(1, setup.cards.size)
    }

    @Test
    @DisplayName("Admettre un inscrit relié : admission posée, rôle Bêta, invitation en privé, carte sans bouton, ligne de journal")
    fun admitALinkedRegistration() {
        val setup = Setup()
        val key = setup.register("Kr Test", discord = "555")
        setup.start()
        setup.advance(60)
        val reply = setup.press(AdmissionTexts.ADMIT_PREFIX, key)

        assertTrue(setup.site.rows.getValue(key).admitted)
        assertEquals(1, setup.database.transactions)
        assertEquals(Admission(key, setup.cards.single().messageId, "admis", "42", setup.timer.now()), setup.admission(key))
        assertEquals(setOf(setup.beta), setup.fake.memberRoles["555"])
        val (to, invitation) = setup.fake.directs.single()
        assertEquals("555", to)
        assertEquals(
            "Tu es admis à la bêta de Silooo. Elle n'a pas encore ouvert : tu seras prévenu ici le jour venu, avec l'adresse et le port.",
            invitation.text,
        )
        val edited = reply.edits.single()
        assertEquals(card + "relié.\nAdmis par <@42> le 5 novembre 2026 à 11 h 01.", edited.text)
        assertTrue(edited.buttons.isEmpty())
        assertTrue(edited.card)
        assertTrue(edited.mentionUserIds.isEmpty(), "la mention du staff ne prévient personne")
        assertTrue(reply.sent.isEmpty())
        assertEquals(listOf("Admission : Kr Test admis par <@42>."), setup.journal)
    }

    @Test
    @DisplayName("Admettre un inscrit non relié : admission posée, ni rôle ni message privé, le journal dit qu'ils attendent la liaison")
    fun admitAnUnlinkedRegistration() {
        val setup = Setup()
        val key = setup.register("Kr Test")
        setup.start()
        setup.advance(60)
        val reply = setup.press(AdmissionTexts.ADMIT_PREFIX, key)

        assertTrue(setup.site.rows.getValue(key).admitted)
        assertEquals("admis", setup.admission(key)?.decision)
        assertTrue(setup.fake.memberRoles.isEmpty())
        assertTrue(setup.fake.directs.isEmpty())
        assertEquals(card + "pas encore relié.\nAdmis par <@42> le 5 novembre 2026 à 11 h 01.", reply.edits.single().text)
        assertEquals(
            listOf("Admission : Kr Test admis par <@42>. Sans liaison Discord : le rôle et l'invitation attendent la liaison."),
            setup.journal,
        )
    }

    @Test
    @DisplayName("Messages privés fermés : l'admission et le rôle tiennent, le journal dit que l'invitation n'est pas partie")
    fun closedDirectMessages() {
        val setup = Setup()
        val key = setup.register("Kr Test", discord = "555")
        setup.fake.closedDirects += "555"
        setup.start()
        setup.advance(60)
        setup.press(AdmissionTexts.ADMIT_PREFIX, key)

        assertEquals(setOf(setup.beta), setup.fake.memberRoles["555"])
        assertTrue(setup.fake.directs.isEmpty())
        assertEquals("admis", setup.admission(key)?.decision)
        assertEquals(
            listOf("Admission : Kr Test admis par <@42>. Message privé fermé : l'invitation n'est pas partie."),
            setup.journal,
        )
    }

    @Test
    @DisplayName("Sans rôle Bêta sur le serveur, ou si Discord refuse le rôle : l'admission tient, le journal le dit, aucune invitation")
    fun roleMissingOrRefused() {
        val missing = Setup(withBeta = false)
        val key = missing.register("Kr Test", discord = "555")
        missing.start()
        missing.advance(60)
        missing.press(AdmissionTexts.ADMIT_PREFIX, key)
        assertTrue(missing.site.rows.getValue(key).admitted)
        assertTrue(missing.fake.directs.isEmpty())
        assertEquals(
            listOf("Admission : Kr Test admis par <@42>. Le rôle Bêta manque sur le serveur : l'invitation n'est pas partie."),
            missing.journal,
        )

        // Le plan connaît un rôle Bêta que le serveur n'a plus : le faux Discord refuse de le donner.
        val refused = Setup()
        val brokenIds = ServerIds(mapOf("staff" to refused.staff, "beta" to "999"), refused.channels)
        val context = BotContext(refused.fake, brokenIds, refused.timer, ServerPhase.ATTENTE)
        val feature = AdmissionFeature(context, rules, zone, refused.store, refused.site, refused.database, BotJournal(context), hasGameProof = { true }, claimInvitation = { true })
        val other = refused.register("Autre", discord = "556")
        runBlocking { feature.start() }
        refused.advance(60)
        val reply = FakeReply()
        runBlocking {
            feature.handle(
                DiscordEvent.ButtonPressed(AdmissionTexts.ADMIT_PREFIX + other, DiscordUser("42", "Kr", roleIds = setOf(refused.staff)), null, "1", reply),
            )
        }
        assertEquals("admis", refused.admission(other)?.decision)
        assertEquals(1, reply.edits.size, "la carte change quand même")
        assertTrue(refused.fake.directs.isEmpty())
        assertEquals(
            listOf("Admission : Autre admis par <@42>. Discord a refusé le rôle ou le message privé : l'invitation n'est pas partie."),
            refused.journal,
        )
    }

    @Test
    @DisplayName("Refuser : décision notée, carte sans bouton, ni admission ni rôle ni message, ligne de journal")
    fun refuse() {
        val setup = Setup()
        val key = setup.register("Kr Test", discord = "555")
        setup.start()
        setup.advance(60)
        val reply = setup.press(AdmissionTexts.REFUSE_PREFIX, key)

        assertEquals(Admission(key, setup.cards.single().messageId, "refuse", "42", setup.timer.now()), setup.admission(key))
        assertFalse(setup.site.rows.getValue(key).admitted)
        assertEquals(0, setup.database.transactions)
        assertTrue(setup.fake.memberRoles.isEmpty() && setup.fake.directs.isEmpty())
        val edited = reply.edits.single()
        assertEquals(card + "relié.\nRefusé par <@42> le 5 novembre 2026 à 11 h 01.", edited.text)
        assertTrue(edited.buttons.isEmpty())
        assertEquals(listOf("Admission : Kr Test refusé par <@42>."), setup.journal)
    }

    @Test
    @DisplayName("Plus tard : la carte garde Admettre et Refuser et dit qui l'a remise ; on peut admettre ensuite")
    fun later() {
        val setup = Setup()
        val key = setup.register("Kr Test")
        setup.start()
        setup.advance(60)
        val reply = setup.press(AdmissionTexts.LATER_PREFIX, key)

        assertEquals("plus-tard", setup.admission(key)?.decision)
        assertFalse(setup.site.rows.getValue(key).admitted)
        val edited = reply.edits.single()
        assertEquals(card + "pas encore relié.\nRemis à plus tard par <@42> le 5 novembre 2026 à 11 h 01.", edited.text)
        assertEquals(listOf("admission-admettre:kr test", "admission-refuser:kr test"), edited.buttons.map { it.id })
        assertEquals(listOf("Admission : Kr Test remis à plus tard par <@42>."), setup.journal)

        setup.advance(3600)
        setup.press(AdmissionTexts.ADMIT_PREFIX, key, user = "43")
        assertEquals(Admission(key, setup.cards.single().messageId, "admis", "43", setup.timer.now()), setup.admission(key))
        assertTrue(setup.site.rows.getValue(key).admitted)
        assertEquals(2, setup.journal.size)
    }

    @Test
    @DisplayName("Un bouton pressé par quelqu'un qui n'est pas du staff est refusé, visible de lui seul, et ne change rien")
    fun notStaff() {
        val setup = Setup()
        val key = setup.register("Kr Test", discord = "555")
        setup.start()
        setup.advance(60)
        for (prefix in listOf(AdmissionTexts.ADMIT_PREFIX, AdmissionTexts.REFUSE_PREFIX, AdmissionTexts.LATER_PREFIX)) {
            val reply = setup.press(prefix, key, roles = emptySet(), user = "99")
            val (message, onlyToUser) = reply.sent.single()
            assertEquals("Ces boutons sont pour le staff.", message.text)
            assertTrue(onlyToUser)
            assertTrue(reply.edits.isEmpty())
        }
        assertEquals("attente", setup.admission(key)?.decision)
        assertFalse(setup.site.rows.getValue(key).admitted)
        assertTrue(setup.fake.memberRoles.isEmpty() && setup.fake.directs.isEmpty() && setup.journal.isEmpty())
    }

    @Test
    @DisplayName("Une clé inconnue, ou une inscription sans carte : « Cette inscription n'est plus là. », rien n'est écrit")
    fun unknownKey() {
        val setup = Setup()
        setup.register("Sans Carte")
        setup.start()
        for (key in listOf("inconnu", "sans carte")) {
            val reply = setup.press(AdmissionTexts.ADMIT_PREFIX, key)
            val (message, onlyToUser) = reply.sent.single()
            assertEquals("Cette inscription n'est plus là.", message.text)
            assertTrue(onlyToUser)
            assertTrue(reply.edits.isEmpty())
        }
        assertTrue(setup.store.admissions.isEmpty())
        assertFalse(setup.site.rows.getValue("sans carte").admitted)
        assertEquals(0, setup.database.transactions)
        assertTrue(setup.journal.isEmpty())
    }

    @Test
    @DisplayName("Une inscription déjà admise ou refusée ne se traite pas deux fois : pas de second message privé")
    fun alreadyDecided() {
        val setup = Setup()
        val key = setup.register("Kr Test", discord = "555")
        setup.start()
        setup.advance(60)
        setup.press(AdmissionTexts.ADMIT_PREFIX, key)
        val again = setup.press(AdmissionTexts.ADMIT_PREFIX, key, user = "43")
        assertEquals("Cette inscription est déjà traitée.", again.sent.single().first.text)
        assertTrue(again.edits.isEmpty())
        val refuse = setup.press(AdmissionTexts.REFUSE_PREFIX, key, user = "43")
        assertEquals("Cette inscription est déjà traitée.", refuse.sent.single().first.text)
        assertEquals(1, setup.fake.directs.size)
        assertEquals("admis", setup.admission(key)?.decision)
        assertEquals("42", setup.admission(key)?.decidedBy)
        assertEquals(1, setup.journal.size)
    }

    @Test
    @DisplayName("Un autre bouton du bot n'est pas le sien : rien ne se passe")
    fun otherButtonsAreIgnored() {
        val setup = Setup()
        setup.register("Kr Test")
        val reply = FakeReply()
        runBlocking {
            setup.feature.handle(DiscordEvent.ButtonPressed("mon-tuto", DiscordUser("42", "Kr"), null, "1", reply))
        }
        assertTrue(reply.sent.isEmpty() && reply.edits.isEmpty() && reply.forms.isEmpty() && reply.acknowledged == 0)
    }

    @Test
    @DisplayName("Sans le salon des admissions sur le serveur : aucune minuterie, aucune carte, aucune ligne")
    fun withoutTheChannel() {
        val setup = Setup(withChannel = false)
        setup.register("Kr Test")
        setup.start()
        assertEquals(0, setup.timer.pending)
        setup.advance(600)
        assertTrue(setup.fake.posted.isEmpty())
        assertTrue(setup.store.admissions.isEmpty())
    }

    @Test
    @DisplayName("Sans salon du journal : le geste du staff passe, la ligne n'est écrite nulle part")
    fun withoutTheJournal() {
        val setup = Setup(withJournal = false)
        val key = setup.register("Kr Test", discord = "555")
        setup.start()
        setup.advance(60)
        setup.press(AdmissionTexts.ADMIT_PREFIX, key)
        assertEquals("admis", setup.admission(key)?.decision)
        assertEquals(1, setup.fake.posted.size, "la seule écriture du bot est la carte")
        assertNull(setup.channels["journal-du-bot"])
    }

    @Test
    @DisplayName("La date s'écrit à la française dans le fuseau : minutes sur deux chiffres, « 1er » le premier du mois, heure d'été comprise")
    fun momentsAreWrittenInFrench() {
        assertEquals("le 5 novembre 2026 à 18 h 10", AdmissionTexts.moment(Instant.parse("2026-11-05T17:10:00Z"), zone))
        assertEquals("le 1er décembre 2026 à 9 h 05", AdmissionTexts.moment(Instant.parse("2026-12-01T08:05:00Z"), zone))
        assertEquals("le 14 juillet 2027 à 0 h 00", AdmissionTexts.moment(Instant.parse("2027-07-13T22:00:00Z"), zone))
        assertEquals("le 31 mars 2027 à 23 h 59", AdmissionTexts.moment(Instant.parse("2027-03-31T21:59:00Z"), zone))
    }
}
