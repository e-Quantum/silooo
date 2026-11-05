package com.silooo.core.sitedata

import com.silooo.core.db.MariaDbSupport
import java.math.BigInteger
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Les lectures du site (classement de la semaine, fiche d'entreprise) sur MariaDB 12.3.3. Ignoré sans Docker, joué dans la CI. */
class SiteStatsSqlTest {
    private val store = JdbcSiteStatsStore()

    private val weekStart = Instant.parse("2026-11-02T00:00:00Z")
    private val weekEnd = Instant.parse("2026-11-09T00:00:00Z")

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    private fun <T> inTransaction(block: (Connection) -> T): T = runBlocking { MariaDbSupport.database.transaction(block) }

    private fun ranking(limit: Int = 25, offset: Int = 0) = inTransaction { store.weeklyRanking(it, weekStart, weekEnd, limit, offset) }

    private fun at(text: String): LocalDateTime = LocalDateTime.ofInstant(Instant.parse(text), ZoneOffset.UTC)

    private fun earn(xuid: String, delta: Long, createdAt: String, currency: String = "LINGOTS") {
        MariaDbSupport.update(
            "INSERT INTO currency_ledger (xuid, currency, delta, balance, reason, created_at) VALUES (?, ?, ?, 0, 'test', ?)",
            BigInteger(xuid), currency, delta, at(createdAt),
        )
    }

    private fun join(companyId: Long, xuid: String, role: String, joinedAt: String) {
        MariaDbSupport.update(
            "INSERT INTO company_members (company_id, xuid, role, share_percent, joined_at) VALUES (?, ?, ?, 50, ?)",
            companyId, BigInteger(xuid), role, at(joinedAt),
        )
    }

    @Test
    @DisplayName("Classement : la somme des gains de la semaine par joueur, du plus riche au moins riche")
    fun sumsPerPlayer() {
        MariaDbSupport.insertPlayer("1", "Premier")
        MariaDbSupport.insertPlayer("2", "Second")
        earn("1", 100, "2026-11-03T10:00:00Z")
        earn("1", 250, "2026-11-04T10:00:00Z")
        earn("2", 300, "2026-11-05T10:00:00Z")
        val rows = ranking()
        assertEquals(listOf("Premier", "Second"), rows.map { it.gamertag })
        assertEquals(listOf(350L, 300L), rows.map { it.lingots })
        assertEquals(listOf(1, 2), rows.map { it.rank })
    }

    @Test
    @DisplayName("Classement : seuls les gains de la semaine comptent, bornes comprises (lundi 00:00 inclus, lundi suivant 00:00 exclu)")
    fun weekBounds() {
        MariaDbSupport.insertPlayer("1", "Dedans")
        earn("1", 1, "2026-11-01T23:59:59Z")
        earn("1", 10, "2026-11-02T00:00:00Z")
        earn("1", 100, "2026-11-08T23:59:59Z")
        earn("1", 1_000, "2026-11-09T00:00:00Z")
        assertEquals(110L, ranking().single().lingots)
    }

    @Test
    @DisplayName("Classement : les dépenses (delta négatif) et les autres monnaies sont ignorées ; un joueur sans gain n'y est pas")
    fun onlyEarnedLingots() {
        MariaDbSupport.insertPlayer("1", "Gagnant")
        MariaDbSupport.insertPlayer("2", "Depensier")
        MariaDbSupport.insertPlayer("3", "Gemmes")
        earn("1", 40, "2026-11-03T10:00:00Z")
        earn("1", -500, "2026-11-03T11:00:00Z")
        earn("2", -900, "2026-11-03T10:00:00Z")
        earn("3", 5_000, "2026-11-03T10:00:00Z", currency = "GEMMES")
        earn("3", 7_000, "2026-11-03T10:00:00Z", currency = "JETONS")
        val rows = ranking()
        assertEquals(listOf("Gagnant"), rows.map { it.gamertag })
        assertEquals(40L, rows.single().lingots)
    }

    @Test
    @DisplayName("Classement : le nom et l'identifiant de l'entreprise, ou rien pour un joueur sans entreprise ; jamais un doublon de ligne")
    fun companyColumn() {
        MariaDbSupport.insertPlayer("1", "AvecEntreprise")
        MariaDbSupport.insertPlayer("2", "Sans")
        val company = MariaDbSupport.insertCompany("Les Silos")
        join(company, "1", "PDG", "2026-10-01T00:00:00Z")
        earn("1", 20, "2026-11-03T10:00:00Z")
        earn("2", 10, "2026-11-03T10:00:00Z")
        val rows = ranking()
        assertEquals(2, rows.size)
        assertEquals(RankedPlayer(1, "AvecEntreprise", company, "Les Silos", 20), rows[0])
        assertEquals(RankedPlayer(2, "Sans", null, null, 10), rows[1])
    }

    @Test
    @DisplayName("Classement : l'égalité se départage par gamertag, la page se lit avec limite et décalage, le rang continue")
    fun tiesAndPaging() {
        for ((xuid, name) in listOf("1" to "Charlie", "2" to "Alice", "3" to "Bob")) {
            MariaDbSupport.insertPlayer(xuid, name)
            earn(xuid, 100, "2026-11-03T10:00:00Z")
        }
        assertEquals(listOf("Alice", "Bob", "Charlie"), ranking().map { it.gamertag })
        val second = ranking(limit = 2, offset = 1)
        assertEquals(listOf("Bob", "Charlie"), second.map { it.gamertag })
        assertEquals(listOf(2, 3), second.map { it.rank })
        assertEquals(emptyList<RankedPlayer>(), ranking(limit = 2, offset = 3))
    }

    @Test
    @DisplayName("Fiche : nom, époque, étoiles, date de création, nombre de prestiges et membres (PDG d'abord, puis par date d'arrivée)")
    fun companyCard() {
        MariaDbSupport.insertPlayer("1", "Associe tardif")
        MariaDbSupport.insertPlayer("2", "Chef")
        MariaDbSupport.insertPlayer("3", "Associe ancien")
        val company = MariaDbSupport.insertCompany("Forge & Fils")
        MariaDbSupport.update("UPDATE companies SET era = 3, stars = 12, created_at = ? WHERE id = ?", at("2026-10-01T08:30:00Z"), company)
        join(company, "1", "ASSOCIE", "2026-10-20T00:00:00Z")
        join(company, "2", "PDG", "2026-10-25T00:00:00Z")
        join(company, "3", "ASSOCIE", "2026-10-10T00:00:00Z")
        for (course in 1..2) {
            MariaDbSupport.update(
                "INSERT INTO prestige_history (company_id, course_number, era_before, stars_after, threshold, donar_earned, lingots_total, duration_hours, decided_by) " +
                    "VALUES (?, ?, 1, ?, 1000, 1000, 10, 2.5, ?)",
                company, course, course, BigInteger("2"),
            )
        }
        val card = checkNotNull(inTransaction { store.company(it, company) })
        assertEquals(company, card.id)
        assertEquals("Forge & Fils", card.name)
        assertEquals(3, card.era)
        assertEquals(12, card.stars)
        assertEquals(Instant.parse("2026-10-01T08:30:00Z"), card.createdAt)
        assertEquals(2, card.prestiges)
        assertEquals(
            listOf(CompanyMember("Chef", MemberRole.PDG), CompanyMember("Associe ancien", MemberRole.ASSOCIE), CompanyMember("Associe tardif", MemberRole.ASSOCIE)),
            card.members,
        )
    }

    @Test
    @DisplayName("Fiche : sans prestige le compte est nul, une entreprise inconnue rend null, une autre entreprise ne mélange pas ses membres")
    fun companyEdges() {
        MariaDbSupport.insertPlayer("1", "Seul")
        MariaDbSupport.insertPlayer("2", "Ailleurs")
        val company = MariaDbSupport.insertCompany("Premiere", slotIndex = 0)
        val other = MariaDbSupport.insertCompany("Seconde", slotIndex = 1)
        join(company, "1", "PDG", "2026-10-01T00:00:00Z")
        join(other, "2", "PDG", "2026-10-01T00:00:00Z")
        val card = checkNotNull(inTransaction { store.company(it, company) })
        assertEquals(0, card.prestiges)
        assertEquals(listOf("Seul"), card.members.map { it.gamertag })
        assertNull(inTransaction { store.company(it, 987_654) })
        assertFalse(card.toString().contains("donar", ignoreCase = true))
    }
}
