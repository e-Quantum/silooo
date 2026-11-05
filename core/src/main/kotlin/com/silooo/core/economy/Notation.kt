package com.silooo.core.economy

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * L'affichage des grands nombres : k, M, Md, puis aa, ab, ac… deux lettres par puissance de mille
 * (clé `notation.apresMd` de definitions/economie.json, table complète dans la feuille Notation du
 * tableur, rejouée par les tests dorés).
 *
 * Règle d'affichage (design/README.md, « Les mots », et design/composants/Compteur.md : « trois
 * chiffres au plus, une virgule, puis l'unité : 84,3 Md · 1,2 aa · 512 k ») :
 *  - virgule décimale, une espace avant l'unité ;
 *  - trois chiffres au plus : une décimale sous 100 (« 84,3 », « 1,2 »), aucune à partir de 100 (« 512 ») ;
 *  - la décimale est tronquée, jamais arrondie : « 1,99 k » s'affiche « 1,9 k » et le compteur ne
 *    montre jamais plus que le joueur n'a ;
 *  - sous 1 000, un entier arrondi à la demi-unité supérieure (« 523 » pour 522,78), comme le tableur ;
 *  - une décimale nulle disparaît : « 1 Md », pas « 1,0 Md ».
 *
 * Cette règle est à valider par Kr : le tableur écrit une décimale même à trois chiffres et un point
 * (« 195.5 M »), la charte veut la virgule et trois chiffres (point 6 de golden/README.md).
 *
 * Le calcul passe par la valeur du Double arrondie à 15 chiffres significatifs (la précision qu'un Double
 * garantit, celle que le tableur affiche aussi) et par la division décimale, non par une division en binaire.
 * Un prix qui sort du calcul à un bit sous son entier (119999,99999999999) s'affiche donc « 120 k » comme le
 * joueur l'attend, et 1 200 s'affiche « 1,2 k » même si 1200 / 1000 vaut 1,1999… en machine. Le résultat est
 * le même sur toutes les machines.
 */
object Notation {
    /** Le Double atteint 1e308 : la puissance de mille 102 (« du ») est la dernière utile. */
    const val MAX_DOUBLE_POWER = 102

    /** Les puissances de mille que les deux lettres savent nommer (de « aa » à « zz »). */
    private const val LETTERS = 26
    private const val FIRST_TWO_LETTER_POWER = 4
    private const val LAST_POWER = FIRST_TWO_LETTER_POWER + LETTERS * LETTERS - 1

    /** Un Double garantit 15 chiffres décimaux : au-delà, ce sont des restes de calcul. */
    private val SIGNIFICANT_DIGITS = MathContext(15, RoundingMode.HALF_EVEN)

    private val THOUSAND = BigDecimal(1000)
    private val HUNDRED = BigDecimal(100)

    /**
     * Le suffixe de la puissance de mille `power` : « » (1), « k » (1 000), « M », « Md », puis « aa »
     * (1e12), « ab »… « az », « ba »…
     */
    fun suffix(power: Int): String {
        require(power in 0..LAST_POWER) { "Puissance de mille sans suffixe : $power (de 0 à $LAST_POWER)" }
        return when (power) {
            0 -> ""
            1 -> "k"
            2 -> "M"
            3 -> "Md"
            else -> {
                val index = power - FIRST_TWO_LETTER_POWER
                "${'a' + index / LETTERS}${'a' + index % LETTERS}"
            }
        }
    }

    /** Un Donar à afficher. Zéro et moins donnent « 0 ». Un nombre infini ou NaN est une erreur de l'appelant. */
    fun format(value: Double): String {
        require(value.isFinite()) { "Un nombre à afficher doit être fini : $value" }
        if (value <= 0.0) return "0"

        val exact = BigDecimal(value).round(SIGNIFICANT_DIGITS)
        if (exact < THOUSAND) {
            val rounded = exact.setScale(0, RoundingMode.HALF_UP)
            // 999,5 et plus s'arrondit à 1 000 : on passe à l'unité du dessus au lieu d'écrire quatre chiffres.
            if (rounded < THOUSAND) return rounded.toPlainString()
            return "1 ${suffix(1)}"
        }

        val power = (exact.precision() - exact.scale() - 1) / 3
        val mantissa = exact.movePointLeft(3 * power)
        val decimals = if (mantissa >= HUNDRED) 0 else 1
        val shown = mantissa.setScale(decimals, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
        return "${shown.replace('.', ',')} ${suffix(power)}"
    }
}
