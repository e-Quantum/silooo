package com.silooo.core.definitions

/** Un fichier de definitions/ manque, est abîmé, ou dit quelque chose d'impossible. Le message dit lequel et où. */
class DefinitionsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Tout ce que dit definitions/ : l'économie, les usines, la boutique. Chargé une fois au démarrage
 * ([DefinitionsLoader]) puis passé aux services ; jamais relu pendant que le service tourne.
 *
 * Une instance est toujours cohérente : le constructeur refuse les chiffres impossibles (croissance de
 * prix sous 1, usine de départ inconnue, seuils qui ne se suivent pas…). Le moteur peut donc s'appuyer
 * sur ces garanties sans les revérifier.
 */
class Definitions(
    val economy: EconomyDefinitions,
    val factories: FactoriesDefinitions,
    val shop: ShopDefinitions,
) {
    init {
        val problems = DefinitionsChecks.problems(economy, factories)
        if (problems.isNotEmpty()) {
            throw DefinitionsException("Définitions incohérentes :\n" + problems.joinToString("\n") { "  - $it" })
        }
    }
}

/** Les contrôles de sens que la forme du JSON ne garantit pas. Les messages citent la clé du fichier. */
internal object DefinitionsChecks {
    fun problems(economy: EconomyDefinitions, factories: FactoriesDefinitions): List<String> {
        val problems = mutableListOf<String>()
        fun check(ok: Boolean, message: () -> String) {
            if (!ok) problems += message()
        }

        val levels = economy.levels
        check(levels.priceGrowth.isFinite() && levels.priceGrowth > 1.0) { "economie.json : niveaux.croissancePrix doit être un nombre au-dessus de 1" }
        check(levels.doublingMilestones.fixed.all { it >= 1 }) { "economie.json : niveaux.paliersX2.fixes ne contient que des niveaux à partir de 1" }
        check(levels.doublingMilestones.thenEvery >= 1) { "economie.json : niveaux.paliersX2.ensuiteTous doit être au moins 1" }

        val offline = economy.offline
        check(offline.capHours.isFinite() && offline.capHours > 0.0) { "economie.json : horsLigne.plafondHeures doit être positif" }
        check(offline.efficiency > 0.0 && offline.efficiency <= 1.0) { "economie.json : horsLigne.efficacite doit être entre 0 (exclu) et 1" }

        val prestige = economy.prestige
        check(prestige.thresholds.isNotEmpty()) { "economie.json : prestige.seuils ne doit pas être vide" }
        prestige.thresholds.forEachIndexed { index, threshold ->
            check(threshold.course == index + 1) {
                "economie.json : prestige.seuils[$index].course vaut ${threshold.course}, attendu ${index + 1} (les courses se suivent depuis 1)"
            }
            check(threshold.donar.isFinite() && threshold.donar > 0.0) { "economie.json : prestige.seuils[$index].donar doit être positif" }
        }
        check(prestige.lingotsConversion.coefficient > 0.0) { "economie.json : prestige.conversionLingots.coefficient doit être positif" }
        check(prestige.lingotsConversion.referenceDivisor > 0.0) { "economie.json : prestige.conversionLingots.diviseurReference doit être positif" }
        check(prestige.productionPerStar.isFinite() && prestige.productionPerStar > 0.0) { "economie.json : prestige.productionParEtoile doit être positif" }
        check(prestige.bonusPerHeldLingot.isFinite() && prestige.bonusPerHeldLingot >= 0.0) { "economie.json : prestige.bonusParLingotDetenu ne peut pas être négatif" }
        check(prestige.courseStart.level >= 1) { "economie.json : prestige.departDeCourse.niveau doit être au moins 1" }

        val outside = economy.lingotsOutsidePrestige
        check(outside.welcomeMission >= 0) { "economie.json : lingotsHorsPrestige.missionBienvenue ne peut pas être négatif" }
        check(outside.averagePerPlayHour.isFinite() && outside.averagePerPlayHour >= 0.0) {
            "economie.json : lingotsHorsPrestige.moyenneParHeureDeJeu ne peut pas être négatif"
        }

        check(economy.city.zonePricesLingots.isNotEmpty() && economy.city.zonePricesLingots.all { it >= 1 }) {
            "economie.json : ville.zonesPrixLingots doit lister des prix d'au moins 1 Lingot"
        }

        check(factories.factories.isNotEmpty()) { "usines.json : usines ne doit pas être vide" }
        val ids = factories.factories.map { it.id }
        check(ids.size == ids.toSet().size) { "usines.json : deux usines ont le même id" }
        factories.factories.forEach { factory ->
            check(factory.era >= 1) { "usines.json : ${factory.id}.epoque doit être au moins 1" }
            check(factory.level1Price.isFinite() && factory.level1Price > 0.0) { "usines.json : ${factory.id}.prixNiveau1 doit être positif" }
            check(factory.productionPerLevel.isFinite() && factory.productionPerLevel > 0.0) {
                "usines.json : ${factory.id}.productionParNiveau doit être positif"
            }
        }
        check(prestige.courseStart.factoryId in ids) {
            "economie.json : prestige.departDeCourse.usine « ${prestige.courseStart.factoryId} » n'est pas une usine de usines.json"
        }
        return problems
    }
}
