package com.silooo.core.staff

import com.silooo.core.definitions.PersonnelDefinitions
import com.silooo.core.definitions.StaffRarity
import com.silooo.core.definitions.StaffRole
import java.time.LocalDate

/**
 * Un candidat de la liste du jour, avant toute embauche : ce que l'agence propose à une entreprise un jour donné.
 * [slot] va de 1 à `candidatsParJour` ; il fixe le rôle (`agence.rolesParPlace`).
 */
data class Candidate(
    val slot: Int,
    val name: String,
    val skinId: String,
    val role: StaffRole,
    val rarity: StaffRarity,
)

/**
 * Le tirage de la liste du jour de l'agence d'emploi. Il est entièrement déterminé par l'entreprise et le jour (UTC) :
 * la même liste toute la journée, à chaque lecture et sur toutes les machines, sans rien écrire en base. Il n'y a pas
 * d'autre entrée, donc aucun moyen de payer pour une autre liste : ni relance, ni tirage supplémentaire.
 *
 * Les raretés suivent la répartition de `raretes.*.repartitionPourcent` (75 / 20 / 5 aujourd'hui), tirée place par place ;
 * les noms de la liste sont tous différents ; l'apparence est choisie parmi celles du rôle de la place. Rien ici n'est un
 * chiffre du jeu : tout vient de [personnel].
 *
 * Le générateur est [SplitMix64], écrit ici et non `kotlin.random` ni `java.util.Random` : sa suite est figée par son
 * algorithme, donc une liste ne change pas quand la JVM ou Kotlin change.
 */
class AgencyDraw(private val personnel: PersonnelDefinitions) {

    /** Le nombre de places de la liste. */
    val slots: Int get() = personnel.agency.candidatesPerDay

    /** La liste du jour [day] de l'entreprise [companyId], dans l'ordre des places. */
    fun candidates(companyId: Long, day: LocalDate): List<Candidate> {
        val random = SplitMix64(companyId * GOLDEN_GAMMA + day.toEpochDay())
        val names = personnel.agency.names.toMutableList()
        return personnel.agency.rolesPerSlot.mapIndexed { index, role ->
            val rarity = rarityAt(random.nextUnit() * PERCENT)
            val name = names.removeAt(random.nextInt(names.size))
            val skins = personnel.agency.appearances.getValue(role)
            Candidate(slot = index + 1, name = name, skinId = skins[random.nextInt(skins.size)], role = role, rarity = rarity)
        }
    }

    /**
     * La rareté qui contient le tirage [roll] (de 0 inclus à 100 exclu), les raretés se suivant dans l'ordre de
     * déclaration de [StaffRarity] : la plus courante prend le début de l'intervalle.
     */
    internal fun rarityAt(roll: Double): StaffRarity {
        var upTo = 0.0
        for (rarity in StaffRarity.entries) {
            upTo += personnel.rarity(rarity).sharePercent
            if (roll < upTo) return rarity
        }
        // Les parts font 100 (DefinitionsChecks) : on n'arrive ici que par le bruit du dernier bit.
        return StaffRarity.entries.last()
    }

    private companion object {
        /** Un pourcent est un centième : constante de la nature. */
        const val PERCENT = 100.0

        /** Le pas de SplitMix64 (la partie fractionnaire du nombre d'or sur 64 bits), aussi utilisé pour séparer les entreprises. */
        val GOLDEN_GAMMA: Long = 0x9E3779B97F4A7C15uL.toLong()
    }
}

/**
 * SplitMix64 (Steele, Lea, Flood, 2014) : un générateur de 64 bits dont chaque sortie ne dépend que de la graine et du
 * rang. Quelques lignes, aucune dépendance, et une suite que ses vecteurs de test figent.
 */
class SplitMix64(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** Un entier de 0 inclus à [bound] exclu. Le biais du modulo (2^63 mod bound) est négligeable pour les listes de quelques dizaines de noms. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "Une borne est positive : $bound" }
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** Un nombre de 0 inclus à 1 exclu, sur 53 bits. */
    fun nextUnit(): Double = (nextLong() ushr 11).toDouble() * UNIT

    private companion object {
        val GAMMA: Long = 0x9E3779B97F4A7C15uL.toLong()
        val MIX_1: Long = 0xBF58476D1CE4E5B9uL.toLong()
        val MIX_2: Long = 0x94D049BB133111EBuL.toLong()
        const val UNIT = 1.0 / (1L shl 53)
    }
}
