package com.silooo.core.site.pages

import com.silooo.core.definitions.Definitions
import com.silooo.core.definitions.FactoryDefinition
import com.silooo.core.definitions.StaffRole
import java.math.BigDecimal
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.ThScope
import kotlinx.html.caption
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul

private const val NBSP = ' '

/**
 * Les blocs `{{nom}}` du wiki, par sujet de page. Chacun est rendu depuis les définitions chargées : un nombre qui s'y lit
 * vient du JSON, jamais du code ni du Markdown. Un sujet sans bloc rend une liste vide, et `avecInsertions` refuse tout
 * écart entre les blocs prévus ici et ceux que le contenu appelle.
 */
internal fun wikiBlocks(subject: String, definitions: Definitions): Map<String, DIV.() -> Unit> = when (subject) {
    "usines" -> mapOf("usines" to { tableauDesUsines(definitions) })
    "silo-et-hors-ligne" -> mapOf("hors-ligne" to { horsLigne(definitions) })
    "personnel" -> mapOf(
        "agence" to { agence(definitions) },
        "postes" to { postes(definitions) },
        "raretes" to { tableauDesRaretes(definitions) },
    )
    "prestige" -> mapOf("epoques" to { epoques(definitions) })
    "incidents-et-electricite" -> mapOf(
        "incidents" to { tableauDesIncidents(definitions) },
        "electricite" to { electricite(definitions) },
    )
    "entreprise-partagee" -> mapOf("entreprise" to { entreprise(definitions) })
    else -> emptyMap()
}

/** Un nombre à la française : virgule décimale, pas de zéro inutile (2,5 ; 30). */
internal fun formatDecimal(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',')

private fun percent(value: Double): String = "${formatDecimal(value)}$NBSP%"

private fun hours(value: Double): String = if (value == 1.0) "1 heure" else "${formatDecimal(value)} heures"

private fun count(value: Int, singular: String, plural: String): String = if (value == 1) "1 $singular" else "$value $plural"

private fun era(value: Int): String = "Époque $value"

/**
 * Un tableau : la première colonne est l'en-tête de ligne. Sous 600 px de large, le CSS fait de chaque ligne une fiche et lit
 * l'étiquette de chaque cellule dans `data-label` (comme les offres de S2, le tableau ne dépasse jamais l'écran).
 */
private fun FlowContent.tableau(legende: String, headers: List<String>, rows: List<List<String>>) {
    table(classes = "tableau") {
        caption { +legende }
        thead {
            tr { for (header in headers) th(scope = ThScope.col) { +header } }
        }
        tbody {
            for (row in rows) {
                tr {
                    row.forEachIndexed { index, cell ->
                        if (index == 0) {
                            th(scope = ThScope.row) { attributes["data-label"] = headers[0]; +cell }
                        } else {
                            td { attributes["data-label"] = headers[index]; +cell }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Le nom que les joueurs lisent pour un produit qu'une usine consomme : celui de l'usine qui le produit, sinon
 * l'identifiant du produit tel qu'il est écrit.
 */
internal fun inputName(definitions: Definitions, product: String): String =
    definitions.factories.factories.firstOrNull { product in it.products }?.name ?: product

/** Les usines, triées par époque puis dans l'ordre du fichier : nom, époque, produit, entrées, électricité. Aucun prix. */
internal fun FlowContent.tableauDesUsines(definitions: Definitions) {
    val factories: List<FactoryDefinition> = definitions.factories.factories.sortedBy { it.era }
    tableau(
        "Les usines du jeu",
        listOf("Usine", "Époque", "Produit", "Consomme", "Électricité"),
        factories.map { factory ->
            listOf(
                factory.name,
                factory.era.toString(),
                factory.products.joinToString(", "),
                factory.inputs.map { inputName(definitions, it) }.distinct().joinToString(", ").ifEmpty { "Rien" },
                if (factory.needsElectricity) "Oui" else "Non",
            )
        },
    )
}

/** Les raretés : l'identifiant en minuscules (les définitions ne portent pas de nom affiché), la part des candidats, le bonus et le salaire. */
internal fun FlowContent.tableauDesRaretes(definitions: Definitions) {
    val personnel = definitions.personnel
    val employee = personnel.role(StaffRole.SALARIE)
    val leader = personnel.role(StaffRole.CHEF)
    tableau(
        "Les raretés du personnel",
        listOf("Rareté", "Part des candidats", "Bonus de production", "Salaire d'un salarié", "Salaire d'un chef"),
        personnel.rarities.map { (rarity, definition) ->
            listOf(
                rarity.name.lowercase(),
                percent(definition.sharePercent),
                percent(definition.productionBonusPercent),
                percent(definition.salaryPercent * employee.salaryFactor),
                percent(definition.salaryPercent * leader.salaryFactor),
            )
        },
    )
}

/** Les sortes d'incident : l'identifiant en minuscules et la part de sa production que l'usine garde tant que dure l'incident. */
internal fun FlowContent.tableauDesIncidents(definitions: Definitions) {
    tableau(
        "Les sortes d'incident",
        listOf("Sorte", "Production de l'usine pendant l'incident"),
        definitions.incidents.kinds.map { listOf(it.id.lowercase(), percent(it.slowdown * 100)) },
    )
}

internal fun FlowContent.agence(definitions: Definitions) {
    val agency = definitions.personnel.agency
    val leaders = agency.rolesPerSlot.count { it == StaffRole.CHEF }
    val employees = agency.rolesPerSlot.size - leaders
    val sentence = "L'agence propose ${count(agency.candidatesPerDay, "candidat", "candidats")} par jour : " +
        "${count(employees, "salarié", "salariés")} et ${count(leaders, "chef d'équipe", "chefs d'équipe")}."
    p { +sentence }
}

internal fun FlowContent.postes(definitions: Definitions) {
    val posts = definitions.personnel.posts
    ul {
        li { +"Une usine accueille ${count(posts.baseEmployees, "salarié", "salariés")} au départ, plus un par tranche de ${posts.levelsPerPost} niveaux." }
        li { +"Une usine accueille ${count(posts.leadersPerFactory, "chef d'équipe", "chefs d'équipe")} au plus." }
        li { +"Un chef d'équipe relève de ${hours(definitions.personnel.teamLeader.offlineCapBonusHours)} le plafond hors ligne de son usine." }
    }
}

internal fun FlowContent.horsLigne(definitions: Definitions) {
    val offline = definitions.economy.offline
    ul {
        li { +"Le plafond de départ est de ${hours(offline.capHours)}." }
        li { +"Hors ligne, une usine produit ${percent(offline.efficiency * 100)} de sa production en ligne." }
    }
}

/** Les époques, de la première à la dernière, avec les usines qui y arrivent. Le nombre d'époques est celui des usines. */
internal fun FlowContent.epoques(definitions: Definitions) {
    val factories = definitions.factories.factories
    ul {
        for (number in 1..factories.maxOf { it.era }) {
            li { +"${era(number)} : ${factories.filter { it.era == number }.joinToString(", ") { it.name }}." }
        }
    }
}

/** L'électricité : l'époque où elle commence, la Centrale qui la produit et les usines qui en demandent. */
internal fun FlowContent.electricite(definitions: Definitions) {
    val electricity = definitions.incidents.electricity
    val plant = definitions.factories.factories.first { it.id == electricity.powerPlantId }
    val consumers = definitions.factories.factories.filter { it.needsElectricity }
    p { +"Le courant est demandé à partir de l'${era(electricity.fromEra).lowercase()}. Il vient de la ${plant.name}." }
    p { +"Ces usines s'arrêtent sans ${plant.name} en marche : ${consumers.joinToString(", ") { it.name }}." }
}

/** L'entreprise partagée : le nombre de membres au plus et la durée d'une invitation. */
internal fun FlowContent.entreprise(definitions: Definitions) {
    val company = definitions.world.company
    ul {
        li { +"Une entreprise compte ${count(company.maxMembers, "membre", "membres")} au plus : le PDG et ${count(company.maxMembers - 1, "associé", "associés")}." }
        li { +"Une invitation reste valable ${hours(company.invitationHours.toDouble())}." }
    }
}
