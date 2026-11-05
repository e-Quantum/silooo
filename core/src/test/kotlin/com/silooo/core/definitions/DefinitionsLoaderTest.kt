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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
    private val cityText = TestDefinitions.text(DefinitionsLoader.CITY_FILE)
    private val voteText = TestDefinitions.text(DefinitionsLoader.VOTE_FILE)
    private val passText = TestDefinitions.text(DefinitionsLoader.PASS_FILE)

    private fun parseJson(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun load(
        economy: String = economyText,
        factories: String = factoriesText,
        shop: String = shopText,
        world: String = worldText,
        missions: String = missionsText,
        personnel: String = personnelText,
        incidents: String = incidentsText,
        city: String = cityText,
        vote: String = voteText,
        pass: String = passText,
    ): Definitions = DefinitionsLoader.parse(economy, factories, shop, world, missions, personnel, incidents, city, vote, pass)

    @Test
    @DisplayName("Les dix fichiers du dépôt se chargent, et le dossier donne le même résultat que les textes")
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
        assertEquals(fromText.city, fromDirectory.city)
        assertEquals(fromText.vote, fromDirectory.vote)
        assertEquals(fromText.pass, fromDirectory.pass)
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
        refused(listOf("raretes", "RARE", "nom"), JsonPrimitive(" "), "raretes.RARE.nom doit avoir de 1 à")
        refused(listOf("raretes", "RARE", "nom"), JsonPrimitive("x".repeat(DefinitionsChecks.RARITY_NAME_MAX_LENGTH + 1)), "raretes.RARE.nom doit avoir de 1 à")
        refused(listOf("raretes", "RARE", "nom"), parseJson(personnelText).getValue("raretes").jsonObject.getValue("COMMUN").jsonObject.getValue("nom"), "deux raretés portent le même nom")
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
    @DisplayName("Une entreprise sans membre possible, ou une invitation qui n'est valable aucune heure, est refusée")
    fun companyMustHaveRoom() {
        val root = parseJson(worldText)
        val company = root.getValue("entreprise").jsonObject
        for (key in listOf("membresMax", "invitationHeures")) {
            val damaged = JsonObject(root + ("entreprise" to JsonObject(company + (key to JsonPrimitive(0)))))
            val message = assertThrows<DefinitionsException>("monde.json avec $key à 0") { load(world = damaged.toString()) }.message!!
            assertTrue(message.contains("entreprise.$key"), message)
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
        val noCity = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary) }
        assertTrue(noCity.message!!.contains(DefinitionsLoader.CITY_FILE), noCity.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.CITY_FILE), cityText)
        val noVote = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary) }
        assertTrue(noVote.message!!.contains(DefinitionsLoader.VOTE_FILE), noVote.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.VOTE_FILE), voteText)
        val noPass = assertThrows<DefinitionsException> { DefinitionsLoader.load(temporary) }
        assertTrue(noPass.message!!.contains(DefinitionsLoader.PASS_FILE), noPass.message)

        Files.writeString(temporary.resolve(DefinitionsLoader.PASS_FILE), passText)
        assertEquals(load().economy, DefinitionsLoader.load(temporary).economy)
    }

    @Test
    @DisplayName("Retirer n'importe quelle clé de ville.json fait échouer le chargement")
    fun everyCityKeyIsRequired() {
        val root = parseJson(cityText)
        val paths = root.keyPaths()
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("ville.json sans ${path.joinToString(".")}") { load(city = damaged) }
            assertTrue(error.message!!.contains("ville.json"), error.message)
        }
    }

    @Test
    @DisplayName("Retirer n'importe quelle clé de vote.json fait échouer le chargement")
    fun everyVoteKeyIsRequired() {
        val root = parseJson(voteText)
        val paths = root.keyPaths()
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("vote.json sans ${path.joinToString(".")}") { load(vote = damaged) }
            assertTrue(error.message!!.contains("vote.json"), error.message)
        }
    }

    /** vote.json avec la valeur au chemin [path] remplacée par [value]. */
    private fun voteWith(path: List<String>, value: JsonElement): String {
        fun replace(node: JsonObject, rest: List<String>): JsonObject =
            JsonObject(node + (rest.first() to if (rest.size == 1) value else replace(node.getValue(rest.first()).jsonObject, rest.drop(1))))
        return replace(parseJson(voteText), path).toString()
    }

    @Test
    @DisplayName("Des votes qui n'ont pas de sens sont refusés : récompense, série, Vote party, sites")
    fun voteMustMakeSense() {
        fun refused(text: String, expected: String) {
            val message = assertThrows<DefinitionsException>(expected) { load(vote = text) }.message!!
            assertTrue(message.contains("vote.json") && message.contains(expected), message)
        }
        refused(voteWith(listOf("recompense", "lingotsParVote"), JsonPrimitive(0)), "recompense.lingotsParVote doit être au moins 1")
        refused(voteWith(listOf("serie", "jours"), JsonPrimitive(1)), "serie.jours doit être au moins 2")
        refused(voteWith(listOf("serie", "lingotsEnPlus"), JsonPrimitive(0)), "serie.lingotsEnPlus doit être au moins 1")
        refused(voteWith(listOf("voteParty", "votesParJour"), JsonPrimitive(0)), "voteParty.votesParJour doit être au moins 1")
        refused(voteWith(listOf("voteParty", "lingotsParJoueur"), JsonPrimitive(0)), "voteParty.lingotsParJoueur doit être au moins 1")

        val site = { id: String, name: String -> JsonObject(mapOf("id" to JsonPrimitive(id), "nom" to JsonPrimitive(name))) }
        refused(voteWith(listOf("sites"), JsonArray(emptyList())), "sites ne doit pas être vide")
        refused(voteWith(listOf("sites"), JsonArray(listOf(site("hubyy", "Hubyy"), site("hubyy", "Autre")))), "deux sites ont le même id")
        refused(voteWith(listOf("sites"), JsonArray(listOf(site("Hubyy", "Hubyy")))), "doit être en kebab-case")
        refused(voteWith(listOf("sites"), JsonArray(listOf(site("hubyy", " ")))), "nom doit avoir de 1 à")
    }

    @Test
    @DisplayName("Le site d'un vote est l'identifiant du site connu (id ou nom, casse ignorée), sinon le nom tel qu'annoncé")
    fun voteSiteIsTheKnownId() {
        val vote = load().vote
        val known = vote.sites.first()
        assertEquals(known.id, vote.siteFor(known.name))
        assertEquals(known.id, vote.siteFor(known.id.uppercase()))
        assertEquals(known.id, vote.siteFor("  ${known.name.lowercase()} "))
        assertEquals("autre-site", vote.siteFor("autre-site"))
    }

    @Test
    @DisplayName("Retirer une clé obligatoire de pass.json fait échouer le chargement")
    fun everyRequiredPassKeyIsRequired() {
        // Un palier porte une récompense par piste, et au moins une : ces deux clés sont facultatives une à une.
        val optional = { path: List<String> -> path.size == 3 && path[0] == "paliers" && path.last() in setOf("gratuit", "premium") }
        val root = parseJson(passText)
        val paths = root.keyPaths().filterNot(optional)
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val damaged = root.without(path).toString()
            val error = assertThrows<DefinitionsException>("pass.json sans ${path.joinToString(".")}") { load(pass = damaged) }
            assertTrue(error.message!!.contains("pass.json"), error.message)
        }
    }

    /** pass.json avec la valeur au chemin [path] (des clés, et des positions pour les listes) remplacée par [value]. */
    private fun passWith(path: List<Any>, value: JsonElement): String {
        fun replace(node: JsonElement, rest: List<Any>): JsonElement {
            if (rest.isEmpty()) return value
            val head = rest.first()
            return when (node) {
                is JsonObject -> JsonObject(node + ((head as String) to replace(node.getValue(head), rest.drop(1))))
                is JsonArray -> JsonArray(node.mapIndexed { i, e -> if (i == head) replace(e, rest.drop(1)) else e })
                else -> error("Chemin impossible : $path")
            }
        }
        return replace(parseJson(passText), path).toString()
    }

    @Test
    @DisplayName("Un pass qui n'a pas de sens est refusé : saison, prix, points, paliers qui ne se suivent pas, piste premium vide ou en double")
    fun passMustMakeSense() {
        fun refused(text: String, expected: String) {
            val message = assertThrows<DefinitionsException>(expected) { load(pass = text) }.message!!
            assertTrue(message.contains("pass.json") && message.contains(expected), message)
        }
        val tiers = parseJson(passText).getValue("paliers").jsonArray

        refused(passWith(listOf("saison", "id"), JsonPrimitive("Saison-1")), "saison.id")
        refused(passWith(listOf("saison", "id"), JsonPrimitive("une-saison-beaucoup-trop-longue")), "saison.id")
        refused(passWith(listOf("saison", "nom"), JsonPrimitive(" ")), "saison.nom doit avoir de 1 à")
        refused(passWith(listOf("saison", "debut"), JsonPrimitive("2026-13-01")), "saison.debut")
        refused(passWith(listOf("saison", "fin"), JsonPrimitive("demain")), "saison.fin")
        refused(passWith(listOf("saison", "fin"), parseJson(passText).getValue("saison").jsonObject.getValue("debut").let { day ->
            JsonPrimitive(java.time.LocalDate.parse(day.jsonPrimitive.content).minusDays(1).toString())
        }), "est avant saison.debut")
        refused(passWith(listOf("premium", "gemmes"), JsonPrimitive(0)), "premium.gemmes doit être au moins 1")
        refused(passWith(listOf("points", "parPrestige"), JsonPrimitive(-1)), "ne peuvent pas être négatifs")
        refused(
            passWith(listOf("points"), buildJsonObject { put("parPrestige", 0); put("parCommande", 0); put("parMission", 0) }),
            "au moins une action doit rapporter des points",
        )

        refused(passWith(listOf("paliers"), JsonArray(emptyList())), "paliers ne doit pas être vide")
        refused(passWith(listOf("paliers", 2, "palier"), JsonPrimitive(7)), "les numéros se suivent")
        refused(passWith(listOf("paliers", 0, "points"), JsonPrimitive(0)), "paliers[0].points doit être au moins 1")
        refused(
            passWith(listOf("paliers", 3, "points"), tiers[2].jsonObject.getValue("points")),
            "doit dépasser celui du palier précédent",
        )
        refused(passWith(listOf("paliers", 1, "gratuit", "lingots"), JsonPrimitive(0)), "gratuit.lingots doit être au moins 1")
        refused(passWith(listOf("paliers", 0, "premium", "cosmetique"), JsonPrimitive("Fanion")), "premium.cosmetique")
        refused(
            passWith(listOf("paliers", 2, "premium", "cosmetique"), tiers[0].jsonObject.getValue("premium").jsonObject.getValue("cosmetique")),
            "un cosmétique est donné à deux paliers",
        )
        refused(
            passWith(listOf("paliers"), JsonArray(tiers.map { JsonObject(it.jsonObject - "premium") })),
            "la piste premium ne donne aucun cosmétique",
        )
        refused(passWith(listOf("paliers"), JsonArray(tiers.mapIndexed { i, tier ->
            if (i == 1) JsonObject(tier.jsonObject - "gratuit" - "premium") else tier
        })), "paliers[1] n'a aucune récompense")
    }

    @Test
    @DisplayName("La saison couvre ses jours de début et de fin compris, et un palier se retrouve par son numéro")
    fun passSeasonAndTiersLookup() {
        val pass = load().pass
        val season = pass.season
        assertTrue(season.covers(season.startDay) && season.covers(season.endDay))
        assertTrue(!season.covers(season.startDay.minusDays(1)) && !season.covers(season.endDay.plusDays(1)))
        assertEquals(pass.tiers.first(), pass.tier(1))
        assertEquals(pass.tiers.last(), pass.tier(pass.tiers.size))
        assertEquals(null, pass.tier(0))
        assertEquals(null, pass.tier(pass.tiers.size + 1))
        assertTrue(pass.tiers.none { it.premium == null && it.free == null })
    }

    @Test
    @DisplayName("Chaque cosmétique de la piste premium est déclaré dans boutique.json et n'y est pas achetable ; un inconnu ou un cosmétique vendu fait échouer le chargement")
    fun passCosmeticsAreDeclaredAndNotSold() {
        val loaded = load()
        val given = loaded.pass.tiers.mapNotNull { it.premium?.cosmeticId }
        assertTrue(given.isNotEmpty())
        for (id in given) {
            val declared = loaded.shop.cosmetics.singleOrNull { it.id == id }
            assertNotNull(declared, "« $id » est donné par le pass : boutique.json (cosmetiques) doit le déclarer")
            assertFalse(declared!!.purchasable, "« $id » est donné par le pass : la boutique ne le vend pas")
            assertEquals(0L, declared.gems, "« $id » n'est pas vendu : son prix est 0")
        }

        val tier = loaded.pass.tiers.indexOfFirst { it.premium != null }
        val path = listOf("paliers", tier, "premium", "cosmetique")
        val unknown = assertThrows<DefinitionsException> { load(pass = passWith(path, JsonPrimitive("s1-inconnu"))) }.message!!
        assertTrue(unknown.contains("pass.json") && unknown.contains("n'est pas déclaré dans boutique.json"), unknown)
        val sold = loaded.shop.cosmetics.first { it.purchasable }.id
        val selling = assertThrows<DefinitionsException> { load(pass = passWith(path, JsonPrimitive(sold))) }.message!!
        assertTrue(selling.contains("pass.json") && selling.contains("est vendu par boutique.json"), selling)
    }

    /** boutique.json avec la valeur au chemin [path] (des clés, et des positions pour les listes) remplacée par [value]. */
    private fun shopWith(path: List<Any>, value: JsonElement): String {
        fun replace(node: JsonElement, rest: List<Any>): JsonElement {
            if (rest.isEmpty()) return value
            val head = rest.first()
            return when (node) {
                is JsonObject -> JsonObject(node + ((head as String) to replace(node.getValue(head), rest.drop(1))))
                is JsonArray -> JsonArray(node.mapIndexed { i, e -> if (i == head) replace(e, rest.drop(1)) else e })
                else -> error("Chemin impossible : $path")
            }
        }
        return replace(parseJson(shopText), path).toString()
    }

    @Test
    @DisplayName("Une boutique qui n'a pas de sens est refusée : noms, cosmétiques (id, nom, prix, titre Pionnier), boost collectif")
    fun shopMustMakeSense() {
        fun refused(text: String, expected: String) {
            val message = assertThrows<DefinitionsException>(expected) { load(shop = text) }.message!!
            assertTrue(message.contains("boutique.json") && message.contains(expected), message)
        }
        val loaded = load().shop
        val cosmetics = parseJson(shopText).getValue("cosmetiques").jsonArray
        val sold = loaded.cosmetics.indexOfFirst { it.purchasable }
        val other = loaded.cosmetics.indexOfFirst { it.purchasable && it.id != loaded.cosmetics[sold].id }
        val pioneer = loaded.cosmetics.indexOfFirst { it.id == ShopDefinitions.PIONEER_TITLE_ID }
        assertTrue(sold >= 0 && other >= 0 && pioneer >= 0)
        fun cosmetic(index: Int, key: String, value: JsonElement) = shopWith(listOf("cosmetiques", index, key), value)

        // Les noms que les joueurs voient.
        refused(shopWith(listOf("abonnements", 0, "nom"), JsonPrimitive(" ")), "abonnements[].nom doit avoir de 1 à")
        refused(cosmetic(sold, "nom", JsonPrimitive(" ")), "cosmetiques[$sold].nom doit avoir de 1 à")
        refused(cosmetic(sold, "nom", JsonPrimitive("x".repeat(DefinitionsChecks.DISPLAY_NAME_MAX_LENGTH + 1))), "cosmetiques[$sold].nom doit avoir de 1 à")
        refused(cosmetic(sold, "nom", JsonPrimitive(" " + loaded.cosmetics[other].name.uppercase())), "deux cosmétiques portent le nom")

        // Les identifiants : kebab-case, uniques, et un article n'en partage pas avec un grade, un abonnement ou le boost.
        refused(cosmetic(sold, "id", JsonPrimitive("Usine-Brique")), "doit être en kebab-case")
        refused(cosmetic(sold, "id", JsonPrimitive("x".repeat(DefinitionsChecks.COSMETIC_ID_MAX_LENGTH + 1))), "doit être en kebab-case")
        refused(cosmetic(sold, "id", JsonPrimitive(loaded.cosmetics[other].id)), "deux cosmétiques ont le même id")
        refused(cosmetic(sold, "id", JsonPrimitive(loaded.grades.first().id)), "désigne déjà un grade")
        refused(cosmetic(sold, "id", JsonPrimitive(loaded.subscriptions.first().id)), "désigne déjà un abonnement")
        refused(cosmetic(sold, "id", JsonPrimitive(ShopDefinitions.BOOST_ID)), "désigne déjà le boost collectif")

        // Le prix : un cosmétique en vente coûte au moins 1 Gemme, un cosmétique qui ne se vend pas coûte 0.
        refused(cosmetic(sold, "prixGemmes", JsonPrimitive(0)), "d'un cosmétique achetable doit être au moins 1")
        refused(cosmetic(other, "achetable", JsonPrimitive(false)), "d'un cosmétique qui n'est pas vendu doit être 0")

        // Le titre Pionnier : présent, de catégorie titre, jamais en vente.
        refused(cosmetic(pioneer, "achetable", JsonPrimitive(true)), "doit contenir le titre")
        refused(cosmetic(pioneer, "categorie", JsonPrimitive("decor")), "doit contenir le titre")
        refused(shopWith(listOf("cosmetiques"), JsonArray(cosmetics.filterIndexed { i, _ -> i != pioneer })), "doit contenir le titre")

        // Le boost collectif.
        refused(shopWith(listOf("boostCollectif", "prixGemmes"), JsonPrimitive(0)), "boostCollectif.prixGemmes doit être au moins 1")
        refused(shopWith(listOf("boostCollectif", "bonusPourcent"), JsonPrimitive(0)), "boostCollectif.bonusPourcent doit être entre 1 et 100")
        refused(shopWith(listOf("boostCollectif", "bonusPourcent"), JsonPrimitive(101)), "boostCollectif.bonusPourcent doit être entre 1 et 100")
        refused(shopWith(listOf("boostCollectif", "dureeMinutes"), JsonPrimitive(0)), "boostCollectif.dureeMinutes doit être au moins 1")
    }

    @Test
    @DisplayName("Les cosmétiques et le boost sont lus comme ils sont écrits : mêmes ids, même ordre, mêmes prix, le titre Pionnier hors vente")
    fun shopCosmeticsAreReadAsWritten() {
        val loaded = load().shop
        val written = parseJson(shopText).getValue("cosmetiques").jsonArray.map { it.jsonObject }
        assertEquals(written.map { it.getValue("id").jsonPrimitive.content }, loaded.cosmetics.map { it.id })
        assertEquals(written.map { it.getValue("prixGemmes").jsonPrimitive.content.toLong() }, loaded.cosmetics.map { it.gems })
        assertEquals(written.map { it.getValue("achetable").jsonPrimitive.content.toBoolean() }, loaded.cosmetics.map { it.purchasable })
        assertEquals(CosmeticCategory.TITLE, loaded.cosmetics.single { it.id == ShopDefinitions.PIONEER_TITLE_ID }.category)
        assertEquals(CosmeticCategory.entries.toSet(), loaded.cosmetics.map { it.category }.toSet(), "chaque rayon a au moins un cosmétique")
        val boost = parseJson(shopText).getValue("boostCollectif").jsonObject
        assertEquals(boost.getValue("prixGemmes").jsonPrimitive.content.toLong(), loaded.collectiveBoost.gems)
        assertEquals(boost.getValue("bonusPourcent").jsonPrimitive.content.toInt(), loaded.collectiveBoost.bonusPercent)
        assertEquals(boost.getValue("dureeMinutes").jsonPrimitive.content.toLong(), loaded.collectiveBoost.durationMinutes)
    }

    /** ville.json avec la valeur au chemin [path] (des clés, et des positions pour les listes) remplacée par [value]. */
    private fun cityWith(path: List<Any>, value: JsonElement): String {
        fun replace(node: JsonElement, rest: List<Any>): JsonElement {
            if (rest.isEmpty()) return value
            val head = rest.first()
            return when (node) {
                is JsonObject -> JsonObject(node + ((head as String) to replace(node.getValue(head), rest.drop(1))))
                is JsonArray -> JsonArray(node.mapIndexed { i, e -> if (i == head) replace(e, rest.drop(1)) else e })
                else -> error("Chemin impossible : $path")
            }
        }
        return replace(parseJson(cityText), path).toString()
    }

    /** La position du bâtiment [id] dans la liste de ville.json. */
    private fun buildingIndex(id: String): Int =
        parseJson(cityText).getValue("batiments").jsonArray.indexOfFirst { it.jsonObject.getValue("id").jsonPrimitive.content == id }

    @Test
    @DisplayName("Une ville qui n'a pas de sens est refusée : zones, mairie, logement, plafonds, bâtiments")
    fun cityMustMakeSense() {
        fun refused(text: String, expected: String) {
            val message = assertThrows<DefinitionsException>(expected) { load(city = text) }.message!!
            assertTrue(message.contains("ville.json") && message.contains(expected), message)
        }
        val town = buildingIndex("mairie")
        val house = buildingIndex("maison")
        assertTrue(town >= 0 && house >= 0)

        // Le quadrillage et les zones.
        refused(cityWith(listOf("ile", "parcelleChunks"), JsonPrimitive(3)), "ile.parcelleChunks doit diviser")
        refused(cityWith(listOf("ile", "blocsParChunk"), JsonPrimitive(0)), "ile.blocsParChunk")
        refused(cityWith(listOf("zones", "colonnes"), JsonPrimitive(3)), "zones.ordre doit lister")
        refused(cityWith(listOf("zones", "ordre"), JsonArray(parseJson(cityText).getValue("zones").jsonObject.getValue("ordre").jsonArray.dropLast(1))), "zones.ordre doit lister")
        val first = parseJson(cityText).getValue("zones").jsonObject.getValue("ordre").jsonArray[0]
        refused(cityWith(listOf("zones", "ordre", 1), first), "chaque case du quadrillage une fois")
        refused(cityWith(listOf("zones", "ordre", 0, "niveauMairieRequis"), JsonPrimitive(0)), "zones.ordre[0].niveauMairieRequis")
        refused(cityWith(listOf("zones", "ordre", 15, "niveauMairieRequis"), JsonPrimitive(1)), "zones.ordre[15].niveauMairieRequis")
        refused(cityWith(listOf("zones", "premiere", "usinesActivesMin"), JsonPrimitive(-1)), "usinesActivesMin")

        // Les prix des zones restent dans economie.json : un prix de moins, et la ville ne tient plus.
        val economy = parseJson(economyText)
        val prices = economy.getValue("ville").jsonObject.getValue("zonesPrixLingots").jsonArray
        val shortEconomy = JsonObject(economy + ("ville" to JsonObject(economy.getValue("ville").jsonObject + ("zonesPrixLingots" to JsonArray(prices.dropLast(1))))))
        val priceMessage = assertThrows<DefinitionsException> { load(economy = shortEconomy.toString()) }.message!!
        assertTrue(priceMessage.contains("zonesPrixLingots"), priceMessage)

        // La mairie de départ.
        refused(cityWith(listOf("mairie", "emplacementDepart", "x"), JsonPrimitive(8)), "doit tomber sur une parcelle")
        refused(cityWith(listOf("mairie", "emplacementDepart", "z"), JsonPrimitive(-16)), "doit tomber sur une parcelle")
        refused(cityWith(listOf("mairie", "emplacementDepart", "x"), JsonPrimitive(0)), "première zone")
        refused(cityWith(listOf("mairie", "emplacementDepart", "rotation"), JsonPrimitive(45)), "rotation")

        // Le logement et les plafonds.
        refused(cityWith(listOf("logement", "reductionSalairePourcent"), JsonPrimitive(101)), "reductionSalairePourcent")
        refused(cityWith(listOf("logement", "reductionSalairePourcent"), JsonPrimitive(-1)), "reductionSalairePourcent")
        for (type in BuildingBonusType.entries) {
            refused(cityWith(listOf("plafonds", type.name), JsonPrimitive(0)), "plafonds.$type")
        }

        // Les bâtiments.
        refused(cityWith(listOf("batiments", house, "id"), JsonPrimitive("mairie")), "deux bâtiments ont le même id")
        refused(cityWith(listOf("batiments", house, "id"), JsonPrimitive("Maison")), "kebab-case")
        refused(cityWith(listOf("batiments", house, "id"), JsonPrimitive("un-batiment-dont-le-nom-est-bien-trop-long")), "32 caractères")
        refused(cityWith(listOf("batiments", house, "quantiteMax"), JsonPrimitive(0)), "quantiteMax")
        refused(cityWith(listOf("batiments", house, "niveaux"), JsonArray(emptyList())), "niveaux ne doit pas être vide")
        refused(cityWith(listOf("batiments", house, "niveaux", 0, "prixLingots"), JsonPrimitive(0)), "prixLingots")
        refused(cityWith(listOf("batiments", house, "niveaux", 0, "niveauMairieRequis"), JsonPrimitive(99)), "niveauMairieRequis")
        refused(cityWith(listOf("batiments", house, "niveaux", 0, "bonus"), JsonPrimitive(0)), "bonus")
        refused(cityWith(listOf("batiments", house, "niveaux", 1, "bonus"), JsonPrimitive(0.5)), "bonus ne peut pas baisser")
        refused(cityWith(listOf("batiments", town, "posable"), JsonPrimitive(true)), "non posable")
        refused(cityWith(listOf("batiments", town, "quantiteMax"), JsonPrimitive(2)), "non posable")
        refused(cityWith(listOf("batiments", town, "niveaux"), JsonArray(parseJson(cityText).getValue("batiments").jsonArray[town].jsonObject.getValue("niveaux").jsonArray.take(3))), "au moins autant de niveaux")
    }

    @Test
    @DisplayName("La ville est lue comme elle est écrite : zones dans l'ordre, mairie gratuite au niveau 1, plafonds de chaque type")
    fun cityIsReadAsWritten() {
        val loaded = load().city
        val root = parseJson(cityText)
        assertEquals(root.getValue("zones").jsonObject.getValue("ordre").jsonArray.size, loaded.zones.order.size)
        assertEquals(load().economy.city.zonePricesLingots.size, loaded.zones.order.size)
        assertEquals(BuildingBonusType.entries.toSet(), loaded.caps.keys)
        val hall = loaded.building(CityDefinitions.TOWN_HALL_ID)!!
        assertEquals(false, hall.placeable)
        assertEquals(0L, hall.level(1).priceLingots)
        assertEquals(null, hall.bonusType)
        val bonusBuildings = loaded.buildings.filter { it.bonusType != null }
        assertTrue(bonusBuildings.isNotEmpty())
        assertTrue(bonusBuildings.all { building -> building.levels.all { it.bonus != null } })
        assertEquals(loaded.grid.blocksPerChunk * loaded.grid.plotChunks, loaded.grid.plotBlocks())
    }
}
