package com.silooo.core.discord

import java.time.Instant

/**
 * [GiveawayStore] en mémoire, avec les mêmes règles que le SQL : les numéros partent de 1, un inscrit ne compte qu'une
 * fois, un giveaway déjà tiré ne se tire pas deux fois, et seules les cartes postées comptent dans le mois.
 */
class InMemoryGiveawayStore : GiveawayStore {
    /** Un lot gardé sur l'identifiant Discord ; [deliveredAt] reste vide : le crédit en jeu n'est pas ici. */
    data class Prize(
        val userId: String,
        val giveawayId: Int,
        val kind: String,
        val amount: Long,
        val label: String?,
        val wonAt: Instant,
        val deliveredAt: Instant? = null,
    )

    val giveaways = mutableListOf<Giveaway>()
    val entered = mutableMapOf<Int, MutableList<String>>()
    val prizes = mutableListOf<Prize>()

    override suspend fun create(
        title: String,
        prizeKind: String,
        prizeAmount: Long,
        prizeLabel: String?,
        channelId: String,
        endsAt: Instant,
        createdBy: String,
        at: Instant,
    ): Int {
        val id = (giveaways.maxOfOrNull { it.id } ?: 0) + 1
        giveaways += Giveaway(id, title, prizeKind, prizeAmount, prizeLabel, channelId, null, endsAt, null, null, createdBy, at)
        return id
    }

    override suspend fun carded(id: Int, messageId: String) = update(id) { it.copy(messageId = messageId) }

    override suspend fun enter(id: Int, userId: String): Boolean {
        if (giveaways.none { it.id == id }) return false
        val list = entered.getOrPut(id) { mutableListOf() }
        if (userId in list) return false
        list += userId
        return true
    }

    override suspend fun entries(id: Int): List<String> = entered[id]?.toList() ?: emptyList()

    override suspend fun open(): List<Giveaway> = giveaways.filter { it.drawnAt == null }.sortedWith(compareBy({ it.endsAt }, { it.id }))

    override suspend fun drawn(id: Int, winnerId: String?, at: Instant) =
        update(id) { if (it.drawnAt == null) it.copy(drawnAt = at, winnerId = winnerId) else it }

    override suspend fun countCreatedBetween(from: Instant, to: Instant): Int =
        giveaways.count { it.messageId != null && !it.createdAt.isBefore(from) && it.createdAt.isBefore(to) }

    override suspend fun addPrize(userId: String, giveawayId: Int, kind: String, amount: Long, label: String?, at: Instant) {
        prizes += Prize(userId, giveawayId, kind, amount, label, at)
    }

    private fun update(id: Int, change: (Giveaway) -> Giveaway) {
        val index = giveaways.indexOfFirst { it.id == id }
        if (index >= 0) giveaways[index] = change(giveaways[index])
    }
}
