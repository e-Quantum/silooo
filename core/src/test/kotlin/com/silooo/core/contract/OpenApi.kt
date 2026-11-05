package com.silooo.core.contract

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Le contrat `api/silooo-core.openapi.yaml` lu par les tests : ses opérations, de quoi fabriquer une requête
 * minimale valide pour chacune, et de quoi vérifier qu'une réponse a la forme que le contrat promet.
 *
 * C'est un lecteur volontairement petit : il comprend ce que CE contrat emploie (`$ref`, `allOf`, `type`,
 * `enum`, `required`, `properties`, `items`, `minimum`, `maximum`, `pattern`, `format`, `nullable`) et
 * REFUSE, au lieu de les ignorer, les mots qu'il ne connaît pas. Quand le contrat prendra un mot de plus, le
 * test échouera en le disant ; personne n'aura cru vérifier ce qui ne l'est pas.
 */
class OpenApi private constructor(private val root: Map<String, Any?>) {

    /** Un paramètre de chemin ou de requête. */
    class Parameter(val name: String, val location: String, val required: Boolean, val schema: Map<String, Any?>)

    /** Une opération du contrat : un verbe sur un chemin. */
    class Operation(
        val id: String,
        val method: String,
        val path: String,
        val parameters: List<Parameter>,
        /** Le schéma du corps de la requête, ou `null` s'il n'y en a pas. */
        val body: Map<String, Any?>?,
        /** Les réponses déclarées : code HTTP -> schéma du corps JSON (`null` : pas de corps). */
        val responses: Map<Int, Map<String, Any?>?>,
    ) {
        val pathParameters get() = parameters.filter { it.location == "path" }
        val queryParameters get() = parameters.filter { it.location == "query" }
        override fun toString() = "$id ($method $path)"
    }

    val operations: List<Operation> by lazy { readOperations() }

    // --- lecture

    @Suppress("UNCHECKED_CAST")
    private fun readOperations(): List<Operation> {
        val result = mutableListOf<Operation>()
        val paths = root.getValue("paths") as Map<String, Map<String, Any?>>
        for ((path, item) in paths) {
            for ((method, raw) in item) {
                if (method !in METHODS) continue
                val op = raw as Map<String, Any?>
                val parameters = ((item["parameters"] as? List<Any?>).orEmpty() + (op["parameters"] as? List<Any?>).orEmpty())
                    .map { resolve(it) as Map<String, Any?> }
                    .map {
                        Parameter(
                            name = it.getValue("name") as String,
                            location = it.getValue("in") as String,
                            required = it["required"] == true,
                            schema = resolve(it.getValue("schema")) as Map<String, Any?>,
                        )
                    }
                val body = (op["requestBody"]?.let { resolve(it) } as Map<String, Any?>?)
                    ?.let { jsonSchemaOf(it) }
                val responses = (op.getValue("responses") as Map<Any, Any?>).entries.associate { (code, response) ->
                    code.toString().toInt() to (resolve(response) as Map<String, Any?>).let { jsonSchemaOf(it) }
                }
                result += Operation(op.getValue("operationId") as String, method.uppercase(), path, parameters, body, responses)
            }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonSchemaOf(holder: Map<String, Any?>): Map<String, Any?>? {
        val content = holder["content"] as? Map<String, Any?> ?: return null
        val json = content["application/json"] as? Map<String, Any?>
            ?: error("Seul application/json est géré par les tests de contrat : ${content.keys}")
        return json["schema"]?.let { resolve(it) as Map<String, Any?> }
    }

    /** Suit un `$ref` local (`#/components/...`) jusqu'au nœud qu'il désigne. */
    @Suppress("UNCHECKED_CAST")
    fun resolve(node: Any?): Any? {
        if (node !is Map<*, *>) return node
        val ref = node["\$ref"] as String? ?: return node
        require(ref.startsWith("#/")) { "\$ref non local : $ref" }
        var current: Any? = root
        for (part in ref.removePrefix("#/").split('/')) current = (current as Map<String, Any?>).getValue(part)
        return resolve(current)
    }

    /** Un schéma de `components.schemas`, par son nom. */
    @Suppress("UNCHECKED_CAST")
    fun schema(name: String): Map<String, Any?> =
        resolve(mapOf("\$ref" to "#/components/schemas/$name")) as Map<String, Any?>

    /** La réponse `components.responses.[name]`, ramenée à son schéma JSON. */
    @Suppress("UNCHECKED_CAST")
    fun responseSchema(name: String): Map<String, Any?> =
        jsonSchemaOf(resolve(mapOf("\$ref" to "#/components/responses/$name")) as Map<String, Any?>)!!

    /** Le schéma de la propriété [property] de [schema] (`allOf` fusionnés, références suivies). */
    @Suppress("UNCHECKED_CAST")
    fun propertyOf(schema: Map<String, Any?>, property: String): Map<String, Any?> =
        resolve(flatten(schema).properties.getValue(property)) as Map<String, Any?>

    /** Les valeurs de `enum` de la propriété [property] du schéma [schema]. */
    @Suppress("UNCHECKED_CAST")
    fun enumOf(schema: Map<String, Any?>, property: String): Set<String> {
        val properties = flatten(schema).properties
        val found = resolve(properties.getValue(property)) as Map<String, Any?>
        return (found.getValue("enum") as List<Any?>).map { it.toString() }.toSet()
    }

    /** Un nœud du document, par son chemin de clés (`at("components", "securitySchemes")`). */
    @Suppress("UNCHECKED_CAST")
    fun at(vararg path: String): Any? = path.fold(root as Any?) { node, key -> (node as Map<String, Any?>)[key] }

    /** Les noms de propriétés obligatoires de [schema], `allOf` fusionnés. */
    fun requiredProperties(schema: Map<String, Any?>): List<String> = flatten(schema).required

    // --- fabrication d'une valeur valide

    /**
     * Une valeur minimale qui respecte [schema] : seulement les propriétés `required`, la première valeur d'un
     * `enum`, le plus petit nombre permis.
     */
    @Suppress("UNCHECKED_CAST")
    fun example(schema: Map<String, Any?>): JsonElement {
        val resolved = resolve(schema) as Map<String, Any?>
        checkKnown(resolved)
        resolved["enum"]?.let { return primitive((it as List<Any?>).first()) }
        resolved["example"]?.takeIf { it is String || it is Number || it is Boolean }?.let { return primitive(it) }
        val shape = flatten(resolved)
        return when (shape.type) {
            "object" -> JsonObject(
                shape.required.associateWith { name ->
                    example(shape.properties[name] as? Map<String, Any?> ?: error("Propriété obligatoire sans schéma : $name"))
                },
            )
            "array" -> JsonArray(listOf(example(shape.items ?: error("tableau sans items"))))
            "string" -> JsonPrimitive(exampleString(shape.raw))
            "integer" -> JsonPrimitive(((shape.raw["minimum"] as Number?)?.toLong() ?: 1L))
            "number" -> JsonPrimitive(((shape.raw["minimum"] as Number?)?.toDouble() ?: 1.0))
            "boolean" -> JsonPrimitive(true)
            else -> error("Type non géré par les tests de contrat : ${shape.type} dans $resolved")
        }
    }

    private fun exampleString(schema: Map<String, Any?>): String = when {
        schema["format"] == "uuid" -> "6f1c2b7e-0d4a-4a53-9c1e-3f6a8d2b5e90"
        schema["format"] == "date-time" -> "2026-11-05T12:00:00Z"
        schema["format"] == "date" -> "2026-11-05"
        schema["pattern"] == XUID_PATTERN -> "2535428000000001"
        schema["pattern"] != null -> error("Motif inconnu des tests de contrat : ${schema["pattern"]}")
        else -> "x"
    }

    private fun primitive(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    // --- vérification d'une valeur

    /** Les écarts entre [value] et [schema] (liste vide : conforme). [strict] refuse aussi les propriétés que le schéma ne déclare pas. */
    fun violations(schema: Map<String, Any?>, value: JsonElement, strict: Boolean = true): List<String> {
        val found = mutableListOf<String>()
        check(schema, value, "$", strict, found)
        return found
    }

    @Suppress("UNCHECKED_CAST")
    private fun check(schema: Map<String, Any?>, value: JsonElement, at: String, strict: Boolean, found: MutableList<String>) {
        val resolved = resolve(schema) as Map<String, Any?>
        checkKnown(resolved)

        if (value is JsonNull) {
            if (!isNullable(resolved)) found += "$at : null, mais le contrat ne l'autorise pas"
            return
        }
        (resolved["enum"] as List<Any?>?)?.let { allowed ->
            val text = (value as? JsonPrimitive)?.content
            if (text == null || allowed.none { it.toString() == text }) found += "$at : $value n'est pas dans $allowed"
        }
        val shape = flatten(resolved)
        when (shape.type) {
            "object" -> {
                if (value !is JsonObject) return run { found += "$at : un objet est attendu, reçu $value" }
                for (name in shape.required) if (name !in value) found += "$at : la propriété obligatoire « $name » manque"
                for ((name, child) in value) {
                    val childSchema = shape.properties[name]
                    when {
                        childSchema != null -> check(childSchema as Map<String, Any?>, child, "$at.$name", strict, found)
                        strict && !shape.additionalAllowed -> found += "$at : la propriété « $name » n'est pas dans le contrat"
                    }
                }
            }
            "array" -> {
                if (value !is JsonArray) return run { found += "$at : un tableau est attendu, reçu $value" }
                val items = shape.items
                if (items != null) value.forEachIndexed { index, child -> check(items, child, "$at[$index]", strict, found) }
            }
            "string" -> {
                if (value !is JsonPrimitive || !value.isString) return run { found += "$at : une chaîne est attendue, reçu $value" }
                val text = value.content
                (shape.raw["maxLength"] as Number?)?.let { max ->
                    if (text.codePointCount(0, text.length) > max.toInt()) found += "$at : plus de $max caractères"
                }
                (shape.raw["minLength"] as Number?)?.let { min ->
                    if (text.codePointCount(0, text.length) < min.toInt()) found += "$at : moins de $min caractères"
                }
                val pattern = shape.raw["pattern"] as String?
                if (pattern != null && !Regex(pattern).containsMatchIn(text)) found += "$at : « $text » ne suit pas le motif $pattern"
                when (shape.raw["format"]) {
                    "uuid" -> if (!UUID_FORMAT.matches(text)) found += "$at : « $text » n'est pas un UUID"
                    "date-time" -> if (runCatching { java.time.Instant.parse(text) }.isFailure) found += "$at : « $text » n'est pas une date ISO 8601"
                    "date" -> if (runCatching { java.time.LocalDate.parse(text) }.isFailure) found += "$at : « $text » n'est pas une date"
                }
            }
            "integer" -> {
                val number = (value as? JsonPrimitive)?.takeIf { !it.isString }
                val long = number?.longOrNull
                if (long == null) found += "$at : un entier est attendu, reçu $value" else bounds(shape.raw, long.toDouble(), at, found)
            }
            "number" -> {
                val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                if (number == null) found += "$at : un nombre est attendu, reçu $value" else bounds(shape.raw, number, at, found)
            }
            "boolean" -> {
                if ((value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null) found += "$at : un booléen est attendu, reçu $value"
            }
            else -> found += "$at : type non géré « ${shape.type} »"
        }
    }

    private fun bounds(schema: Map<String, Any?>, number: Double, at: String, found: MutableList<String>) {
        (schema["minimum"] as Number?)?.let { if (number < it.toDouble()) found += "$at : $number est sous le minimum $it" }
        (schema["maximum"] as Number?)?.let { if (number > it.toDouble()) found += "$at : $number dépasse le maximum $it" }
    }

    // --- aplatir allOf

    /** Un schéma une fois `allOf` fusionné. */
    private class Shape(
        val type: String,
        val raw: Map<String, Any?>,
        val required: List<String>,
        val properties: Map<String, Any?>,
        val items: Map<String, Any?>?,
        val additionalAllowed: Boolean,
    )

    @Suppress("UNCHECKED_CAST")
    private fun flatten(schema: Map<String, Any?>): Shape {
        val resolved = resolve(schema) as Map<String, Any?>
        val parts = (resolved["allOf"] as List<Any?>?)?.map { flatten(it as Map<String, Any?>) }.orEmpty()
        val type = resolved["type"] as String? ?: parts.firstOrNull { it.type.isNotEmpty() }?.type
            ?: if (resolved["properties"] != null) "object" else ""
        val required = ((resolved["required"] as List<Any?>?)?.map { it.toString() }.orEmpty()) + parts.flatMap { it.required }
        val properties = parts.fold(emptyMap<String, Any?>()) { acc, part -> acc + part.properties } +
            (resolved["properties"] as Map<String, Any?>? ?: emptyMap())
        val additional = resolved["additionalProperties"]
        val allowed = additional == true || (additional is Map<*, *>) || parts.any { it.additionalAllowed }
        val items = (resolved["items"] as Map<String, Any?>?) ?: parts.firstNotNullOfOrNull { it.items }
        return Shape(type, resolved, required.distinct(), properties, items, allowed)
    }

    private fun isNullable(schema: Map<String, Any?>): Boolean {
        if (schema["nullable"] == true) return true
        @Suppress("UNCHECKED_CAST")
        return (schema["allOf"] as List<Any?>?)?.any { isNullable(resolve(it) as Map<String, Any?>) } == true
    }

    /** Refuse un mot de schéma que ce lecteur ne sait pas vérifier : mieux vaut échouer que ne rien contrôler. */
    private fun checkKnown(schema: Map<String, Any?>) {
        val unknown = schema.keys - KNOWN_KEYWORDS
        require(unknown.isEmpty()) { "Mots du contrat que les tests ne vérifient pas : $unknown dans $schema" }
    }

    companion object {
        private val METHODS = setOf("get", "post", "put", "patch", "delete")
        const val XUID_PATTERN = "^[0-9]{1,20}\$"
        private val UUID_FORMAT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\$")
        private val KNOWN_KEYWORDS = setOf(
            "type", "format", "enum", "required", "properties", "items", "allOf", "nullable", "description",
            "example", "default", "minimum", "maximum", "minLength", "maxLength", "pattern", "additionalProperties",
        )

        /** Le chemin du contrat : on remonte depuis le dossier de travail des tests jusqu'à `api/silooo-core.openapi.yaml`. */
        fun locate(): Path {
            var folder: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (folder != null) {
                val candidate = folder.resolve("api").resolve("silooo-core.openapi.yaml")
                if (Files.isRegularFile(candidate)) return candidate
                folder = folder.parent
            }
            error("api/silooo-core.openapi.yaml introuvable depuis ${System.getProperty("user.dir")}")
        }

        /** Lit le contrat du dépôt. */
        @Suppress("UNCHECKED_CAST")
        fun load(path: Path = locate()): OpenApi {
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            val document = Files.newBufferedReader(path, Charsets.UTF_8).use { yaml.load<Any?>(it) }
            return OpenApi(document as Map<String, Any?>)
        }
    }
}
