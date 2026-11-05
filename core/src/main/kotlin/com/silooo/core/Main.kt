package com.silooo.core

import com.silooo.core.db.HikariDatabase
import com.silooo.core.definitions.DefinitionsException
import com.silooo.core.definitions.DefinitionsLoader
import com.silooo.core.db.Migrations
import com.silooo.core.incident.INCIDENT_TICK_EVERY
import com.silooo.core.registration.HttpDiscordIdentity
import com.silooo.core.shop.DeliveryRelief
import com.silooo.core.shop.JdbcShopStore
import com.silooo.core.site.siteModule
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.silooo.core.Main")

/** Codes de sortie (les mêmes que `tools/verifier.sh` pour l'usage). */
internal const val EXIT_OK = 0
internal const val EXIT_FAILURE = 1
internal const val EXIT_USAGE = 64
internal const val EXIT_CONFIG = 78

/**
 * Deux modes, choisis par l'argument :
 *
 *  - `migrate` : applique les migrations Flyway et sort. C'est la commande du service
 *    `silooo-migrate` du compose, avec le compte migrateur de la base.
 *  - aucun argument : démarrage normal. Le service contrôle d'abord que la base est à jour
 *    (Flyway `validate`), puis écoute. Il n'applique JAMAIS de migration lui-même.
 *
 * Pourquoi deux modes plutôt qu'un service qui migre au démarrage ? Le compte applicatif de
 * silooo-core n'a pas le droit de changer le schéma (docs/base-de-donnees.md), et la base est
 * partagée avec SileneMC : un schéma ne change que par une étape voulue, avant le démarrage, que le
 * déploiement peut rejouer et dont le retour arrière reste lisible. Si une migration manque, le
 * démarrage normal échoue net au lieu de tourner sur un schéma inconnu.
 */
fun main(args: Array<String>) {
    val migrateMode = when {
        args.isEmpty() -> false
        args.size == 1 && args[0] == "migrate" -> true
        else -> {
            System.err.println("Usage : silooo-core [migrate]")
            exitProcess(EXIT_USAGE)
        }
    }
    exitProcess(if (migrateMode) runMigrate(System.getenv()) else runService(System.getenv()))
}

/** Mode `migrate` : seule la base est nécessaire (pas de clé). */
internal fun runMigrate(env: Map<String, String>): Int = guarded {
    val database = Config.database(env)
    val applied = Migrations.migrate(database)
    log.info("Migrations terminées : {} appliquée(s).", applied)
    EXIT_OK
}

/** Démarrage normal : la base et la clé sont obligatoires, sinon le service ne démarre pas. */
internal fun runService(env: Map<String, String>): Int = guarded {
    val config = Config.service(env)
    // Les définitions d'abord : un fichier manquant ou abîmé arrête le démarrage avant de toucher à la base.
    val definitions = DefinitionsLoader.load(config.definitionsDir)
    log.info("Définitions chargées depuis {}.", config.definitionsDir)
    Migrations.validate(config.database)
    val database = HikariDatabase.connect(config.database)
    try {
        val shopStore = JdbcShopStore()
        val dependencies = Dependencies(
            database = database,
            apiKey = config.apiKey,
            definitions = definitions,
            shopStore = shopStore,
            apiRequestRetention = config.apiRequestRetention,
            incidentTick = INCIDENT_TICK_EVERY,
            deliveries = DeliveryRelief(database, shopStore),
            deliveryPoll = config.deliveryPoll,
            siteMode = config.siteMode,
            siteUrl = config.siteUrl,
            discordUrl = config.discordUrl,
            siteSettings = config.siteSettings,
            siteAdmission = config.siteAdmission,
            discordIdentity = config.discord?.let { HttpDiscordIdentity(it) },
            siteTrustedProxy = config.siteTrustedProxy,
            payNowWebhookSecret = config.payNowWebhookSecret,
        )
        log.info("silooo-core écoute sur le port {}.", config.port)
        // 0.0.0.0 : dans le conteneur, seul le réseau Docker atteint ce port. Le compose ne le publie jamais.
        val api = embeddedServer(CIO, port = config.port, host = "0.0.0.0") { siloooModule(dependencies) }
        // Le site public : un second serveur dans la même JVM, sur son propre port. Les routes de l'API n'existent
        // pas sur ce port (le Caddy de SileneMC ne joint que lui), et les tests de l'API n'ont pas à changer.
        val site = embeddedServer(CIO, port = config.sitePort, host = "0.0.0.0") { siteModule(dependencies) }
        try {
            site.start(wait = false)
            log.info("Le site écoute sur le port {} en mode {}.", config.sitePort, config.siteMode.word)
            api.start(wait = true)
        } finally {
            site.stop(1_000, 2_000)
            api.stop(1_000, 2_000)
        }
    } finally {
        database.close()
    }
    EXIT_OK
}

/** Transforme une erreur de configuration ou d'exécution en code de sortie, sans jamais afficher un secret. */
private fun guarded(block: () -> Int): Int =
    try {
        block()
    } catch (e: ConfigException) {
        System.err.println(e.message)
        EXIT_CONFIG
    } catch (e: DefinitionsException) {
        // Un fichier de definitions/ manque ou est abîmé : c'est de la configuration, pas un bogue.
        System.err.println(e.message)
        EXIT_CONFIG
    } catch (e: Exception) {
        log.error("silooo-core s'arrête : {}", e.message, e)
        EXIT_FAILURE
    }
