package com.silooo.core.economy

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * La tolérance des tests dorés, et pourquoi elle vaut ce qu'elle vaut.
 *
 * Les JSON dorés gardent les valeurs que le tableur avait en cache, à 15 chiffres significatifs. Arrondir à 15
 * chiffres change une valeur d'au plus une demi-unité du 15e chiffre, soit 5e-15 en relatif (au pire, pour une
 * mantisse qui commence par 1) : aucun rejeu, même parfait, ne peut faire mieux que 5e-15 contre ces fichiers.
 *
 * Mesure du rejeu complet (965 achats, 9 courses), écart relatif maximal contre le tableur :
 *  - prix : 4,1e-15 ; revenu avant et après achat : 4,6e-15 ; Donar gagnés : 4,4e-15 ;
 *  - attente : 4,8e-15 ; heure dans la course : 4,5e-15 ; heure cumulée : 4,8e-15 ;
 *  - heure de départ de course : 2,2e-15 ; durée : 4,5e-15 ; multiplicateur : 3,4e-15.
 * Le pire écart, 4,8e-15, est donc l'arrondi du cache et rien d'autre : le moteur calcule ce que calcule le tableur.
 *
 * La tolérance est 1e-14 : le double de la borne de l'arrondi (5e-15), pour que l'ordre des opérations de la
 * feuille, qui n'est pas toujours le nôtre, ne fasse pas échouer un test pour un bit. Elle est cinq ordres de
 * grandeur plus serrée que le 1e-9 que suggère le README doré, et elle détecte toujours un niveau d'écart : un
 * niveau d'usine de plus ou de moins change un prix de 11 % au moins, un Lingot gardé de plus ou de moins
 * le multiplicateur de 8e-4 au moins.
 *
 * Les calculs du moteur passent par `StrictMath` et l'arithmétique stricte de la JVM 17 : le résultat est le même
 * bit pour bit sur toutes les machines, la tolérance ne cache donc aucune variation d'une machine à l'autre.
 */
object GoldenTolerance {
    const val RELATIVE = 1e-14
}

/** Vérifie `actual` contre la valeur dorée `expected` à [GoldenTolerance.RELATIVE] près, en relatif. */
fun assertGolden(expected: Double, actual: Double, message: String) {
    assertTrue(
        closeTo(expected, actual),
        "$message : le tableur dit $expected, le moteur donne $actual (écart relatif ${relativeError(expected, actual)}, tolérance ${GoldenTolerance.RELATIVE})",
    )
}

fun relativeError(expected: Double, actual: Double): Double =
    if (expected == actual) 0.0 else abs(actual - expected) / abs(expected)

fun closeTo(expected: Double, actual: Double): Boolean = expected == actual || relativeError(expected, actual) <= GoldenTolerance.RELATIVE

/**
 * Le texte du tableur ramené à la règle de la charte. Deux différences, et elles seules : le tableur met un
 * point décimal et garde une décimale à trois chiffres (« 195.5 M »), la charte veut la virgule et trois
 * chiffres au plus (« 195 M »). La décimale du tableur est tronquée comme la nôtre. Point 6 du README doré,
 * à valider par Kr.
 */
fun sheetToCharter(sheet: String): String {
    val match = checkNotNull(Regex("^(\\d+)(?:\\.(\\d+))?(?: (\\S+))?$").matchEntire(sheet)) { "Texte du tableur illisible : $sheet" }
    val (whole, decimals, suffix) = match.destructured
    val number = if (decimals.isEmpty() || whole.length >= 3) whole else "$whole,$decimals"
    return if (suffix.isEmpty()) number else "$number $suffix"
}
