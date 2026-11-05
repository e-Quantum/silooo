package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `discord_members` sur la vraie MariaDB : une arrivée, l'accueil, le bonjour, le tuto, un départ, un retour. Ignoré sans Docker : la CI le joue. */
class MemberSqlTest {
    private val store get() = JdbcMemberStore(MariaDbSupport.database)
    private val start = Instant.parse("2026-11-05T12:00:00Z")

    @BeforeEach
    fun prepare() = MariaDbSupport.prepare()

    @Test
    @DisplayName("Une arrivée s'écrit, l'accueil et le bonjour la complètent, un départ puis un retour gardent la première arrivée")
    fun recordsAnArrival() = runBlocking {
        store.joined("7", start)
        store.onboarded("7", start + Duration.ofSeconds(40), "console")
        store.tutorialSent("7", start + Duration.ofSeconds(50), refused = true)
        store.greeted("7", start + Duration.ofSeconds(50))
        val greeted = checkNotNull(store.member("7"))
        assertEquals(MemberRecord("7", start, start, start + Duration.ofSeconds(40), "console", start + Duration.ofSeconds(50), start + Duration.ofSeconds(50), true, null), greeted)

        store.left("7", start + Duration.ofDays(1))
        assertEquals(start + Duration.ofDays(1), store.member("7")?.leftAt)
        store.joined("7", start + Duration.ofDays(3))
        val back = checkNotNull(store.member("7"))
        assertNull(back.leftAt)
        assertEquals(start, back.firstJoinedAt)
        assertEquals(start + Duration.ofDays(3), back.joinedAt)
        assertEquals("console", back.platform, "le reste ne bouge pas")
        assertEquals(1L, MariaDbSupport.count("discord_members"))
    }

    @Test
    @DisplayName("Un accueil fini sans arrivée connue (bot éteint à ce moment) crée le membre ; un bonjour sans membre ne fait rien")
    fun createsOnOnboardingWhenTheJoinWasMissed() = runBlocking {
        store.onboarded("8", start, null)
        val record = checkNotNull(store.member("8"))
        assertEquals(start, record.firstJoinedAt)
        assertEquals(start, record.onboardedAt)
        assertNull(record.platform)
        assertFalse(record.tutorialRefused)

        store.greeted("9", start)
        assertNull(store.member("9"))
        assertTrue(store.member("8")?.greetedAt == null)
    }
}
