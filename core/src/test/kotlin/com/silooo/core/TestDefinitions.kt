package com.silooo.core

import com.silooo.core.definitions.Definitions
import com.silooo.core.definitions.DefinitionsLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Les définitions du jeu, telles que les tests les lisent : le dossier `definitions/` du dépôt, dont
 * `build.gradle.kts` pose le chemin dans la propriété système `silooo.definitions.dir`. Aucun chemin
 * relatif écrit dans un test, aucune copie des chiffres.
 */
object TestDefinitions {
    val directory: Path = Path.of(
        checkNotNull(System.getProperty("silooo.definitions.dir")) {
            "La propriété système silooo.definitions.dir manque : les tests se lancent par Gradle (core/build.gradle.kts la pose)."
        },
    )

    /** Chargées une fois pour tous les tests. */
    val loaded: Definitions by lazy { DefinitionsLoader.load(directory) }

    /** Le texte d'un fichier de definitions/, pour les tests qui l'abîment à dessein. */
    fun text(name: String): String = Files.readString(directory.resolve(name))
}

/**
 * Les JSON dorés tirés du tableur (`src/test/resources/golden/`, lus sur le chemin de classes). Ils
 * sont générés par `tools/tableur/extraire.py` : un test les lit, il ne les corrige jamais.
 */
object GoldenFiles {
    private val cache = HashMap<String, JsonObject>()

    /** `read("simulation")` lit `golden/simulation.json`. */
    @Synchronized
    fun read(name: String): JsonObject = cache.getOrPut(name) {
        val stream = checkNotNull(GoldenFiles::class.java.getResourceAsStream("/golden/$name.json")) { "golden/$name.json est introuvable" }
        Json.parseToJsonElement(stream.use { String(it.readBytes(), Charsets.UTF_8) }).jsonObject
    }
}

fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double

fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

fun JsonObject.objects(key: String): List<JsonObject> = array(key).map { it.jsonObject }

/** Une copie de l'objet sans la clé `key`, à n'importe quelle profondeur donnée par `path` (indices d'éléments inclus). */
fun JsonObject.without(path: List<String>): JsonObject {
    val head = path.first()
    if (path.size == 1) return JsonObject(filterKeys { it != head })
    val child = getValue(head)
    val replaced = when (child) {
        is JsonObject -> child.without(path.drop(1))
        is JsonArray -> {
            val index = path[1].toInt()
            val element = child[index].jsonObject
            JsonArray(child.mapIndexed { i, e -> if (i == index) element.without(path.drop(2)) else e })
        }
        else -> error("Chemin impossible : $path")
    }
    return JsonObject(mapValues { (key, value) -> if (key == head) replaced else value })
}

/** Toutes les clés d'un objet JSON sous forme de chemins (`["prestige", "seuils", "0", "donar"]`). */
fun JsonObject.keyPaths(prefix: List<String> = emptyList()): List<List<String>> = flatMap { (key, value) ->
    val here = prefix + key
    when (value) {
        is JsonObject -> listOf(here) + value.keyPaths(here)
        is JsonArray -> listOf(here) + value.withIndex().flatMap { (i, element) ->
            if (element is JsonObject) element.keyPaths(here + i.toString()) else emptyList()
        }
        is JsonPrimitive -> listOf(here)
    }
}
