package com.silooo.core.db

import com.silooo.core.DatabaseConfig
import org.flywaydb.core.Flyway

/**
 * Les migrations Flyway de `src/main/resources/db/migration/` (V1__init.sql en tête).
 *
 * Deux usages, et c'est voulu :
 *  - [migrate] : le mode `migrate` du service, lancé par le service `silooo-migrate` du compose
 *    avant chaque démarrage, avec le compte migrateur. Il applique ce qui manque et s'arrête.
 *  - [validate] : le démarrage normal, avec le compte applicatif. Il ne modifie rien. Si une
 *    migration manque (ou si une migration appliquée a été changée), il lève une exception et le
 *    service ne démarre pas : mieux vaut tomber net qu'écrire dans un schéma qu'on ne connaît pas.
 *
 * Une migration appliquée ne se modifie jamais (docs/base-de-donnees.md) : Flyway le contrôle avec
 * la somme de contrôle de chaque fichier.
 */
object Migrations {

    /**
     * Applique les migrations en attente et rend leur nombre. [target] vaut `latest` (tout) ou un
     * numéro de version, par exemple `1` pour s'arrêter après V1.
     */
    fun migrate(config: DatabaseConfig, target: String = "latest"): Int =
        flyway(config, target).migrate().migrationsExecuted

    /** Lève une `FlywayException` si la base n'est pas exactement à jour avec les fichiers. */
    fun validate(config: DatabaseConfig) {
        flyway(config, "latest").validate()
    }

    private fun flyway(config: DatabaseConfig, target: String): Flyway =
        Flyway.configure()
            .dataSource(config.url, config.user, config.password)
            .locations("classpath:db/migration")
            .target(target)
            // La base est partagée avec SileneMC : jamais de « clean », même par erreur.
            .cleanDisabled(true)
            .load()
}
