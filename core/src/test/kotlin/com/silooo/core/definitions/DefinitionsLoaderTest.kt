package com.silooo.core.definitions

import com.silooo.core.TestDefinitions
import com.silooo.core.keyPaths
import com.silooo.core.without
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Le chargeur de definitions/ est strict : il lit les vrais fichiers du dépôt, et chaque défaut d'un
 * fichier (clé manquante, clé inconnue, mauvais type, fichier absent) l'arrête net avec un message qui
 * dit où. Aucun chiffre du jeu ici : les tests abîment les fichiers du dépôt, ils n'en recopient rien.
 */
class DefinitionsLoaderTest {
    private val economyText = TestDefinitions.text(DefinitionsLoader.ECONOMY_FILE)
    private val factoriesText = TestDefinitions.text(DefinitionsLoader.FACTORIES_FILE)
    private val shopText = TestDefinitions.text(DefinitionsLoader.SHOP_FILE)
    private val worldText = TestDefinitions.text(DefinitionsLoader.WORLD_FILE)
    private val missionsText = TestDefinitions.text(DefinitionsLoader.MISSIONS_FILE)
    private val personnelText = TestDefinitions.text(DefinitionsLoader.PERSONNEL_FILE)
    private val incidentsText = TestDefinitions.text(DefinitionsLoader.INCIDENTS_FILE)

    private fun parseJson(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun load(
        economy: String = economyText,
        factories: String = factoriesText,
        shop: String = shopText,
        world: String = worldText,
        missions: String = missionsText,
        personnel: String = personnelText,
        incidents: String = incidentsText,
    ): Definitions = DefinitionsLoader.parse(economy, factories, shop, world, missions, personnel, incidents)

    @Test
    @DisplayName("Les sept fichiers du dépôt se chargent, et le dossier donne le même résultat que les textes")
    fun loadsTheRepositoryFiles() {
        val fromDirectory = DefinitionsLoader.load(TestDefinitions.directory)
        val fromText = load()
        assertEquals(fromText.economy, fromDirectory.economy)
        assertEquals(fromText.factories, fromDirectory.factories)
        assertEquals(fromText.shop, fromDirectory.shop)
        assertEquals(fromText.world, fromDirectory.world)
        assertEquals(fromText.missions, fromDirectory.missions)
        assertEquals(fromText.personnel, fromDirectory.personnel)
        assertEquals(fromText.incidents, fromDirectory.incidents)
        assertTrue(fromDirectory.factories.factories.isNotEmpty())
        assertTrue(fromDirectory.economy.prestige.thresholds.isNotEmpty())
    }

    @Test
    @DisplayName("Retirer n'importe quelle clé de economie.json fait échouer le chargement")
    fun everyEconomyKeyIsRequired() {
        val root = parseJson(economyText)
        val paths = root.keyPaths()
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("economie.json sans ${path.joinToString(".")}") { load(economy = damaged) }
            assertTrue(error.message!!.contains("economie.json"), error.message)
        }
    }

    @Test
    @DisplayName("Retirer n'importe quelle clé de usines.json fait échouer le chargement")
    fun everyFactoryKeyIsRequired() {
        val root = parseJson(factoriesText)
        for (path in root.keyPaths()) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("usines.json sans ${path.joinToString(".")}") { load(factories = damaged) }
            assertTrue(error.message!!.contains("usines.json"), error.message)
        }
    }

    @Test
    @DisplayName("Retirer une clé obligatoire de boutique.json fait échouer le chargement")
    fun everyRequiredShopKeyIsRequired() {
        // Un abonnement porte soit un bonus de Donar, soit une réserve à un grade : ces trois clés sont facultatives.
        val optional = setOf("bonusDonarPourcent", "gemmesRendues", "reserveA")
        val root = parseJson(shopText)
        for (path in root.keyPaths().filter { it.last() !in optional }) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("boutique.json sans ${path.joinToString(".")}") { load(shop = damaged) }
            assertTrue(error.message!!.contains("boutique.json"), error.message)
        }
    }

    @Test
    @DisplayName("Retirer n'importe quelle clé de monde.json fait échouer le chargement")
    fun everyWorldKeyIsRequired() {
        val root = parseJson(worldText)
        val paths = root.keyPaths()
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("monde.json sans ${path.joinToString(".")}") { load(world = damaged) }
            assertTrue(error.message!!.contains("monde.json"), error.message)
        }
    }

    @Test
    @DisplayName("Retirer n'importe quelle clé de missions.json fait échouer le chargement")
    fun everyMissionKeyIsRequired() {
        val root = parseJson(missionsText)
        val paths = root.keyPaths()
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("missions.json sans ${path.joinToString(".")}") { load(missions = damaged) }
            assertTrue(error.message!!.contains("missions.json"), error.message)
        }
    }

    /** missions.json avec les clés de la première mission remplacées par [changes]. */
    private fun missionsWith(vararg changes: Pair<String, JsonElement>): String {
        val root = parseJson(missionsText)
        val first = root.getValue("missions").jsonArray.first().jsonObject
        val changed = JsonObject(first + changes)
        return JsonObject(root + ("missions" to JsonArray(listOf(changed)))).toString()
    }

    @Test
    @DisplayName("Un événement de mission que le service ne sait pas compter est refusé")
    fun unknownMissionEventIsRefused() {
        val message = assertThrows<DefinitionsException> { load(missions = missionsWith("evenement" to JsonPrimitive("pas-un-evenement"))) }.message!!
        assertTrue(message.contains("missions.json"), message)
    }

    @Test
    @DisplayName("Une récompense qui ne mène pas à un nombre entier de economie.json est refusée, en disant laquelle")
    fun unknownMissionRewardIsRefused() {
        // Un chemin qui n'existe pas, un chemin qui mène à un objet, un chemin qui mène à un nombre à virgule.
        for (path in listOf("lingotsHorsPrestige.inexistant", "lingotsHorsPrestige", "horsLigne.efficacite")) {
            val message = assertThrows<DefinitionsException>("récompense $path") { load(missions = missionsWith("recompense" to JsonPrimitive(path))) }.message!!
            assertTrue(message.contains("recompense") && message.contains(path), message)
        }
    }

    @Test
    @DisplayName("Une cible de mission sous 1 est refusée")
    fun missionTargetMustBePositive() {
        for (target in listOf(0, -3)) {
            val message = assertThrows<DefinitionsException>("cible $target") { load(missions = missionsWith("cible" to JsonPrimitive(target))) }.message!!
            assertTrue(message.contains("cible"), message)
        }
    }

    @Test
    @DisplayName("Un identifiant de mission qui n'est pas en kebab-case, ou trop long pour player_missions, est refusé")
    fun missionIdMustFitTheTable() {
        val tooLong = "a".repeat(DefinitionsChecks.MISSION_ID_MAX_LENGTH + 1)
        for (id in listOf("Bienvenue", "bienvenue_mairie", "-bienvenue", "", tooLong)) {
            val message = assertThrows<DefinitionsException>("id « $id »") { load(missions = missionsWith("id" to JsonPrimitive(id))) }.message!!
            assertTrue(message.contains("missions.json") && message.contains(".id"), message)
        }
    }

    @Test
    @DisplayName("Deux missions de même identifiant, ou aucune mission, sont refusées")
    fun missionCatalogueMustBeUniqueAndNotEmpty() {
        val root = parseJson(missionsText)
        val first = root.getValue("missions").jsonArray.first()

        val twice = JsonObject(root + ("missions" to JsonArray(listOf(first, first)))).toString()
        val duplicate = assertThrows<DefinitionsException> { load(missions = twice) }.message!!
        assertTrue(duplicate.contains("même id"), duplicate)

        val none = JsonObject(root + ("missions" to JsonArray(emptyList()))).toString()
        val empty = assertThrows<DefinitionsException> { load(missions = none) }.message!!
        assertTrue(empty.contains("missions.json"), empty)
    }

    @Test
    @DisplayName("Retirer n'importe quelle clé de personnel.json fait échouer le chargement")
    fun everyPersonnelKeyIsRequired() {
        val root = parseJson(personnelText)
        val paths = root.keyPaths()
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("personnel.json sans ${path.joinToString(".")}") { load(personnel = damaged) }
            assertTrue(error.message!!.contains("personnel.json"), error.message)
        }
    }

    /** personnel.json avec la valeur au chemin [path] remplacée par [value]. */
    private fun personnelWith(path: List<String>, value: JsonElement): String {
        fun replace(node: JsonObject, rest: List<String>): JsonObject =
            JsonObject(node + (rest.first() to if (rest.size == 1) value else replace(node.getValue(rest.first()).jsonObject, rest.drop(1))))
        return replace(parseJson(personnelText), path).toString()
    }

    private fun strings(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    @Test
    @DisplayName("Un personnel qui n'a pas de sens est refusé : parts qui ne font pas 100, salaire au-dessus du bonus, places sans rôle, trop peu de noms, apparence mal écrite")
    fun personnelMustMakeSense() {
        fun refused(path: List<String>, value: JsonElement, expected: String) {
            val message = assertThrows<DefinitionsException>("${path.joinToString(".")} = $value") { load(personnel = personnelWith(path, value)) }.message!!
            assertTrue(message.contains("personnel.json") && message.contains(expected), message)
        }

        refused(listOf("raretes", "COMMUN", "repartitionPourcent"), JsonPrimitive(74), "doivent faire 100")
        refused(listOf("raretes", "COMMUN", "salairePourcent"), JsonPrimitive(10), "salaire")
        refused(listOf("raretes", "RARE", "coutHeuresDeRevenu"), JsonPrimitive(0), "coutHeuresDeRevenu")
        refused(listOf("agence", "candidatsParJour"), JsonPrimitive(6), "candidatsParJour")
        refused(listOf("agence", "rolesParPlace"), strings("SALARIE", "CHEF"), "rolesParPlace")
        refused(listOf("agence", "noms"), strings("Adèle", "Basile"), "agence.noms")
        refused(listOf("agence", "noms"), strings("A", "A", "B", "C", "D", "E"), "agence.noms")
        refused(listOf("agence", "apparences", "CHEF"), strings("Chef Veste"), "agence.apparences.CHEF")
        refused(listOf("agence", "apparences", "SALARIE"), strings(), "agence.apparences.SALARIE")
        refused(listOf("postes", "niveauxParPoste"), JsonPrimitive(0), "niveauxParPoste")
        refused(listOf("chefEquipe", "plafondHorsLigneBonusHeures"), JsonPrimitive(-1), "plafondHorsLigneBonusHeures")
        // Un rôle ou une rareté que le service ne connaît pas est refusé dès la lecture.
        val unknown = JsonObject(parseJson(personnelText).getValue("roles").jsonObject + ("DIRECTEUR" to parseJson(personnelText).getValue("roles").jsonObject.getValue("CHEF")))
        refused(listOf("roles"), unknown, "personnel.json")
    }

    @Test
    @DisplayName("Les trois raretés, les deux rôles et leurs chiffres sont lus comme ils sont écrits")
    fun personnelIsReadAsWritten() {
        val loaded = load().personnel
        assertEquals(setOf(StaffRarity.COMMUN, StaffRarity.RARE, StaffRarity.EPIQUE), loaded.rarities.keys)
        assertEquals(setOf(StaffRole.SALARIE, StaffRole.CHEF), loaded.roles.keys)
        assertEquals(loaded.agency.candidatesPerDay, loaded.agency.rolesPerSlot.size)
        val root = parseJson(personnelText)
        assertEquals(
            root.getValue("raretes").jsonObject.getValue("RARE").jsonObject.getValue("bonusProductionPourcent").toString().toDouble(),
            loaded.rarity(StaffRarity.RARE).productionBonusPercent,
        )
    }

    @Test
    @DisplayName("Retirer n'importe quelle clé de incidents.json fait échouer le chargement")
    fun everyIncidentsKeyIsRequired() {
        val root = parseJson(incidentsText)
        val paths = root.keyPaths()
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("incidents.json sans ${path.joinToString(".")}") { load(incidents = damaged) }
            assertTrue(error.message!!.contains("incidents.json"), error.message)
        }
    }

    /** incidents.json avec la valeur au chemin [path] remplacée par [value]. */
    private fun incidentsWith(path: List<String>, value: JsonElement): String {
        fun replace(node: JsonObject, rest: List<String>): JsonObject =
            JsonObject(node + (rest.first() to if (rest.size == 1) value else replace(node.getValue(rest.first()).jsonObject, rest.drop(1))))
        return replace(parseJson(incidentsText), path).toString()
    }

    /** incidents.json dont la première sorte a la clé [key] remplacée par [value]. */
    private fun firstKindWith(key: String, value: JsonElement): String {
        val root = parseJson(incidentsText)
        val kinds = root.getValue("sortes").jsonArray
        val changed = JsonObject(kinds.first().jsonObject + (key to value))
        return JsonObject(root + ("sortes" to JsonArray(listOf<JsonElement>(changed) + kinds.drop(1)))).toString()
    }

    @Test
    @DisplayName("Des incidents qui n'ont pas de sens sont refusés : créneau qui ne divise pas le jour, chance hors bornes, ralentissement nul ou trop précis, Centrale inconnue ou dépendante du courant")
    fun incidentsMustMakeSense() {
        fun refused(text: String, expected: String) {
            val message = assertThrows<DefinitionsException>(expected) { load(incidents = text) }.message!!
            assertTrue(message.contains("incidents.json") && message.contains(expected), message)
        }

        refused(incidentsWith(listOf("frequence", "creneauMinutes"), JsonPrimitive(25)), "frequence.creneauMinutes")
        refused(incidentsWith(listOf("frequence", "creneauMinutes"), JsonPrimitive(0)), "frequence.creneauMinutes")
        refused(incidentsWith(listOf("frequence", "chancePourcent"), JsonPrimitive(0)), "frequence.chancePourcent")
        refused(incidentsWith(listOf("frequence", "chancePourcent"), JsonPrimitive(101)), "frequence.chancePourcent")
        refused(incidentsWith(listOf("sortes"), JsonArray(emptyList())), "sortes ne doit pas être vide")
        refused(firstKindWith("ralentissement", JsonPrimitive(0)), ".ralentissement")
        refused(firstKindWith("ralentissement", JsonPrimitive(1)), ".ralentissement")
        refused(firstKindWith("ralentissement", JsonPrimitive(0.555)), "plus de deux décimales")
        refused(firstKindWith("poids", JsonPrimitive(0)), ".poids")
        refused(firstKindWith("id", JsonPrimitive("Incendie")), "kebab-case")
        refused(firstKindWith("id", JsonPrimitive("une-sorte-bien-trop-longue")), "16 caractères")
        refused(incidentsWith(listOf("recompense", "minutesDeRevenu"), JsonPrimitive(0)), "recompense.minutesDeRevenu")
        refused(incidentsWith(listOf("electricite", "aPartirDeEpoque"), JsonPrimitive(0)), "electricite.aPartirDeEpoque")
        refused(incidentsWith(listOf("electricite", "usineCentrale"), JsonPrimitive("usine-fantome")), "n'est pas une usine de usines.json")
        refused(incidentsWith(listOf("electricite", "usineCentrale"), JsonPrimitive("verrerie")), "dépend elle-même de l'électricité")

        // Deux sortes qui portent le même identifiant.
        val kinds = parseJson(incidentsText).getValue("sortes").jsonArray
        val twice = JsonArray(listOf(kinds.first(), kinds.first()))
        refused(incidentsWith(listOf("sortes"), twice), "deux sortes ont le même id")
    }

    @Test
    @DisplayName("Les sortes d'incident, la cadence, la récompense et l'électricité sont lues comme elles sont écrites")
    fun incidentsAreReadAsWritten() {
        val loaded = load().incidents
        val root = parseJson(incidentsText)
        assertEquals(root.getValue("sortes").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }, loaded.kinds.map { it.id })
        assertEquals(
            root.getValue("frequence").jsonObject.getValue("chancePourcent").jsonPrimitive.double,
            loaded.frequency.chancePercent,
        )
        assertEquals(
            root.getValue("electricite").jsonObject.getValue("usineCentrale").jsonPrimitive.content,
            loaded.electricity.powerPlantId,
        )
        assertTrue(loaded.kinds.all { it.slowdown > 0.0 && it.slowdown < 1.0 })
    }

    @Test
    @DisplayName("La récompense d'une mission est lue dans economie.json : un autre chiffre là-bas change les Lingots, sans toucher à missions.json")
    fun missionRewardFollowsEconomy() {
        val loaded = load()
        val reward = loaded.missions.missions.first().reward
        assertEquals(loaded.economy.lingotsOutsidePrestige.welcomeMission, loaded.missionRewards.getValue(loaded.missions.missions.first().id), reward)

        val root = parseJson(economyText)
        val outside = root.getValue("lingotsHorsPrestige").jsonObject
        val changed = JsonObject(root + ("lingotsHorsPrestige" to JsonObject(outside + ("missionBienvenue" to JsonPrimitive(7777)))))
        assertEquals(7777L, load(economy = changed.toString()).missionRewards.getValue(loaded.missions.missions.first().id))
    }

    @Test
    @DisplayName("La commande du jour et la trouvaille sont lues dans economie.json : un autre chiffre là-bas les change, sans toucher au code")
    fun dailyOrderAndFindingFollowEconomy() {
        val root = parseJson(economyText)
        val outside = root.getValue("lingotsHorsPrestige").jsonObject
        val order = root.getValue("commandeDuJour").jsonObject
        val changed = JsonObject(
            root + mapOf(
                "lingotsHorsPrestige" to JsonObject(outside + mapOf("commandeDuJour" to JsonPrimitive(41), "trouvaille" to JsonPrimitive(43))),
                "commandeDuJour" to JsonObject(order + mapOf("objectifUnites" to JsonPrimitive(37), "minutesDeProductionParUnite" to JsonPrimitive(2.5))),
            ),
        )

        val economy = load(economy = changed.toString()).economy
        assertEquals(41L, economy.lingotsOutsidePrestige.dailyOrder)
        assertEquals(43L, economy.lingotsOutsidePrestige.finding)
        assertEquals(37L, economy.dailyOrder.targetUnits)
        assertEquals(2.5, economy.dailyOrder.minutesOfIncomePerUnit)
    }

    @Test
    @DisplayName("Un objectif de commande sous 1, un prix d'unité nul ou une prime négative est refusé, en disant laquelle")
    fun dailyOrderFiguresMustMakeSense() {
        val root = parseJson(economyText)
        val order = root.getValue("commandeDuJour").jsonObject
        val outside = root.getValue("lingotsHorsPrestige").jsonObject
        fun withOrder(key: String, value: JsonElement) = JsonObject(root + ("commandeDuJour" to JsonObject(order + (key to value)))).toString()
        fun withOutside(key: String, value: JsonElement) = JsonObject(root + ("lingotsHorsPrestige" to JsonObject(outside + (key to value)))).toString()

        val cases = listOf(
            Triple("commandeDuJour.objectifUnites", withOrder("objectifUnites", JsonPrimitive(0)), "objectifUnites"),
            Triple("commandeDuJour.minutesDeProductionParUnite", withOrder("minutesDeProductionParUnite", JsonPrimitive(0)), "minutesDeProductionParUnite"),
            Triple("lingotsHorsPrestige.commandeDuJour", withOutside("commandeDuJour", JsonPrimitive(-1)), "commandeDuJour"),
            Triple("lingotsHorsPrestige.trouvaille", withOutside("trouvaille", JsonPrimitive(-1)), "trouvaille"),
        )
        for ((what, damaged, word) in cases) {
            val message = assertThrows<DefinitionsException>(what) { load(economy = damaged) }.message!!
            assertTrue(message.contains(word), message)
        }
    }

    @Test
    @DisplayName("Les ressources du jeu sont les produits de usines.json, une fois chacun, dans l'ordre du fichier")
    fun resourcesAreTheDistinctProducts() {
        val loaded = load()
        val expected = loaded.factories.factories.flatMap { it.products }.distinct()
        assertEquals(expected, loaded.resources)
        assertTrue(loaded.resources.isNotEmpty())
    }

    @Test
    @DisplayName("Une grille de monde sans écart ou sans case par rangée est refusée")
    fun worldGridMustHaveRoom() {
        val root = parseJson(worldText)
        val archipelago = root.getValue("archipel").jsonObject
        for (key in listOf("ecartEntreCases", "casesParRangee")) {
            val damaged = JsonObject(root + ("archipel" to JsonObject(archipelago + (key to JsonPrimitive(0)))))
            val message = assertThrows<DefinitionsException>("monde.json avec $key à 0") { load(world = damaged.toString()) }.message!!
            assertTrue(message.contains("archipel.$key"), message)
        }
    }

    @Test
    @DisplayName("Un facteur de seuil qui ne dépasse pas 1 est refusé")
    fun nextThresholdFactorMustExceedOne() {
        val root = parseJson(economyText)
        val prestige = root.getValue("prestige").jsonObject
        for (factor in listOf(1, 0)) {
            val damaged = JsonObject(root + ("prestige" to JsonObject(prestige + ("facteurSeuilSuivant" to JsonPrimitive(factor)))))
            val message = assertThrows<DefinitionsException>("facteurSeuilSuivant à $factor") { load(economy = damaged.toString()) }.message!!
            assertTrue(message.contains("prestige.facteurSeuilSuivant"), message)
        }
    }

    @Test
    @DisplayName("Le message d'une clé manquante cite la clé et le chemin JSON")
    fun missingKeyMessageNamesTheKey() {
        val damaged = parseJson(economyText).without(listOf("niveaux", "croissancePrix")).toString()
        val message = assertThrows<DefinitionsException> { load(economy = damaged) }.message!!
        assertTrue(message.contains("croissancePrix"), message)
        assertTrue(message.contains("$.niveaux"), message)
    }

    @Test
    @DisplayName("Une clé inconnue est refusée : le fichier et le code disent la même chose")
    fun unknownKeyIsRefused() {
        val root = parseJson(economyText)
        val extra = JsonObject(root + ("cleEnTrop" to JsonPrimitive(1)))
        val message = assertThrows<DefinitionsException> { load(economy = extra.toString()) }.message!!
        assertTrue(message.contains("cleEnTrop"), message)
    }

    @Test
    @DisplayName("Un nombre à la place d'un texte, et du texte à la place d'un nombre, sont refusés")
    fun wrongTypeIsRefused() {
        val root = parseJson(economyText)
        val levels = root.getValue("niveaux").jsonObject
        val damaged = JsonObject(root + ("niveaux" to JsonObject(levels + ("croissancePrix" to JsonPrimitive("beaucoup")))))
        assertThrows<DefinitionsException> { load(economy = damaged.toString()) }
    }

    @Test
    @DisplayName("Un JSON cassé est refusé avec le nom du fichier")
    fun brokenJsonIsRefused() {
        val message = assertThrows<DefinitionsException> { load(factories = "{ pas du json") }.message!!
        assertTrue(message.contains("usines.json"), message)
    }

    @Test
    @DisplayName("Les problèmes de plusieurs fichiers sont rendus d'un coup")
    fun problemsOfSeveralFilesAreReportedTogether() {
        val message = assertThrows<DefinitionsException> { load(economy = "{}", factories = "{}") }.message!!
        assertTrue(message.contains("economie.json") && message.contains("usines.json"), message)
    }

    @Test
    @DisplayName("Une usine de départ qui n'existe pas dans usines.json est refusée")
    fun unknownStartingFactoryIsRefused() {
        val root = parseJson(economyText)
        val prestige = root.getValue("prestige").jsonObject
        val start = buildJsonObject {
            put("usine", "usine-qui-n-existe-pas")
            put("niveau", 1)
        }
        val damaged = JsonObject(root + ("prestige" to JsonObject(prestige + ("departDeCourse" to start))))
        val message = assertThrows<DefinitionsException> { load(economy = damaged.toString()) }.message!!
        assertTrue(message.contains("usine-qui-n-existe-pas"), message)
    }

    @Test
    @DisplayName("Une croissance de prix qui ne dépasse pas 1 est refusée")
    fun priceGrowthMustExceedOne() {
        val root = parseJson(economyText)
        val levels = root.getValue("niveaux").jsonObject
        val damaged = JsonObject(root + ("niveaux" to JsonObject(levels + ("croissancePrix" to JsonPrimitive(1)))))
        val message = assertThrows<DefinitionsException> { load(economy = damaged.toString()) }.message!!
        assertTrue(message.contains("croissancePrix"), message)
    }

    @Test
    @DisplayName("Des seuils de prestige qui ne se suivent pas depuis la course 1 sont refusés")
    fun thresholdsMustFollowCourseNumbers() {
        val root = parseJson(economyText)
        val prestige = root.getValue("prestige").jsonObject
        val reversed = JsonArray(prestige.getValue("seuils").jsonArray.reversed())
        val damaged = JsonObject(root + ("prestige" to JsonObject(prestige + ("seuils" to reversed))))
        val message = assertThrows<DefinitionsException> { load(economy = damaged.toString()) }.message!!
        assertTrue(message.contains("prestige.seuils"), message)
    }

    @Test
    @DisplayName("Un fichier absent ou un dossier absent sont refusés en disant lequel")
    fun missingFileOrDirectoryIsRefused(@TempDir temporary: Path) {
        val noDirectory = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary.resolve("nulle-part")) }
        assertTrue(noDirectory.message!!.contains("nulle-part"), noDirectory.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.ECONOMY_FILE), economyText)
        Files.writeString(temporary.resolve(DefinitionsLoader.FACTORIES_FILE), factoriesText)
        val noShop = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary) }
        assertTrue(noShop.message!!.contains(DefinitionsLoader.SHOP_FILE), noShop.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.SHOP_FILE), shopText)
        val noWorld = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary) }
        assertTrue(noWorld.message!!.contains(DefinitionsLoader.WORLD_FILE), noWorld.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.WORLD_FILE), worldText)
        val noMissions = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary) }
        assertTrue(noMissions.message!!.contains(DefinitionsLoader.MISSIONS_FILE), noMissions.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.MISSIONS_FILE), missionsText)
        val noPersonnel = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary) }
        assertTrue(noPersonnel.message!!.contains(DefinitionsLoader.PERSONNEL_FILE), noPersonnel.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.PERSONNEL_FILE), personnelText)
        val noIncidents = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary) }
        assertTrue(noIncidents.message!!.contains(DefinitionsLoader.INCIDENTS_FILE), noIncidents.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.INCIDENTS_FILE), incidentsText)
        assertEquals(load().economy, DefinitionsLoader.load(temporary).economy)
    }
}
