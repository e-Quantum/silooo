package com.silooo.core.incident

import com.silooo.core.definitions.IncidentKindDefinition
import com.silooo.core.definitions.IncidentsDefinitions
import com.silooo.core.staff.SplitMix64
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Un créneau du tirage : la tranche de `frequence.creneauMinutes` minutes (UTC) qui contient un instant. [ordinal] est son
 * rang dans la journée [day], à partir de 0 ; [start] est son premier instant.
 */
data class IncidentSlot(val start: Instant, val day: LocalDate, val ordinal: Int)

/**
 * Ce que le tirage d'un créneau décide, avant de regarder les usines : la sorte de l'incident et un nombre qui, plus tard,
 * désignera l'usine ([IncidentDraw.factoryFor]).
 */
data class IncidentRoll(val kind: IncidentKindDefinition, val factoryPick: Long)

/**
 * Le tirage des incidents d'usine. Il est entièrement déterminé par l'entreprise, le jour (UTC) et le rang du créneau dans
 * ce jour : le même créneau donne le même résultat à chaque appel, sur toutes les machines, sans rien écrire en base. Un test
 * le rejoue à l'identique, et il n'y a aucune autre entrée : ni relance, ni tirage payant, ni moyen d'acheter un autre
 * résultat. Un incident ne coûte jamais rien au joueur : il ralentit une usine jusqu'à la réparation.
 *
 * Le temps se découpe en créneaux de `frequence.creneauMinutes` minutes. Pour chacun, dans cet ordre :
 *  1. la chance : `frequence.chancePourcent` de chances qu'un incident survienne (aucune autre suite sinon) ;
 *  2. la sorte, parmi `sortes`, selon leur `poids` ;
 *  3. un nombre brut, qui choisira l'usine parmi celles que l'incident peut toucher à cet instant ([factoryFor]).
 *
 * Les deux premiers tirages ne dépendent d'aucune usine : que l'entreprise gagne ou perde une usine ne change pas qu'un
 * créneau tombe ou non. Le générateur est [SplitMix64], le même que l'agence d'emploi : sa suite est figée par son algorithme.
 * Rien ici n'est un chiffre du jeu : tout vient de [incidents].
 */
class IncidentDraw(private val incidents: IncidentsDefinitions) {
    private val slotSeconds: Long = incidents.frequency.slotMinutes * SECONDS_PER_MINUTE
    private val totalWeight: Int = incidents.kinds.sumOf { it.weight }

    /** Le créneau qui contient [at]. */
    fun slotAt(at: Instant): IncidentSlot {
        val start = Instant.ofEpochSecond(Math.floorDiv(at.epochSecond, slotSeconds) * slotSeconds)
        val day = LocalDate.ofInstant(start, ZoneOffset.UTC)
        val secondOfDay = start.epochSecond - day.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return IncidentSlot(start, day, (secondOfDay / slotSeconds).toInt())
    }

    /** Ce que le créneau [slot] de l'entreprise [companyId] décide, ou `null` quand aucun incident n'y survient. */
    fun roll(companyId: Long, slot: IncidentSlot): IncidentRoll? {
        val random = randomFor(companyId, slot)
        if (random.nextUnit() * PERCENT >= incidents.frequency.chancePercent) return null
        var pick = random.nextInt(totalWeight)
        val kind = incidents.kinds.first { kind ->
            pick -= kind.weight
            pick < 0
        }
        return IncidentRoll(kind, random.nextLong())
    }

    /**
     * L'usine que [roll] frappe parmi [eligible], ou `null` s'il n'y en a aucune. L'appelant donne les usines dans l'ordre
     * croissant de leur identifiant : le même tirage désigne alors la même usine à chaque appel.
     */
    fun factoryFor(roll: IncidentRoll, eligible: List<String>): String? =
        if (eligible.isEmpty()) null else eligible[Math.floorMod(roll.factoryPick, eligible.size.toLong()).toInt()]

    /**
     * Le générateur du créneau. La graine passe par une chaîne (entreprise, puis jour, puis rang) plutôt que par une somme :
     * deux triplets différents ne se rejoignent pas par hasard d'arithmétique.
     */
    private fun randomFor(companyId: Long, slot: IncidentSlot): SplitMix64 {
        val byCompany = SplitMix64(companyId).nextLong()
        val byDay = SplitMix64(byCompany xor slot.day.toEpochDay()).nextLong()
        return SplitMix64(byDay + slot.ordinal)
    }

    private companion object {
        /** Une minute : constante de la nature. */
        const val SECONDS_PER_MINUTE = 60L

        /** Un pourcent est un centième : constante de la nature. */
        const val PERCENT = 100.0
    }
}
