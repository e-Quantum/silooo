package com.silooo.core.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Le corps de la réponse `Refused` de l'OpenAPI : `{"reason": "<CODE>", "missing": <somme>}`.
 * `missing` n'est écrit que pour un refus qui porte une somme (`PAS_ASSEZ_DE_DONAR`…) : le schéma ne
 * l'accepte pas à `null`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RefusedBody(
    val reason: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val missing: Double? = null,
)

/**
 * Les codes de la réponse `Refused` (énumération `reason` de l'OpenAPI). Un code nouveau s'ajoute
 * d'abord au contrat ; `ContractTest` compare cette liste à l'OpenAPI et échoue au moindre écart.
 */
object RefusalCodes {
    const val NOT_ENOUGH_DONAR = "PAS_ASSEZ_DE_DONAR"
    const val NOT_ENOUGH_LINGOTS = "PAS_ASSEZ_DE_LINGOTS"
    const val NOT_ENOUGH_GEMS = "PAS_ASSEZ_DE_GEMMES"
    const val NOT_THE_CEO = "PAS_LE_PDG"
    const val THRESHOLD_NOT_REACHED = "SEUIL_NON_ATTEINT"
    const val FACTORY_LOCKED = "USINE_VERROUILLEE"
    const val ZONE_LOCKED = "ZONE_VERROUILLEE"
    const val SPOT_TAKEN = "EMPLACEMENT_OCCUPE"
    const val COMPANY_FULL = "ENTREPRISE_PLEINE"
    const val ALREADY_OWNED = "DEJA_POSSEDE"
    const val PREVIOUS_RANK_REQUIRED = "GRADE_PRECEDENT_REQUIS"

    /** L'entreprise d'un joueur est déjà lancée : elle ne peut pas rejoindre une autre entreprise (`invite`, `acceptInvite`). */
    const val ENTERPRISE_UNDERWAY = "ENTREPRISE_EN_COURS"

    /** L'écriture a perdu contre une autre (version dépassée, même requête en cours) : l'add-on relit et rejoue. */
    const val CONFLICT_RETRY = "CONFLIT_REESSAYER"

    val ALL: Set<String> = setOf(
        NOT_ENOUGH_DONAR, NOT_ENOUGH_LINGOTS, NOT_ENOUGH_GEMS, NOT_THE_CEO, THRESHOLD_NOT_REACHED,
        FACTORY_LOCKED, ZONE_LOCKED, SPOT_TAKEN, COMPANY_FULL, ALREADY_OWNED, PREVIOUS_RANK_REQUIRED,
        CONFLICT_RETRY, ENTERPRISE_UNDERWAY,
    )
}

/**
 * Un refus du jeu, à lever depuis une action : la transaction est annulée (rien de ce que l'action a
 * écrit ne reste), puis la réponse `Refused` part avec [status] (409 en général, 403 pour
 * `PAS_LE_PDG`). Dans [idempotent], un refus est gardé comme n'importe quelle réponse et rejoué tel quel ;
 * seul `CONFLIT_REESSAYER` ne l'est pas, parce que le nouvel essai doit pouvoir réussir.
 *
 * Sans trace de pile : c'est du flux normal, pas une panne.
 */
open class Refusal(
    val status: HttpStatusCode,
    val reason: String,
    val missing: Double? = null,
) : RuntimeException(reason, null, false, false) {
    init {
        require(reason in RefusalCodes.ALL) { "Code de refus hors contrat : $reason" }
    }

    /** Vrai pour un refus qu'un nouvel essai avec le même `requestId` peut faire changer. */
    val isTransient: Boolean get() = reason == RefusalCodes.CONFLICT_RETRY

    fun body(): RefusedBody = RefusedBody(reason, missing)
}

/** Raccourci pour le cas courant : 409 avec le code donné. */
fun refuse(reason: String, missing: Double? = null): Nothing = throw Refusal(HttpStatusCode.Conflict, reason, missing)

/** Répond avec le corps `Refused` du contrat. Même écriture à la main que [respondError]. */
suspend fun ApplicationCall.respondRefusal(refusal: Refusal) {
    respondText(SiloooJson.encodeToString(RefusedBody.serializer(), refusal.body()), ContentType.Application.Json, refusal.status)
}
