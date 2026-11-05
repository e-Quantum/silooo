package com.silooo.core.economy

/**
 * Combien de niveaux le joueur demande d'un coup : l'énumération `amount` du contrat
 * (`buyLevels` dans api/silooo-core.openapi.yaml).
 *
 * `ONE` et `TEN` sont des tailles de lot d'interface, pas des chiffres d'équilibrage : le contrat les
 * fixe, `definitions/` n'a rien à en dire.
 */
enum class BuyAmount(
    /** La valeur écrite dans le JSON du contrat. */
    val wire: String,
    /** Le nombre de niveaux d'un lot fixe ; `null` quand il dépend de l'état (palier, max). */
    val fixedLevels: Int?,
) {
    ONE("1", 1),
    TEN("10", 10),

    /** Jusqu'au prochain palier ×2 de l'usine. */
    MILESTONE("palier", null),

    /** Tout ce que le solde paie. */
    MAX("max", null),
    ;

    companion object {
        /** La valeur du contrat vers l'énumération ; `null` si le JSON dit autre chose (le contrat refuse alors, 400). */
        fun fromWire(value: String): BuyAmount? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Le prix d'un achat de niveaux, calculé par [EconomyEngine.quote]. Le service compare [cost] au solde
 * (c'est [affordable]) et ne touche à rien si l'achat est refusé.
 */
data class LevelQuote(
    /** Niveaux achetés. 0 seulement pour `MAX` quand le solde ne paie même pas un niveau. */
    val levels: Int,
    /** Prix total en Donar : la somme des prix des [levels] niveaux. 0 quand [levels] vaut 0. */
    val cost: Double,
    /** Le niveau de l'usine après l'achat. */
    val newLevel: Int,
    /** Le solde paie [cost] (toujours vrai pour `MAX` quand [levels] dépasse 0). */
    val affordable: Boolean,
)
