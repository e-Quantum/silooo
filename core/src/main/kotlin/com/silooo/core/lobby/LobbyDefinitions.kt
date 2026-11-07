package com.silooo.core.lobby

import com.silooo.core.definitions.DefinitionsException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * La forme de definitions/attente.json (schéma : definitions/schemas/attente.schema.json), pour ce que le service en
 * lit : le plafond de vitesse d'une course et le classement. Le reste du fichier (le parcours, les rappels, les rythmes)
 * est lu par l'add-on de la salle d'attente. Lu à part des [com.silooo.core.definitions.Definitions] du jeu, comme
 * discord.json : un fichier abîmé arrête la salle d'attente, jamais le jeu.
 */
@Serializable
data class LobbyDefinitions(
    val version: Int,
    val commentaire: String,
    val parcours: Course,
    val classement: Board,
    val rappels: kotlinx.serialization.json.JsonObject,
    val rythmes: kotlinx.serialization.json.JsonObject,
) {
    @Serializable
    data class Course(
        val yMin: Int,
        val yMax: Int,
        val chute: Int,
        val trouMax: kotlinx.serialization.json.JsonObject,
        val paliers: kotlinx.serialization.json.JsonArray,
        @SerialName("plafondParSeconde") val maxPerSecond: Double,
    )

    @Serializable
    data class Board(
        @SerialName("taille") val size: Int,
        @SerialName("fuseau") val zoneName: String,
        @SerialName("actus") val news: Int,
        @SerialName("hologramme") val hologram: Int,
        @SerialName("podiumDiscord") val podium: Int,
    )

    /** Le fuseau de la semaine du classement. */
    val zone: ZoneId get() = ZoneId.of(classement.zoneName)

    companion object {
        const val FILE = "attente.json"
        private val json = Json { ignoreUnknownKeys = false }

        fun load(directory: Path): LobbyDefinitions {
            val file = directory.resolve(FILE)
            if (!Files.isRegularFile(file)) throw DefinitionsException("Le fichier $FILE manque dans le dossier des définitions : $directory")
            return parse(String(Files.readAllBytes(file), StandardCharsets.UTF_8))
        }

        fun parse(text: String): LobbyDefinitions {
            val parsed = try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw DefinitionsException("$FILE : ${e.message.orEmpty().lineSequence().firstOrNull().orEmpty()}", e)
            } catch (e: IllegalArgumentException) {
                throw DefinitionsException("$FILE : ${e.message.orEmpty().lineSequence().firstOrNull().orEmpty()}", e)
            }
            try {
                parsed.zone
            } catch (e: Exception) {
                throw DefinitionsException("$FILE : fuseau inconnu « ${parsed.classement.zoneName} »", e)
            }
            if (parsed.parcours.maxPerSecond <= 0.0) throw DefinitionsException("$FILE : plafondParSeconde doit être positif")
            return parsed
        }
    }
}
