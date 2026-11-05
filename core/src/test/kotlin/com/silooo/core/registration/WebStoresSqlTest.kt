package com.silooo.core.registration

import com.silooo.core.db.MariaDbSupport
import com.silooo.core.site.Empreinte
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Les tables de V11 (comptes, liste d'attente, codes de liaison, bêta, sessions) sur MariaDB 12.3.3. Ignoré sans Docker, joué dans la CI. */
class WebStoresSqlTest {
    private val accounts = JdbcWebAccountStore()
    private val waitlist = JdbcWaitlistStore()
    private val links = JdbcDiscordLinkStore()
    private val beta = JdbcBetaAccessStore()
    private val sessions = JdbcWebSessionStore()

    /** Une seconde entière : DATETIME(3) la garde telle quelle, les comparaisons d'égalité restent exactes. */
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val maxEmpreinte = "18446744073709551615"

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    private fun <T> inTransaction(block: (Connection) -> T): T = runBlocking { MariaDbSupport.database.transaction(block) }

    private fun xbox(xuid: String = "2535400000000001", gamertag: String = "Kr Test") =
        inTransaction { accounts.createXbox(it, xuid, gamertag, Empreinte.duGamertag(gamertag), now) }

    private fun discord(id: String = "413000000000000001") = inTransaction { accounts.createDiscord(it, id, now) }

    private fun session(hash: String, accountId: Long, expiresAt: Instant = now.plus(WebSessions.LIFETIME)) =
        WebSession(hash, accountId, "csrf-$hash", now, expiresAt)

    // --- web_accounts

    @Test
    @DisplayName("Compte Xbox : gamertag, empreinte et date rendus tels quels ; le XUID Xbox sert à le retrouver sans jamais sortir")
    fun createXbox() {
        val created = xbox()
        assertEquals(WebAccount(created.id, Empreinte.duGamertag("Kr Test"), "Kr Test", xbox = true, discord = false, createdAt = now), created)
        assertEquals(created, inTransaction { accounts.find(it, created.id) })
        assertEquals(created, inTransaction { accounts.findByXbox(it, "2535400000000001") })
        assertEquals(created, inTransaction { accounts.findByPlayer(it, Empreinte.duGamertag("Kr Test")) })
        assertNull(inTransaction { accounts.findByDiscord(it, "413000000000000001") })
        assertNull(inTransaction { accounts.findByXbox(it, "2535400000000002") })
        assertNull(inTransaction { accounts.find(it, created.id + 1) })
    }

    @Test
    @DisplayName("Compte Discord : seul l'identifiant est connu ; un second compte du même Discord est refusé par la base")
    fun createDiscord() {
        val created = discord()
        assertEquals(WebAccount(created.id, null, null, xbox = false, discord = true, createdAt = now), created)
        assertEquals(created, inTransaction { accounts.findByDiscord(it, "413000000000000001") })
        assertEquals("413000000000000001", inTransaction { accounts.discordUserId(it, created.id) })
        assertThrows<SQLException> { discord() }
        assertEquals(1L, MariaDbSupport.count("web_accounts"))
    }

    @Test
    @DisplayName("Le XUID Xbox et l'empreinte sont uniques : un second compte Xbox qui les reprend est refusé")
    fun xboxAndPlayerAreUnique() {
        xbox()
        assertThrows<SQLException> { xbox() }
        assertThrows<SQLException> { xbox(xuid = "2535400000000002") }
        assertThrows<SQLException> { xbox(gamertag = "autre nom") }
        assertEquals(1L, MariaDbSupport.count("web_accounts"))
    }

    @Test
    @DisplayName("Une empreinte qui n'est pas un XUID ne trouve rien (et ne lève pas)")
    fun findByPlayerRefusesWhatIsNotAnEmpreinte() {
        xbox()
        assertNull(inTransaction { accounts.findByPlayer(it, "abc") })
        assertNull(inTransaction { accounts.findByPlayer(it, "") })
        assertNull(inTransaction { accounts.findByPlayer(it, "18446744073709551616") })
    }

    @Test
    @DisplayName("setPlayer : pose, remplace, refuse l'empreinte d'un autre compte, ne connaît pas un compte absent")
    fun setPlayer() {
        val account = discord()
        val other = discord("413000000000000002")
        val empreinte = Empreinte.duGamertag("Kr Test")
        assertEquals(PlayerOutcome.SET, inTransaction { accounts.setPlayer(it, account.id, "Kr Test", empreinte) })
        assertEquals(account.copy(playerXuid = empreinte, gamertag = "Kr Test"), inTransaction { accounts.find(it, account.id) })
        // Rejouer à l'identique reste un succès (le pilote peut compter zéro ligne changée).
        assertEquals(PlayerOutcome.SET, inTransaction { accounts.setPlayer(it, account.id, "Kr Test", empreinte) })
        // L'empreinte est prise par le premier compte.
        assertEquals(PlayerOutcome.PLAYER_TAKEN, inTransaction { accounts.setPlayer(it, other.id, "Kr Test", empreinte) })
        assertNull(inTransaction { accounts.find(it, other.id) }?.playerXuid)
        // Un autre gamertag remplace le premier.
        val second = Empreinte.duGamertag("Autre Nom")
        assertEquals(PlayerOutcome.SET, inTransaction { accounts.setPlayer(it, account.id, "Autre Nom", second) })
        assertEquals(second, inTransaction { accounts.find(it, account.id) }?.playerXuid)
        assertNull(inTransaction { accounts.findByPlayer(it, empreinte) })
        assertEquals(PlayerOutcome.NOT_FOUND, inTransaction { accounts.setPlayer(it, account.id + 100, "Fantôme", Empreinte.duGamertag("Fantôme")) })
    }

    @Test
    @DisplayName("Une empreinte de 64 bits non signée fait l'aller-retour sans perdre son signe")
    fun unsignedEmpreinte() {
        val account = discord()
        assertEquals(PlayerOutcome.SET, inTransaction { accounts.setPlayer(it, account.id, "Grand", maxEmpreinte) })
        assertEquals(maxEmpreinte, MariaDbSupport.string("SELECT player_xuid FROM web_accounts"))
        assertEquals(maxEmpreinte, inTransaction { accounts.find(it, account.id) }?.playerXuid)
        assertEquals(account.id, inTransaction { accounts.findByPlayer(it, maxEmpreinte) }?.id)
    }

    @Test
    @DisplayName("clearPlayer libère le gamertag et l'empreinte ; faux quand il n'y a rien à retirer")
    fun clearPlayer() {
        val account = xbox()
        val other = discord()
        assertTrue(inTransaction { accounts.clearPlayer(it, account.id) })
        assertFalse(inTransaction { accounts.clearPlayer(it, account.id) }, "déjà libre")
        assertFalse(inTransaction { accounts.clearPlayer(it, other.id) })
        assertFalse(inTransaction { accounts.clearPlayer(it, account.id + 100) })
        val cleared = checkNotNull(inTransaction { accounts.find(it, account.id) })
        assertNull(cleared.playerXuid)
        assertNull(cleared.gamertag)
        assertTrue(cleared.xbox, "le compte reste entré par Xbox")
        // L'empreinte libérée peut être reprise par un autre compte.
        assertEquals(PlayerOutcome.SET, inTransaction { accounts.setPlayer(it, other.id, "Kr Test", Empreinte.duGamertag("Kr Test")) })
    }

    @Test
    @DisplayName("setDiscord : relie, refuse un second Discord sur le compte ou un Discord déjà pris, ne connaît pas un compte absent")
    fun setDiscord() {
        val account = xbox()
        val holder = discord()
        assertEquals(LinkOutcome.LINKED, inTransaction { accounts.setDiscord(it, account.id, "413000000000000009") })
        assertTrue(checkNotNull(inTransaction { accounts.find(it, account.id) }).discord)
        assertEquals("413000000000000009", inTransaction { accounts.discordUserId(it, account.id) })
        assertEquals(account.id, inTransaction { accounts.findByDiscord(it, "413000000000000009") }?.id)
        // Le compte a déjà un Discord : rien ne change, même avec le même identifiant.
        assertEquals(LinkOutcome.ALREADY_LINKED, inTransaction { accounts.setDiscord(it, account.id, "413000000000000010") })
        assertEquals(LinkOutcome.ALREADY_LINKED, inTransaction { accounts.setDiscord(it, account.id, "413000000000000009") })
        assertEquals("413000000000000009", inTransaction { accounts.discordUserId(it, account.id) })
        // Un Discord porté par un autre compte.
        val third = xbox(xuid = "2535400000000003", gamertag = "Troisieme")
        assertEquals(LinkOutcome.DISCORD_TAKEN, inTransaction { accounts.setDiscord(it, third.id, "413000000000000001") })
        assertFalse(checkNotNull(inTransaction { accounts.find(it, third.id) }).discord)
        assertEquals(LinkOutcome.NOT_REGISTERED, inTransaction { accounts.setDiscord(it, holder.id + 100, "413000000000000011") })
    }

    @Test
    @DisplayName("clearDiscord retire le lien (le compte garde le reste) ; faux quand il n'y en a pas")
    fun clearDiscord() {
        val account = xbox()
        assertFalse(inTransaction { accounts.clearDiscord(it, account.id) })
        assertEquals(LinkOutcome.LINKED, inTransaction { accounts.setDiscord(it, account.id, "413000000000000009") })
        assertTrue(inTransaction { accounts.clearDiscord(it, account.id) })
        assertFalse(inTransaction { accounts.clearDiscord(it, account.id) })
        assertNull(inTransaction { accounts.discordUserId(it, account.id) })
        assertNull(inTransaction { accounts.findByDiscord(it, "413000000000000009") })
        assertEquals(account, inTransaction { accounts.find(it, account.id) })
        assertNull(inTransaction { accounts.discordUserId(it, account.id + 100) })
    }

    @Test
    @DisplayName("attachXbox : pose le XUID sur un compte Discord, refuse un autre XUID, accepte le même, refuse celui d'un autre compte")
    fun attachXbox() {
        val account = discord()
        val xboxHolder = xbox()
        assertTrue(inTransaction { accounts.attachXbox(it, account.id, "2535400000000002") })
        val attached = checkNotNull(inTransaction { accounts.find(it, account.id) })
        assertTrue(attached.xbox)
        assertTrue(attached.discord)
        assertEquals(account.id, inTransaction { accounts.findByXbox(it, "2535400000000002") }?.id)
        // Rejouer la liaison avec le même XUID ne change rien et reste un succès.
        assertTrue(inTransaction { accounts.attachXbox(it, account.id, "2535400000000002") })
        // Un autre XUID sur un compte qui en a déjà un.
        assertFalse(inTransaction { accounts.attachXbox(it, account.id, "2535400000000003") })
        assertEquals(account.id, inTransaction { accounts.findByXbox(it, "2535400000000002") }?.id)
        // Un XUID déjà porté par un autre compte.
        val bare = discord("413000000000000002")
        assertFalse(inTransaction { accounts.attachXbox(it, bare.id, "2535400000000001") })
        assertEquals(xboxHolder.id, inTransaction { accounts.findByXbox(it, "2535400000000001") }?.id)
        assertFalse(checkNotNull(inTransaction { accounts.find(it, bare.id) }).xbox)
        // Un compte qui n'existe pas.
        assertFalse(inTransaction { accounts.attachXbox(it, bare.id + 100, "2535400000000004") })
    }

    @Test
    @DisplayName("touch note la dernière connexion, à la milliseconde, et ne change rien d'autre")
    fun touch() {
        val account = xbox()
        assertNull(MariaDbSupport.string("SELECT last_login_at FROM web_accounts"))
        val login = now.plusSeconds(3600).plusMillis(250)
        inTransaction { accounts.touch(it, account.id, login) }
        assertEquals("2026-11-05 13:00:00.250", MariaDbSupport.string("SELECT CAST(last_login_at AS CHAR) FROM web_accounts"))
        assertEquals(account, inTransaction { accounts.find(it, account.id) })
    }

    // --- waitlist

    @Test
    @DisplayName("Liste d'attente : inscrit une fois, la seconde rend faux sans rien changer ; le Pionnier donné se relit")
    fun waitlistInsertAndFind() {
        val first = xbox()
        val second = discord()
        assertNull(inTransaction { waitlist.find(it, first.id) })
        assertEquals(0L, inTransaction { waitlist.count(it) })
        assertTrue(inTransaction { waitlist.insert(it, first.id, now) })
        assertFalse(inTransaction { waitlist.insert(it, first.id, now.plusSeconds(60)) })
        assertEquals(WaitlistEntry(first.id, now, null), inTransaction { waitlist.find(it, first.id) })
        assertTrue(inTransaction { waitlist.insert(it, second.id, now.plusSeconds(5)) })
        assertEquals(2L, inTransaction { waitlist.count(it) })
        // Le jeu donne le Pionnier : le site relit la date.
        MariaDbSupport.update("UPDATE waitlist SET pioneer_given_at = ? WHERE account_id = ?", LocalDateTime.of(2026, 11, 6, 8, 30, 15), first.id)
        assertEquals(
            WaitlistEntry(first.id, now, Instant.parse("2026-11-06T08:30:15Z")),
            inTransaction { waitlist.find(it, first.id) },
        )
    }

    @Test
    @DisplayName("Liste d'attente : un compte qui n'existe pas n'est pas inscrit")
    fun waitlistNeedsAnAccount() {
        assertFalse(inTransaction { waitlist.insert(it, 4242, now) })
        assertEquals(0L, MariaDbSupport.count("waitlist"))
    }

    // --- discord_links

    @Test
    @DisplayName("Code de liaison : posé pour le compte, relu par le code et par le compte, dates à la milliseconde")
    fun linkReplaceAndFind() {
        val account = discord()
        val expires = now.plus(LinkCodes.LIFETIME)
        inTransaction { links.replace(it, account.id, "K7M4PX", now, expires) }
        val expected = LinkCode("K7M4PX", account.id, now, expires)
        assertEquals(expected, inTransaction { links.findByCode(it, "K7M4PX") })
        assertEquals(expected, inTransaction { links.findByAccount(it, account.id) })
        assertNull(inTransaction { links.findByCode(it, "AAAAAA") })
        assertNull(inTransaction { links.findByAccount(it, account.id + 100) })
        assertFalse(expected.expired(now.plus(Duration.ofMinutes(9))))
        assertTrue(expected.expired(expires))
    }

    @Test
    @DisplayName("Un seul code vivant par compte : le nouveau prend la place de l'ancien, les autres comptes gardent le leur")
    fun linkReplaceKeepsOnePerAccount() {
        val account = discord()
        val other = discord("413000000000000002")
        inTransaction { links.replace(it, account.id, "AAAAAA", now, now.plus(LinkCodes.LIFETIME)) }
        inTransaction { links.replace(it, other.id, "BBBBBB", now, now.plus(LinkCodes.LIFETIME)) }
        val later = now.plusSeconds(120)
        inTransaction { links.replace(it, account.id, "CCCCCC", later, later.plus(LinkCodes.LIFETIME)) }
        assertNull(inTransaction { links.findByCode(it, "AAAAAA") })
        assertEquals("CCCCCC", inTransaction { links.findByAccount(it, account.id) }?.code)
        assertEquals("BBBBBB", inTransaction { links.findByAccount(it, other.id) }?.code)
        assertEquals(2L, MariaDbSupport.count("discord_links"))
    }

    @Test
    @DisplayName("Un code déjà porté par un autre compte fait échouer la transaction entière : l'ancien code du compte reste")
    fun linkCodeCollisionRollsBack() {
        val account = discord()
        val other = discord("413000000000000002")
        inTransaction { links.replace(it, account.id, "AAAAAA", now, now.plus(LinkCodes.LIFETIME)) }
        inTransaction { links.replace(it, other.id, "BBBBBB", now, now.plus(LinkCodes.LIFETIME)) }
        assertThrows<SQLException> { inTransaction { links.replace(it, account.id, "BBBBBB", now, now.plus(LinkCodes.LIFETIME)) } }
        // Le DELETE de l'ancien code est annulé avec l'INSERT qui a échoué.
        assertEquals("AAAAAA", inTransaction { links.findByAccount(it, account.id) }?.code)
        assertEquals(other.id, inTransaction { links.findByCode(it, "BBBBBB") }?.accountId)
    }

    @Test
    @DisplayName("delete efface un code et rend vrai, faux s'il n'existe pas ; purge efface les expirés, au bord inclus")
    fun linkDeleteAndPurge() {
        val first = discord()
        val second = discord("413000000000000002")
        val third = discord("413000000000000003")
        inTransaction { links.replace(it, first.id, "AAAAAA", now, now.plusSeconds(60)) }
        inTransaction { links.replace(it, second.id, "BBBBBB", now, now.plusSeconds(61)) }
        inTransaction { links.replace(it, third.id, "CCCCCC", now, now.plusSeconds(600)) }
        assertTrue(inTransaction { links.delete(it, "CCCCCC") })
        assertFalse(inTransaction { links.delete(it, "CCCCCC") })
        // À expires_at pile, le code est expiré (LinkCode.expired) : la purge l'efface, mais pas celui qui vaut encore une seconde.
        assertEquals(0, inTransaction { links.purge(it, now.plusSeconds(59)) })
        assertEquals(1, inTransaction { links.purge(it, now.plusSeconds(60)) })
        assertNull(inTransaction { links.findByCode(it, "AAAAAA") })
        assertNotNull(inTransaction { links.findByCode(it, "BBBBBB") })
        assertEquals(1, inTransaction { links.purge(it, now.plusSeconds(3600)) })
        assertEquals(0L, MariaDbSupport.count("discord_links"))
    }

    // --- beta_access

    @Test
    @DisplayName("Bêta : admet une empreinte une fois, par qui et quand ; la seconde admission rend faux sans rien changer")
    fun betaAdmit() {
        val empreinte = Empreinte.duGamertag("Kr Test")
        assertNull(inTransaction { beta.find(it, empreinte) })
        assertTrue(inTransaction { beta.admit(it, empreinte, "tous", now) })
        assertFalse(inTransaction { beta.admit(it, empreinte, "staff:Kr", now.plusSeconds(60)) })
        assertEquals(BetaAccess(empreinte, "tous", now), inTransaction { beta.find(it, empreinte) })
        assertEquals(1L, inTransaction { beta.count(it) })
        assertTrue(inTransaction { beta.admit(it, Empreinte.duGamertag("Autre Nom"), "staff:Kr", now.plusSeconds(5)) })
        assertEquals(2L, inTransaction { beta.count(it) })
    }

    @Test
    @DisplayName("Bêta : une empreinte de 64 bits non signée fait l'aller-retour ; ce qui n'est pas une empreinte n'est pas admis")
    fun betaUnsignedAndInvalid() {
        assertTrue(inTransaction { beta.admit(it, maxEmpreinte, "tous", now) })
        assertEquals(maxEmpreinte, MariaDbSupport.string("SELECT player_xuid FROM beta_access"))
        assertEquals(BetaAccess(maxEmpreinte, "tous", now), inTransaction { beta.find(it, maxEmpreinte) })
        assertNull(inTransaction { beta.find(it, "abc") })
        assertNull(inTransaction { beta.find(it, "18446744073709551616") })
    }

    // --- web_sessions

    @Test
    @DisplayName("Session : relue par l'empreinte du jeton tant qu'elle vaut ; expirée à expires_at pile")
    fun sessionCreateAndFind() {
        val account = xbox()
        val hash = "a".repeat(64)
        val created = session(hash, account.id)
        inTransaction { sessions.create(it, created) }
        assertEquals(created, inTransaction { sessions.find(it, hash, now) })
        assertEquals(created, inTransaction { sessions.find(it, hash, created.expiresAt.minusMillis(1)) })
        assertNull(inTransaction { sessions.find(it, hash, created.expiresAt) }, "à expires_at pile, la session est finie")
        assertNull(inTransaction { sessions.find(it, hash, created.expiresAt.plusSeconds(1)) })
        assertNull(inTransaction { sessions.find(it, "b".repeat(64), now) })
        // La base ne garde que l'empreinte du jeton, jamais le jeton.
        assertEquals(listOf(hash), MariaDbSupport.strings("SELECT token_hash FROM web_sessions"))
    }

    @Test
    @DisplayName("Deux sessions ne partagent jamais la même empreinte de jeton")
    fun sessionHashIsUnique() {
        val account = xbox()
        val hash = "c".repeat(64)
        inTransaction { sessions.create(it, session(hash, account.id)) }
        assertThrows<SQLException> { inTransaction { sessions.create(it, session(hash, account.id)) } }
        assertEquals(1L, MariaDbSupport.count("web_sessions"))
    }

    @Test
    @DisplayName("Fermer une session : vrai une fois, faux ensuite ; fermer toutes celles d'un compte rend combien, sans toucher aux autres")
    fun sessionDelete() {
        val account = xbox()
        val other = discord()
        inTransaction { sessions.create(it, session("1".repeat(64), account.id)) }
        inTransaction { sessions.create(it, session("2".repeat(64), account.id)) }
        inTransaction { sessions.create(it, session("3".repeat(64), other.id)) }
        assertTrue(inTransaction { sessions.delete(it, "1".repeat(64)) })
        assertFalse(inTransaction { sessions.delete(it, "1".repeat(64)) })
        assertEquals(1, inTransaction { sessions.deleteAll(it, account.id) })
        assertEquals(0, inTransaction { sessions.deleteAll(it, account.id) })
        assertNotNull(inTransaction { sessions.find(it, "3".repeat(64), now) })
        assertEquals(1L, MariaDbSupport.count("web_sessions"))
    }

    @Test
    @DisplayName("purge efface les sessions finies à l'instant donné, expires_at pile compris, et seulement elles")
    fun sessionPurge() {
        val account = xbox()
        inTransaction { sessions.create(it, session("1".repeat(64), account.id, expiresAt = now.plusSeconds(100))) }
        inTransaction { sessions.create(it, session("2".repeat(64), account.id, expiresAt = now.plusSeconds(200))) }
        assertEquals(0, inTransaction { sessions.purge(it, now.plusSeconds(99)) })
        assertEquals(1, inTransaction { sessions.purge(it, now.plusSeconds(100)) })
        assertNull(inTransaction { sessions.find(it, "1".repeat(64), now) })
        assertNotNull(inTransaction { sessions.find(it, "2".repeat(64), now) })
        assertEquals(1, inTransaction { sessions.purge(it, now.plusSeconds(1000)) })
        assertEquals(0L, MariaDbSupport.count("web_sessions"))
    }

    // --- clés étrangères

    @Test
    @DisplayName("Supprimer un compte emporte son inscription, son code de liaison et ses sessions, pas ceux des autres")
    fun deletingAnAccountCascades() {
        val account = xbox()
        val other = discord()
        inTransaction { waitlist.insert(it, account.id, now) }
        inTransaction { waitlist.insert(it, other.id, now) }
        inTransaction { links.replace(it, account.id, "AAAAAA", now, now.plus(LinkCodes.LIFETIME)) }
        inTransaction { links.replace(it, other.id, "BBBBBB", now, now.plus(LinkCodes.LIFETIME)) }
        inTransaction { sessions.create(it, session("1".repeat(64), account.id)) }
        inTransaction { sessions.create(it, session("2".repeat(64), other.id)) }
        assertEquals(1, MariaDbSupport.update("DELETE FROM web_accounts WHERE id = ?", account.id))
        assertEquals(listOf(other.id), MariaDbSupport.longs("SELECT account_id FROM waitlist"))
        assertEquals(listOf(other.id), MariaDbSupport.longs("SELECT account_id FROM discord_links"))
        assertEquals(listOf(other.id), MariaDbSupport.longs("SELECT account_id FROM web_sessions"))
    }

    @Test
    @DisplayName("Une session ou un code de liaison ne s'attache pas à un compte qui n'existe pas")
    fun foreignKeysRefuseAMissingAccount() {
        assertThrows<SQLException> { inTransaction { sessions.create(it, session("1".repeat(64), 4242)) } }
        assertThrows<SQLException> { inTransaction { links.replace(it, 4242, "AAAAAA", now, now.plus(LinkCodes.LIFETIME)) } }
        assertEquals(0L, MariaDbSupport.count("web_sessions"))
        assertEquals(0L, MariaDbSupport.count("discord_links"))
    }
}
