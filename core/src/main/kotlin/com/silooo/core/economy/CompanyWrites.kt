package com.silooo.core.economy

import com.silooo.core.db.CompanyColumn
import com.silooo.core.db.Xuid
import com.silooo.core.db.updateCompany
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Membership
import com.silooo.core.session.Production
import com.silooo.core.session.SessionStore
import java.sql.Connection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Ce que les services qui écrivent sur l'entreprise ([CompanyService], `StaffService`) partagent : une seule façon de
 * verser la production en attente et de retirer une dépense, pour que le Donar s'écrive partout de la même manière.
 */

/**
 * Verse [production] sur l'entreprise, retire [spent] du Donar et remet `last_collect_at` : UNE écriture, un seul
 * changement de version (`updateCompany`, verrou optimiste). Rend la ligne telle qu'elle est maintenant, sans la relire.
 *
 * Le solde est calculé comme dans le devis de `buyLevels` (`donar + production`) avant le retrait : un achat jugé
 * payable ne laisse jamais un Donar négatif, même à l'arrondi près.
 *
 * À appeler AVANT toute autre écriture de l'action : c'est la version de l'entreprise qui protège les écritures suivantes
 * d'une action concurrente.
 *
 * [mairieLevel], quand il est donné, est le nouveau niveau de la mairie : il s'écrit dans la même écriture, donc au même
 * changement de version (l'amélioration de la mairie, voir `CityService`).
 */
internal fun writeProduction(
    connection: Connection,
    company: CompanyRecord,
    production: Production,
    spent: Double,
    mairieLevel: Int? = null,
): CompanyRecord {
    val balance = company.donar + production.earned
    val donar = balance - spent
    val earned = company.donarEarned + production.earned
    val changes = mutableListOf<Pair<CompanyColumn, Any?>>(
        CompanyColumn.DONAR to donar,
        CompanyColumn.DONAR_EARNED to earned,
        CompanyColumn.LAST_COLLECT_AT to production.at,
    )
    if (mairieLevel != null) changes += CompanyColumn.MAIRIE_LEVEL to mairieLevel
    val version = updateCompany(connection, company.id, company.version, *changes.toTypedArray())
    return company.copy(
        donar = donar,
        donarEarned = earned,
        lastCollectAt = production.at,
        mairieLevel = mairieLevel ?: company.mairieLevel,
        version = version,
    )
}

/** Un montant manquant doit rester un nombre JSON : un prix qui sortirait des doubles vaut [Double.MAX_VALUE]. */
internal fun Double.finiteOrMax(): Double = if (isFinite()) this else Double.MAX_VALUE

/** L'appartenance de [actor] à l'entreprise [companyId], avec son rôle. Sinon [RequestRejected] (le contrat n'a ni 403 ni 404 ici). */
internal fun SessionStore.requireMembership(connection: Connection, companyId: Long, actor: Xuid): Membership {
    val membership = membershipOf(connection, actor)
    if (membership == null || membership.companyId != companyId) {
        throw RequestRejected("L'acteur $actor n'est pas membre de l'entreprise $companyId")
    }
    return membership
}

/** L'entreprise et ses usines, pour un acteur qui en est membre. Sinon [RequestRejected]. */
internal fun SessionStore.loadForActor(connection: Connection, companyId: Long, actor: Xuid): Pair<CompanyRecord, List<FactoryRecord>> {
    requireMembership(connection, companyId, actor)
    return loadCompany(connection, companyId) to loadFactories(connection, companyId)
}

/** Le `payload` d'un événement d'entreprise : `action` et `companyId`, puis ce que [fill] ajoute. */
internal fun companyEvent(action: String, companyId: Long, fill: JsonObjectBuilder.() -> Unit): JsonObject =
    buildJsonObject {
        put("action", action)
        put("companyId", companyId)
        fill()
    }
