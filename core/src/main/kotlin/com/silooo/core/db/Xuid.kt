package com.silooo.core.db

import java.math.BigInteger
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.Types
import java.util.UUID

/**
 * Un XUID valide : un entier de 0 à 2^64 - 1, qui tient dans un `BIGINT UNSIGNED`. Dans le JSON il
 * voyage en chaîne (règle 6 du socle) ; en base c'est un entier, et un 64 bits non signé ne tient pas
 * dans un `Long` de Kotlin. D'où ce type, qui garde le [BigInteger] et ne se construit que par [parse].
 */
class Xuid private constructor(val number: BigInteger) {

    /** La forme du JSON : des chiffres, sans zéro en tête. */
    val text: String get() = number.toString()

    /**
     * L'UUID que Floodgate déduit du XUID sur SileneMC (`players.floodgate_uuid`) : les 64 bits du
     * XUID dans la moitié basse, zéro dans la moitié haute.
     */
    val floodgateUuid: UUID get() = UUID(0L, number.toLong())

    override fun equals(other: Any?) = other is Xuid && other.number == number
    override fun hashCode() = number.hashCode()
    override fun toString() = text

    companion object {
        /** Schéma `Xuid` de l'OpenAPI : 1 à 20 chiffres. */
        private val SHAPE = Regex("^[0-9]{1,20}$")
        private val MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

        /** `null` si [raw] n'a pas la forme du contrat, ou ne tient pas dans un `BIGINT UNSIGNED`. */
        fun parse(raw: String): Xuid? {
            if (!SHAPE.matches(raw)) return null
            val number = BigInteger(raw)
            return if (number > MAX) null else Xuid(number)
        }
    }
}

/**
 * Pose un XUID (ou `NULL`). On passe le [BigInteger] et non une chaîne : une comparaison
 * `xuid = '2535…'` se ferait en nombre à virgule côté MariaDB et perdrait des chiffres au-delà de 2^53.
 */
fun PreparedStatement.setXuid(index: Int, xuid: Xuid?) {
    if (xuid == null) setNull(index, Types.BIGINT) else setObject(index, xuid.number)
}

/** `ER_DUP_ENTRY` : une clé unique ou primaire existe déjà. */
private const val DUPLICATE_ENTRY = 1062

/** Vrai pour une violation de clé unique ou primaire, quelle que soit la façon dont le pilote la remonte. */
fun SQLException.isDuplicateKey(): Boolean = this is SQLIntegrityConstraintViolationException || errorCode == DUPLICATE_ENTRY
