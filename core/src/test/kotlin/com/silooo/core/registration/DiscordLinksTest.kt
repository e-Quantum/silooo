package com.silooo.core.registration

import com.silooo.core.MutableClock
import com.silooo.core.site.Empreinte
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** « Relier Discord » sur les faux dépôts : le code émis, réutilisé, périmé, puis tapé en jeu dans chaque issue. */
class DiscordLinksTest {
    private val start = Instant.parse("2026-11-05T12:00:00Z")
    private val clock = MutableClock(start)
    private val accounts = InMemoryWebAccountStore()
    private val links = InMemoryDiscordLinkStore()
    private val registrations = InMemorySiteRegistrationStore()
    private val discordId = "413000000000000001"

    /** Les codes que le tirage rendra, dans l'ordre ; vide : le vrai tirage. */
    private val scripted = ArrayDeque<String>()
    private val service = DiscordLinks(accounts, links, registrations, clock) { scripted.removeFirstOrNull() ?: LinkCodes.generate() }

    /** Les faux n'ouvrent jamais la connexion : si un code s'en sert, le test échoue au lieu de passer à vide. */
    private val connection: Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
        InvocationHandler { _, method, _ -> throw UnsupportedOperationException("la connexion n'est pas utilisable (${method.name})") },
    ) as Connection

    private fun discordAccount(id: String = discordId) = accounts.createDiscord(connection, id, start)

    private fun issue(accountId: Long) = service.issue(connection, accountId)

    private fun redeem(typed: String, gamertag: String = "Kr Test", admit: Boolean = true, pioneer: Boolean = true) =
        service.redeem(connection, typed, gamertag, admit, pioneer)

    @Test
    @DisplayName("Émettre : un code de l'alphabet, valable dix minutes, rangé pour le compte")
    fun issuesACode() {
        val account = discordAccount()
        val code = issue(account.id)
        assertEquals(account.id, code.accountId)
        assertEquals(code.code, LinkCodes.normalize(code.code))
        assertEquals(start, code.createdAt)
        assertEquals(start.plus(LinkCodes.LIFETIME), code.expiresAt)
        assertEquals(code, links.findByAccount(connection, account.id))
        assertEquals(code, links.findByCode(connection, code.code))
    }

    @Test
    @DisplayName("Émettre deux fois rend le code vivant, sans en tirer un autre ; chaque compte a le sien")
    fun reusesTheLiveCode() {
        val first = discordAccount()
        val second = discordAccount("413000000000000002")
        scripted += listOf("AAAAAA", "BBBBBB")
        val code = issue(first.id)
        clock.advance(Duration.ofMinutes(9).plusSeconds(59))
        assertEquals(code, issue(first.id), "à dix minutes moins une seconde, le même code")
        assertEquals(1, links.byCode.size)
        assertEquals("BBBBBB", issue(second.id).code)
        assertEquals(setOf("AAAAAA", "BBBBBB"), links.byCode.keys)
    }

    @Test
    @DisplayName("Un code expiré est remplacé par un autre, l'ancien disparaît ; le compte n'a jamais qu'un code")
    fun drawsAnotherAfterExpiry() {
        val account = discordAccount()
        scripted += listOf("AAAAAA", "BBBBBB")
        val old = issue(account.id)
        clock.advance(LinkCodes.LIFETIME)
        val fresh = issue(account.id)
        assertEquals("AAAAAA", old.code)
        assertEquals("BBBBBB", fresh.code)
        assertEquals(clock.instant(), fresh.createdAt)
        assertEquals(clock.instant().plus(LinkCodes.LIFETIME), fresh.expiresAt)
        assertNull(links.findByCode(connection, "AAAAAA"), "l'ancien code est parti")
        assertEquals(listOf("BBBBBB"), links.byCode.keys.toList())
    }

    @Test
    @DisplayName("Un tirage qui tombe sur un code déjà en base, vivant ou périmé, est écarté")
    fun skipsACodeAlreadyInTheBase() {
        val other = discordAccount("413000000000000002")
        val account = discordAccount()
        scripted += listOf("AAAAAA", "AAAAAA", "BBBBBB")
        assertEquals("AAAAAA", issue(other.id).code)
        assertEquals("BBBBBB", issue(account.id).code, "AAAAAA est pris : le tirage suivant")
        // Même un code périmé que la purge n'a pas encore retiré n'est pas repris.
        clock.advance(LinkCodes.LIFETIME)
        val third = discordAccount("413000000000000003")
        scripted += listOf("BBBBBB", "CCCCCC")
        assertEquals("CCCCCC", issue(third.id).code)
    }

    @Test
    @DisplayName("Aucun code libre après vingt tirages : une erreur, rien d'écrit")
    fun givesUpWhenEveryDrawIsTaken() {
        val other = discordAccount("413000000000000002")
        val account = discordAccount()
        scripted += "AAAAAA"
        issue(other.id)
        repeat(20) { scripted += "AAAAAA" }
        assertThrows<IllegalStateException> { issue(account.id) }
        assertNull(links.findByAccount(connection, account.id))
    }

    @Test
    @DisplayName("Taper le bon code : le gamertag est posé, l'inscription écrite et reliée au Discord du compte, le code effacé")
    fun redeemLinks() {
        val account = discordAccount()
        val code = issue(account.id)
        assertEquals(RedeemOutcome.Linked(account.id), redeem(code.code))
        val linked = checkNotNull(accounts.find(connection, account.id))
        assertEquals("Kr Test", linked.gamertag)
        assertEquals(Empreinte.duGamertag("Kr Test"), linked.playerXuid)
        assertTrue(linked.discord)
        // site_registrations : la ligne que le jeu et le bot lisent, avec l'identifiant Discord du compte.
        val row = checkNotNull(registrations.rows["kr test"])
        assertEquals("Kr Test", row.gamertag)
        assertEquals(discordId, row.discord)
        assertTrue(row.admitted)
        assertTrue(row.pioneer)
        assertEquals(SiteRegistration("kr test", "Kr Test", linked = true, admitted = true), registrations.find(connection, "kr test"))
        assertNull(links.findByCode(connection, code.code), "le code est consommé")
        assertEquals(RedeemOutcome.UnknownCode, redeem(code.code), "un code ne sert qu'une fois")
    }

    @Test
    @DisplayName("Le code est reconnu tapé en minuscules, avec des espaces ou un tiret")
    fun redeemNormalizesWhatWasTyped() {
        for ((index, typed) in listOf("k7m4px", " K7M 4PX ", "k7m-4px").withIndex()) {
            val account = discordAccount("41300000000000010$index")
            scripted += "K7M4PX"
            issue(account.id)
            assertEquals(RedeemOutcome.Linked(account.id), redeem(typed, gamertag = "Joueur $index"), typed)
            assertEquals("Joueur $index", accounts.find(connection, account.id)?.gamertag)
        }
    }

    @Test
    @DisplayName("La règle d'admission et le moment passent tels quels : sans admission, admitted_at reste nul ; hors pionniers, pioneer est faux")
    fun redeemPassesAdmitAndPioneer() {
        val account = discordAccount()
        val code = issue(account.id)
        assertEquals(RedeemOutcome.Linked(account.id), redeem(code.code, admit = false, pioneer = false))
        val row = checkNotNull(registrations.rows["kr test"])
        assertFalse(row.admitted)
        assertFalse(row.pioneer)
        assertEquals(discordId, row.discord)
    }

    @Test
    @DisplayName("Ce qui n'est pas un code, ou qu'aucun code ne porte : code inconnu, rien d'écrit")
    fun unknownCode() {
        val account = discordAccount()
        issue(account.id)
        for (typed in listOf("", "abc", "AAAAAAA", "O0I1O0", "ZZZZZZ")) {
            assertEquals(RedeemOutcome.UnknownCode, redeem(typed), "« $typed »")
        }
        assertTrue(registrations.rows.isEmpty())
        assertNull(accounts.find(connection, account.id)?.gamertag)
        assertEquals(1, links.byCode.size, "le vrai code est intact")
    }

    @Test
    @DisplayName("Un code de dix minutes est expiré : rien n'est écrit et le code reste jusqu'à la purge")
    fun expiredCode() {
        val early = discordAccount()
        val earlyCode = issue(early.id)
        clock.advance(LinkCodes.LIFETIME.minusSeconds(1))
        assertEquals(RedeemOutcome.Linked(early.id), redeem(earlyCode.code), "à dix minutes moins une seconde, il vaut encore")
        val late = discordAccount("413000000000000002")
        val lateCode = issue(late.id)
        clock.advance(LinkCodes.LIFETIME)
        assertEquals(RedeemOutcome.Expired, redeem(lateCode.code, gamertag = "Retard"))
        assertNull(accounts.find(connection, late.id)?.gamertag)
        assertNull(registrations.rows["retard"])
        assertNotNull(links.findByCode(connection, lateCode.code))
        assertEquals(1, links.purge(connection, clock.instant()))
        assertEquals(RedeemOutcome.UnknownCode, redeem(lateCode.code, gamertag = "Retard"))
    }

    @Test
    @DisplayName("Un gamertag déjà porté par un autre compte du site : refusé, rien d'écrit, le code reste")
    fun playerTakenByAnotherAccount() {
        accounts.createXbox(connection, "2535400000000001", "Kr Test", Empreinte.duGamertag("Kr Test"), start)
        val account = discordAccount()
        val code = issue(account.id)
        // Même empreinte malgré la casse et les espaces autour.
        assertEquals(RedeemOutcome.PlayerTaken, redeem(code.code, gamertag = " KR TEST "))
        assertNull(accounts.find(connection, account.id)?.gamertag)
        assertTrue(registrations.rows.isEmpty())
        assertEquals(code, links.findByCode(connection, code.code))
    }

    @Test
    @DisplayName("Un gamertag dont l'inscription est déjà reliée à un autre Discord : refusé, l'autre lien reste")
    fun playerTakenByALinkedRegistration() {
        registrations.insert(connection, "kr test", "Kr Test", admitted = false)
        registrations.link(connection, "kr test", "999", admit = false)
        val account = discordAccount()
        val code = issue(account.id)
        assertEquals(RedeemOutcome.PlayerTaken, redeem(code.code))
        assertNull(accounts.find(connection, account.id)?.gamertag)
        assertEquals("999", registrations.rows["kr test"]?.discord)
        assertNotNull(links.findByCode(connection, code.code))
    }

    @Test
    @DisplayName("Une inscription d'avant les comptes, non reliée, est reprise : son gamertag est gardé, le Discord posé, l'admission donnée")
    fun takesOverAnOlderRegistration() {
        registrations.insert(connection, "kr test", "KR test", admitted = false)
        val account = discordAccount()
        val code = issue(account.id)
        assertEquals(RedeemOutcome.Linked(account.id), redeem(code.code))
        assertEquals(1, registrations.rows.size)
        val row = checkNotNull(registrations.rows["kr test"])
        assertEquals("KR test", row.gamertag, "l'inscription existante n'est pas réécrite")
        assertEquals(discordId, row.discord)
        assertTrue(row.admitted)
    }

    @Test
    @DisplayName("Un compte qui porte déjà un autre gamertag : déjà relié, rien n'est changé, le code reste")
    fun alreadyLinkedToAnotherPlayer() {
        val account = discordAccount()
        accounts.setPlayer(connection, account.id, "Autre Nom", Empreinte.duGamertag("Autre Nom"))
        val code = issue(account.id)
        assertEquals(RedeemOutcome.AlreadyLinked, redeem(code.code))
        assertEquals("Autre Nom", accounts.find(connection, account.id)?.gamertag)
        assertTrue(registrations.rows.isEmpty())
        assertNotNull(links.findByCode(connection, code.code))
    }

    @Test
    @DisplayName("Un compte qui porte déjà ce gamertag : relié sans réécrire le compte, même tapé autrement (casse, espaces autour)")
    fun sameGamertagIsNotRewritten() {
        val account = discordAccount()
        accounts.setPlayer(connection, account.id, "Kr Test", Empreinte.duGamertag("Kr Test"))
        val code = issue(account.id)
        assertEquals(RedeemOutcome.Linked(account.id), redeem(code.code, gamertag = " kr test "))
        assertEquals("Kr Test", accounts.find(connection, account.id)?.gamertag, "le gamertag du compte n'est pas réécrit")
        assertEquals(discordId, registrations.rows["kr test"]?.discord)
        assertNull(links.findByCode(connection, code.code))
        // Rejouer ne casse rien.
        val again = issue(account.id)
        assertEquals(RedeemOutcome.Linked(account.id), redeem(again.code))
        assertEquals(1, registrations.rows.size)
    }

    @Test
    @DisplayName("Le Discord du compte déjà posé sur un autre gamertag de site_registrations : une erreur, que la transaction annule")
    fun discordHeldByAnotherRegistration() {
        registrations.insert(connection, "ancien", "Ancien", admitted = false)
        registrations.link(connection, "ancien", discordId, admit = false)
        val account = discordAccount()
        val code = issue(account.id)
        val error = assertThrows<IllegalStateException> { redeem(code.code) }
        assertFalse(error.message.orEmpty().contains(discordId), "le message ne porte pas l'identifiant Discord")
    }
}
