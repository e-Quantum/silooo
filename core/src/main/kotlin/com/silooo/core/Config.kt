package com.silooo.core

import com.silooo.core.definitions.DefinitionsLoader
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration

/** Une variable d'environnement absente ou invalide. Le message les liste toutes d'un coup. */
class ConfigException(message: String) : Exception(message)

/** Connexion à la base `silooo`. Le mot de passe ne s'affiche jamais. */
data class DatabaseConfig(val url: String, val user: String, val password: String) {
    override fun toString() = "DatabaseConfig(url=$url, user=$user, password=***)"
}

/**
 * Tout ce qu'il faut pour démarrer le service. La clé ne s'affiche jamais.
 * [definitionsDir] est le dossier qui contient economie.json, usines.json, boutique.json, monde.json et missions.json.
 *
 * [apiRequestRetention] : combien de temps `api_requests` garde une réponse pour la rejouer ; au-delà,
 * la ligne est purgée (voir [Config.API_REQUESTS_RETENTION_HOURS]).
 *
 * [deliveryPoll] : l'intervalle du relevé des commandes de Gemmes du site (`shop_deliveries`), voir [Config.DELIVERY_POLL_SECONDS].
 */
data class ServiceConfig(
    val port: Int,
    val apiKey: String,
    val database: DatabaseConfig,
    val definitionsDir: Path,
    val apiRequestRetention: Duration = Duration.ofHours(Config.DEFAULT_API_REQUESTS_RETENTION_HOURS),
    val deliveryPoll: Duration = Duration.ofSeconds(Config.DEFAULT_DELIVERY_POLL_SECONDS),
) {
    override fun toString() =
        "ServiceConfig(port=$port, apiKey=***, database=$database, definitionsDir=$definitionsDir, " +
            "apiRequestRetention=$apiRequestRetention, deliveryPoll=$deliveryPoll)"
}

/**
 * La configuration vient des variables d'environnement, et d'elles seules : pas de fichier lu par
 * le code, rien d'écrit dans le dépôt (règle 9 du socle). Les fonctions prennent la table des
 * variables en paramètre, pour qu'un test n'ait pas à toucher à l'environnement du processus.
 */
object Config {
    const val PORT = "SILOOO_PORT"
    const val DB_URL = "SILOOO_DB_URL"
    const val DB_USER = "SILOOO_DB_USER"
    const val DB_PASSWORD = "SILOOO_DB_PASSWORD"
    const val API_KEY = "SILOOO_API_KEY"
    const val DEFINITIONS_DIR = "SILOOO_DEFINITIONS_DIR"

    /**
     * Combien d'heures `api_requests` garde la réponse d'une requête pour la rejouer. Un réglage technique, pas
     * un chiffre du jeu : un rejeu arrive dans les secondes ou les minutes qui suivent une coupure, jamais
     * des jours plus tard.
     */
    const val API_REQUESTS_RETENTION_HOURS = "SILOOO_API_REQUESTS_RETENTION_HOURS"

    /**
     * 24 heures quand la variable n'est pas posée : très au-delà de n'importe quel nouvel essai de l'add-on,
     * et assez court pour que la table ne grossisse pas dans la base partagée avec SileneMC (une ligne par
     * action, avec l'état de l'entreprise en réponse).
     */
    const val DEFAULT_API_REQUESTS_RETENTION_HOURS = 24L

    /** Garde-fou : une faute de frappe ne doit pas garder les réponses un siècle (une année au plus). */
    const val MAX_API_REQUESTS_RETENTION_HOURS = 8760L

    /**
     * Toutes les combien de secondes le service relève `shop_deliveries` : les commandes de Gemmes que le site a écrites et qu'il
     * verse aux joueurs qu'il connaît. Un réglage technique, pas un chiffre du jeu : plus court, le joueur voit ses Gemmes plus
     * vite ; plus long, la base est moins sollicitée. Un joueur qui entre en jeu reçoit les siennes à `join`, sans attendre ce passage.
     */
    const val DELIVERY_POLL_SECONDS = "SILOOO_DELIVERY_POLL_SECONDS"

    /** 30 secondes quand la variable n'est pas posée : le signal de vie du serveur bat toutes les 10 secondes, et un paiement se voit vite. */
    const val DEFAULT_DELIVERY_POLL_SECONDS = 30L

    /** Garde-fou : une faute de frappe ne doit pas laisser les paiements une journée sans livraison (une heure au plus). */
    const val MAX_DELIVERY_POLL_SECONDS = 3600L

    /** Port d'écoute quand `SILOOO_PORT` n'est pas posé : celui de l'OpenAPI (`http://silooo-core:8080`). */
    const val DEFAULT_PORT = 8080

    /**
     * Seulement la base : c'est tout ce que le mode `migrate` demande. Le service `silooo-migrate`
     * du compose n'a donc pas besoin de la clé du serveur ni des définitions.
     */
    fun database(env: Map<String, String>): DatabaseConfig {
        val reader = Reader(env)
        val database = reader.database()
        reader.finish()
        return checkNotNull(database)
    }

    /**
     * Démarrage normal : le service refuse de partir sans la base, la clé ni les définitions.
     * [workingDir] ne sert qu'à retrouver `definitions/` en développement, quand `SILOOO_DEFINITIONS_DIR`
     * n'est pas posée : un test le donne explicitement, le service prend le dossier courant.
     */
    fun service(env: Map<String, String>, workingDir: Path = Path.of("").toAbsolutePath()): ServiceConfig {
        val reader = Reader(env)
        val database = reader.database()
        val apiKey = reader.required(API_KEY)
        val port = reader.port()
        val definitionsDir = reader.definitionsDir(workingDir)
        val retention = reader.apiRequestRetention()
        val deliveryPoll = reader.deliveryPoll()
        reader.finish()
        return ServiceConfig(
            checkNotNull(port), checkNotNull(apiKey), checkNotNull(database), checkNotNull(definitionsDir), checkNotNull(retention),
            checkNotNull(deliveryPoll),
        )
    }

    /**
     * En développement, `definitions/` du dépôt se trouve depuis le dossier courant : `../definitions`
     * quand on lance depuis `core/`, `definitions` quand on lance depuis la racine. Rien d'autre n'est
     * deviné ; dans l'image Docker la variable est posée.
     */
    internal fun developmentDefinitionsDir(workingDir: Path): Path? =
        listOf(workingDir.resolve("../definitions").normalize(), workingDir.resolve("definitions").normalize())
            .firstOrNull { Files.isRegularFile(it.resolve(DefinitionsLoader.ECONOMY_FILE)) }

    /** Lit les variables en gardant chaque problème, pour tout signaler en une fois. */
    private class Reader(private val env: Map<String, String>) {
        private val problems = mutableListOf<String>()

        fun required(name: String): String? {
            val value = env[name]
            if (value.isNullOrBlank()) {
                problems += "$name est absente ou vide"
                return null
            }
            return value
        }

        fun database(): DatabaseConfig? {
            val url = required(DB_URL)
            val user = required(DB_USER)
            val password = required(DB_PASSWORD)
            if (url != null && !url.startsWith("jdbc:")) {
                problems += "$DB_URL doit être une URL JDBC (jdbc:mariadb://hôte:3306/silooo)"
                return null
            }
            if (url == null || user == null || password == null) return null
            return DatabaseConfig(url, user, password)
        }

        fun definitionsDir(workingDir: Path): Path? {
            val raw = env[DEFINITIONS_DIR]
            if (raw.isNullOrBlank()) {
                val found = developmentDefinitionsDir(workingDir)
                if (found == null) {
                    problems += "$DEFINITIONS_DIR est absente et le dossier definitions/ du dépôt est introuvable depuis $workingDir"
                }
                return found
            }
            val directory = try {
                Path.of(raw.trim())
            } catch (e: InvalidPathException) {
                problems += "$DEFINITIONS_DIR n'est pas un chemin valable"
                return null
            }
            if (!Files.isDirectory(directory)) {
                problems += "$DEFINITIONS_DIR ne désigne pas un dossier : $directory"
                return null
            }
            return directory
        }

        fun port(): Int? {
            val raw = env[PORT]
            if (raw.isNullOrBlank()) return DEFAULT_PORT
            val port = raw.trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                problems += "$PORT doit être un numéro de port entre 1 et 65535"
                return null
            }
            return port
        }

        fun apiRequestRetention(): Duration? {
            val raw = env[API_REQUESTS_RETENTION_HOURS]
            if (raw.isNullOrBlank()) return Duration.ofHours(DEFAULT_API_REQUESTS_RETENTION_HOURS)
            val hours = raw.trim().toLongOrNull()
            if (hours == null || hours !in 1..MAX_API_REQUESTS_RETENTION_HOURS) {
                problems += "$API_REQUESTS_RETENTION_HOURS doit être un nombre entier d'heures entre 1 et $MAX_API_REQUESTS_RETENTION_HOURS"
                return null
            }
            return Duration.ofHours(hours)
        }

        fun deliveryPoll(): Duration? {
            val raw = env[DELIVERY_POLL_SECONDS]
            if (raw.isNullOrBlank()) return Duration.ofSeconds(DEFAULT_DELIVERY_POLL_SECONDS)
            val seconds = raw.trim().toLongOrNull()
            if (seconds == null || seconds !in 1..MAX_DELIVERY_POLL_SECONDS) {
                problems += "$DELIVERY_POLL_SECONDS doit être un nombre entier de secondes entre 1 et $MAX_DELIVERY_POLL_SECONDS"
                return null
            }
            return Duration.ofSeconds(seconds)
        }

        fun finish() {
            if (problems.isNotEmpty()) {
                throw ConfigException("Configuration invalide : " + problems.joinToString(" ; ") + ".")
            }
        }
    }
}
