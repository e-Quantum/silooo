package com.silooo.core.definitions

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Lit definitions/ au démarrage : `economie.json`, `usines.json`, `boutique.json`, `monde.json`, `missions.json`, `personnel.json`, dans
 * un dossier donné (variable `SILOOO_DEFINITIONS_DIR`, voir [com.silooo.core.Config]).
 *
 * Le chargement est strict. Une clé manquante, une clé inconnue, un type qui ne va pas, un fichier
 * absent : tout échoue net avec une [DefinitionsException] qui dit le fichier et le chemin JSON, et le
 * service ne démarre pas. Tous les problèmes de forme de tous les fichiers sont rendus d'un coup.
 */
object DefinitionsLoader {
    const val ECONOMY_FILE = "economie.json"
    const val FACTORIES_FILE = "usines.json"
    const val SHOP_FILE = "boutique.json"
    const val WORLD_FILE = "monde.json"
    const val MISSIONS_FILE = "missions.json"
    const val PERSONNEL_FILE = "personnel.json"

    // ignoreUnknownKeys reste faux : le fichier et les classes Kotlin disent la même chose.
    private val json = Json { ignoreUnknownKeys = false }

    fun load(directory: Path): Definitions {
        if (!Files.isDirectory(directory)) {
            throw DefinitionsException("Le dossier des définitions n'existe pas : $directory")
        }
        return parse(
            economy = read(directory, ECONOMY_FILE),
            factories = read(directory, FACTORIES_FILE),
            shop = read(directory, SHOP_FILE),
            world = read(directory, WORLD_FILE),
            missions = read(directory, MISSIONS_FILE),
            personnel = read(directory, PERSONNEL_FILE),
        )
    }

    /** Les six textes JSON déjà lus. Sert au chargement et aux tests qui abîment un fichier à dessein. */
    fun parse(economy: String, factories: String, shop: String, world: String, missions: String, personnel: String): Definitions {
        val problems = mutableListOf<String>()
        val parsedEconomy = decode(EconomyDefinitions.serializer(), ECONOMY_FILE, economy, problems)
        val parsedFactories = decode(FactoriesDefinitions.serializer(), FACTORIES_FILE, factories, problems)
        val parsedShop = decode(ShopDefinitions.serializer(), SHOP_FILE, shop, problems)
        val parsedWorld = decode(WorldDefinitions.serializer(), WORLD_FILE, world, problems)
        val parsedMissions = decode(MissionsDefinitions.serializer(), MISSIONS_FILE, missions, problems)
        val parsedPersonnel = decode(PersonnelDefinitions.serializer(), PERSONNEL_FILE, personnel, problems)
        if (problems.isNotEmpty() || parsedEconomy == null || parsedFactories == null || parsedShop == null || parsedWorld == null ||
            parsedMissions == null || parsedPersonnel == null
        ) {
            throw DefinitionsException("Définitions illisibles :\n" + problems.joinToString("\n") { "  - $it" })
        }
        return Definitions(parsedEconomy, parsedFactories, parsedShop, parsedWorld, parsedMissions, parsedPersonnel)
    }

    private fun read(directory: Path, name: String): String {
        val file = directory.resolve(name)
        if (!Files.isRegularFile(file)) {
            throw DefinitionsException("Le fichier $name manque dans le dossier des définitions : $directory")
        }
        try {
            return String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            throw DefinitionsException("Impossible de lire $file : ${e.message}", e)
        }
    }

    // missingFields est marqué expérimental dans kotlinx.serialization ; on s'en sert pour nommer la clé.
    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> decode(strategy: DeserializationStrategy<T>, name: String, text: String, problems: MutableList<String>): T? =
        try {
            json.decodeFromString(strategy, text)
        } catch (e: MissingFieldException) {
            problems += "$name : clé manquante ${e.missingFields.joinToString(", ") { "« $it »" }}${where(e)}"
            null
        } catch (e: SerializationException) {
            problems += "$name : ${firstLine(e)}"
            null
        } catch (e: IllegalArgumentException) {
            // JSON illisible ou nombre hors limites : kotlinx le signale parfois en IllegalArgumentException.
            problems += "$name : ${firstLine(e)}"
            null
        }

    /** Le chemin JSON que kotlinx met en fin de message (« at path: $.niveaux »), pour dire où chercher. */
    private fun where(e: Exception): String {
        val path = Regex("at path: (\\S+)").find(e.message.orEmpty())?.groupValues?.get(1)
        return if (path == null) "" else " (chemin JSON $path)"
    }

    /** Le message de kotlinx sans ses conseils ni la copie du JSON, qui n'aident pas ici. */
    private fun firstLine(e: Exception): String = e.message.orEmpty().lineSequence().firstOrNull().orEmpty()
}
