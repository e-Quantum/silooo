package com.silooo.core.discord

import java.time.Instant

/** [MemberStore] en mémoire, avec les mêmes règles que le SQL : la première arrivée reste, un retour efface le départ. */
class InMemoryMemberStore : MemberStore {
    val records = mutableMapOf<String, MemberRecord>()

    override suspend fun joined(userId: String, at: Instant) {
        val known = records[userId]
        records[userId] = known?.copy(joinedAt = at, leftAt = null) ?: MemberRecord(userId, at, at)
    }

    override suspend fun onboarded(userId: String, at: Instant, platform: String?) {
        val known = records[userId] ?: MemberRecord(userId, at, at)
        records[userId] = known.copy(onboardedAt = at, platform = platform)
    }

    override suspend fun greeted(userId: String, at: Instant) {
        records[userId]?.let { records[userId] = it.copy(greetedAt = at) }
    }

    override suspend fun tutorialSent(userId: String, at: Instant, refused: Boolean) {
        records[userId]?.let { records[userId] = it.copy(tutorialAt = at, tutorialRefused = refused) }
    }

    override suspend fun left(userId: String, at: Instant) {
        records[userId]?.let { records[userId] = it.copy(leftAt = at) }
    }

    override suspend fun member(userId: String): MemberRecord? = records[userId]
}
