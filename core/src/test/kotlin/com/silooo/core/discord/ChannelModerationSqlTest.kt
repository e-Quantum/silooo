package com.silooo.core.discord

import com.silooo.core.db.MariaDbSupport as Db
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChannelModerationSqlTest {
    private val store get() = JdbcChannelModerationStore(Db.database)
    private val now = Instant.parse("2026-11-08T18:00:00Z")
    private val before = ChannelModerationSnapshot(ChannelPermissionValue(false), 0, true)
    private val locked = before.copy(everyone = ChannelPermissionValue(true, 0, CHANNEL_WRITING_MASK))
    @BeforeEach fun prepare() = Db.prepare()
    private fun case(action: ChannelAction, lock: ChannelModerationLock? = null) = ChannelModerationCase(UUID.randomUUID().toString(), "111", "77", "Motif", now, now.plusSeconds(600),
        ChannelActionPlan(action, if (action == ChannelAction.UNLOCK) locked else before, if (action == ChannelAction.LOCK) locked else before, lockVersion = lock?.version, lockId = lock?.id))

    @Test fun concurrentLockClaimsHaveOneOwnerAndDoNotReplaceTheOriginalState() = runBlocking {
        val first = case(ChannelAction.LOCK); val second = case(ChannelAction.LOCK)
        assertTrue(store.reserve(first)); assertTrue(store.reserve(second))
        val results = listOf(first, second).map { item -> async(Dispatchers.Default) { store.claim(item.id, item.actorId, now, 300) } }.awaitAll()
        assertEquals(1, results.count { it != null })
        val owner = results.filterNotNull().single()
        assertEquals(owner.id, store.lock("77")?.id)
        assertEquals(before.everyone, store.lock("77")?.original)
        assertNull(store.claim(owner.id, owner.actorId, now.plusSeconds(1), 300))
    }

    @Test fun cancelledExpiredOrForeignConfirmationsCannotClaim() = runBlocking {
        val cancelled = case(ChannelAction.LOCK); store.reserve(cancelled)
        assertFalse(store.cancel(cancelled.id, "other", now)); assertTrue(store.cancel(cancelled.id, "111", now))
        assertNull(store.claim(cancelled.id, "111", now, 300)); assertNull(store.lock("77"))
        val expired = case(ChannelAction.LOCK); store.reserve(expired)
        assertNull(store.claim(expired.id, "111", now.plusSeconds(600), 300))
        assertNull(store.claim(expired.id, "other", now, 300))
        assertNull(store.lock("77"))
    }

    @Test fun anOldUnlockCannotAffectANewLockEvenWhenVersionNumbersMatch() = runBlocking {
        val first = case(ChannelAction.LOCK); store.reserve(first); store.claim(first.id, "111", now, 300)
        store.finish(first.id, ChannelCaseStatus.SUCCEEDED, 1, now)
        val lock = store.lock("77")!!
        val staleUnlock = case(ChannelAction.UNLOCK, lock); val realUnlock = case(ChannelAction.UNLOCK, lock)
        store.reserve(staleUnlock); store.reserve(realUnlock)
        assertNotNull(store.claim(realUnlock.id, "111", now, 300))
        store.finish(realUnlock.id, ChannelCaseStatus.SUCCEEDED, 1, now)
        val next = case(ChannelAction.LOCK); store.reserve(next); assertNotNull(store.claim(next.id, "111", now, 300))
        store.finish(next.id, ChannelCaseStatus.SUCCEEDED, 1, now)
        assertEquals(lock.version, store.lock("77")!!.version)
        assertNotEquals(lock.id, store.lock("77")!!.id)
        assertNull(store.claim(staleUnlock.id, "111", now, 300))
        assertEquals(next.id, store.lock("77")!!.id)
    }

    @Test fun restartKeepsOriginalPermissionsAndDoesNotReplayATentativeLock() = runBlocking {
        val item = case(ChannelAction.LOCK); store.reserve(item); store.claim(item.id, "111", now, 300)
        val reloaded = JdbcChannelModerationStore(Db.database)
        assertEquals(ChannelCaseStatus.ATTEMPTED, reloaded.get(item.id)?.status)
        assertNull(reloaded.claim(item.id, "111", now.plusSeconds(301), 300))
        val lock = reloaded.lock("77")!!
        assertEquals(before.everyone, lock.original)
        val recovery = case(ChannelAction.UNLOCK, lock); reloaded.reserve(recovery)
        assertNull(reloaded.claim(recovery.id, "111", now.plusSeconds(100), 300))
        assertNotNull(reloaded.claim(recovery.id, "111", now.plusSeconds(301), 300))
        try { store.finish(item.id, ChannelCaseStatus.SUCCEEDED, 1, now.plusSeconds(302)); fail<Unit>("Un ancien propriétaire ne doit pas terminer le verrou") } catch (_: IllegalStateException) { }
        assertNotNull(reloaded.lock("77"))
        reloaded.finish(recovery.id, ChannelCaseStatus.SUCCEEDED, 1, now.plusSeconds(303))
        assertNull(reloaded.lock("77"))
    }

    @Test fun failedUnsentLockReleasesOwnershipButUncertainLockRetainsIt() = runBlocking {
        val refused = case(ChannelAction.LOCK); store.reserve(refused); store.claim(refused.id, "111", now, 300)
        store.finish(refused.id, ChannelCaseStatus.FAILED, null, now)
        assertNull(store.lock("77"))
        val uncertain = case(ChannelAction.LOCK); store.reserve(uncertain); store.claim(uncertain.id, "111", now, 300)
        store.finish(uncertain.id, ChannelCaseStatus.UNCERTAIN, null, now)
        assertNotNull(store.lock("77")); assertEquals(now.plusSeconds(300), store.lock("77")!!.availableAfter)
        assertNull(store.claim(uncertain.id, "111", now.plusSeconds(301), 300))
    }

    @Test fun retentionNeverRemovesThePermissionsNeededForUnlocking() = runBlocking {
        val item = case(ChannelAction.LOCK); store.reserve(item); store.claim(item.id, "111", now, 300)
        store.finish(item.id, ChannelCaseStatus.SUCCEEDED, 1, now)
        assertEquals(1, store.purge(now.plusSeconds(1)))
        assertNull(store.get(item.id)); assertEquals(before.everyone, store.lock("77")!!.original)
    }
}
