package com.silooo.core.db

import com.silooo.core.api.Refusal
import com.silooo.core.api.RefusalCodes
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.sql.SQLException

/** Les monnaies personnelles (`player_balances`). Les noms sont ceux de l'énumération `currency_ledger.currency`. */
enum class Currency(val column: String) {
    LINGOTS("lingots"),
    GEMMES("gemmes"),
    JETONS("jetons"),
    CLES("cles"),
}

/**
 * Le solde ne couvre pas un retrait. Levée par [creditBalance] AVANT toute écriture : la contrainte
 * `CHECK` de la base reste le filet, pas la règle. [missing] est ce qui manque, en unités entières.
 */
class InsufficientBalance(val currency: Currency, val missing: Long) :
    RuntimeException("Solde de ${currency.name} insuffisant : il manque $missing", null, false, false) {

    /**
     * Le refus du contrat : `PAS_ASSEZ_DE_LINGOTS` ou `PAS_ASSEZ_DE_GEMMES`, avec `missing`. Les jetons et les
     * clés n'ont pas de code dans l'OpenAPI (voir le compte rendu de la session) : tant que le contrat
     * n'en a pas, retirer des jetons ou des clés sans assez de solde reste une erreur de programmation.
     */
    fun toRefusal(): Refusal {
        val reason = when (currency) {
            Currency.LINGOTS -> RefusalCodes.NOT_ENOUGH_LINGOTS
            Currency.GEMMES -> RefusalCodes.NOT_ENOUGH_GEMS
            Currency.JETONS, Currency.CLES -> throw IllegalStateException("Aucun code de refus dans l'OpenAPI pour $currency", this)
        }
        return Refusal(HttpStatusCode.Conflict, reason, missing.toDouble())
    }
}

private const val REASON_MAX = 32 // currency_ledger.reason VARCHAR(32)
private const val REF_ID_MAX = 64 // currency_ledger.ref_id VARCHAR(64)

/** `ER_NO_REFERENCED_ROW_2` : la clé étrangère ne trouve pas la ligne parente (le joueur). */
private const val NO_REFERENCED_ROW = 1452

/**
 * Change un solde personnel de [delta] (positif : gain, négatif : dépense) et l'écrit dans le journal,
 * dans la transaction de [connection] : `player_balances` et `currency_ledger` partent ensemble ou pas du
 * tout (règle 5 du socle). La ligne du joueur est verrouillée pendant la lecture, donc deux
 * mouvements en même temps se suivent au lieu de se mélanger. Le journal garde le solde après le
 * mouvement.
 *
 * - Un solde qui deviendrait négatif lève [InsufficientBalance] avant d'écrire quoi que ce soit.
 * - [delta] à zéro ne fait rien et n'écrit aucune ligne de journal.
 * - [reason] est un mot court (`prestige`, `mission`, `zone`, `marche`, `boutique`, `livraison`), [refId]
 *   l'identifiant de ce qui a causé le mouvement (une commande, une mission…).
 * - Le joueur doit exister dans `players` ; sa ligne de soldes est créée à zéro si elle manque.
 *
 * Rend le solde après le mouvement.
 */
fun creditBalance(
    connection: Connection,
    xuid: Xuid,
    currency: Currency,
    delta: Long,
    reason: String,
    refId: String? = null,
): Long {
    require(reason.isNotBlank() && reason.length <= REASON_MAX) { "reason : 1 à $REASON_MAX caractères" }
    require(refId == null || refId.length <= REF_ID_MAX) { "refId : $REF_ID_MAX caractères au plus" }

    val before = lockBalance(connection, xuid, currency)
    if (delta == 0L) return before

    val after = try {
        Math.addExact(before, delta)
    } catch (e: ArithmeticException) {
        throw IllegalArgumentException("Le solde de $currency déborde : $before + $delta", e)
    }
    if (after < 0) throw InsufficientBalance(currency, -after)

    // currency.column vient de l'énumération ci-dessus : jamais un texte de l'extérieur.
    connection.prepareStatement("UPDATE player_balances SET ${currency.column} = ?, version = version + 1 WHERE xuid = ?").use {
        it.setLong(1, after)
        it.setXuid(2, xuid)
        it.executeUpdate()
    }
    connection.prepareStatement(
        "INSERT INTO currency_ledger (xuid, currency, delta, balance, reason, ref_id) VALUES (?, ?, ?, ?, ?, ?)",
    ).use {
        it.setXuid(1, xuid)
        it.setString(2, currency.name)
        it.setLong(3, delta)
        it.setLong(4, after)
        it.setString(5, reason)
        it.setString(6, refId)
        it.executeUpdate()
    }
    return after
}

/** Lit le solde en verrouillant la ligne ; crée la ligne à zéro si le joueur n'en a pas encore. */
private fun lockBalance(connection: Connection, xuid: Xuid, currency: Currency): Long {
    fun read(): Long? =
        connection.prepareStatement("SELECT ${currency.column} FROM player_balances WHERE xuid = ? FOR UPDATE").use {
            it.setXuid(1, xuid)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }

    read()?.let { return it }
    // INSERT IGNORE : une autre transaction a pu créer la ligne entre-temps ; la relecture verrouillée tranche.
    // Joueur inconnu : la clé étrangère refuse la ligne (en erreur, ou en simple avertissement avec IGNORE selon
    // la version) ; dans les deux cas la relecture ne trouve rien et l'erreur est celle de plus bas.
    try {
        connection.prepareStatement("INSERT IGNORE INTO player_balances (xuid) VALUES (?)").use {
            it.setXuid(1, xuid)
            it.executeUpdate()
        }
    } catch (e: SQLException) {
        if (e.errorCode != NO_REFERENCED_ROW) throw e
    }
    return read() ?: throw IllegalArgumentException("Joueur inconnu : $xuid")
}
