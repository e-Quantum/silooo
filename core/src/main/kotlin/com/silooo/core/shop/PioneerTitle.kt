package com.silooo.core.shop

import com.silooo.core.db.Xuid
import com.silooo.core.events.GameEventKind
import com.silooo.core.registration.SiteRegistrations
import com.silooo.core.session.PendingTitles
import java.sql.Connection
import java.time.Clock
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Le titre Pionnier. Le site écrit `site_registrations` (gamertag en clé, `pioneer`) quand un visiteur s'inscrit avant l'ouverture ; le
 * jeu donne le titre. Au `join` ([PendingTitles]), si l'inscription de ce gamertag (même clé que [SiteRegistrations.key] : casse et
 * espaces des bords sans effet) dit `pioneer` et que le joueur n'a pas encore le cosmétique `pionnier`, le service l'écrit dans
 * `player_cosmetics` (source `site`) et publie un événement `ANNONCE` au joueur (`payload.action` = `pionnier`).
 *
 * Une seule fois : l'écriture est une insertion à clé primaire (xuid, cosmétique) et l'annonce ne part que si CET appel a écrit la
 * ligne. Un second `join`, ou un second appel en même temps, ne publie rien. Le titre que le joueur porte reste à l'add-on (il le lit
 * dans `cosmetics` de la réponse de `join`).
 */
class PioneerTitle(
    private val store: ShopStore,
    private val clock: Clock = Clock.systemUTC(),
) : PendingTitles {
    override fun relieve(connection: Connection, xuid: Xuid, gamertag: String) {
        if (!store.isPioneer(connection, SiteRegistrations.key(gamertag))) return
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        if (!store.grantCosmetic(connection, xuid, ShopRules.PIONEER_TITLE_ID, SOURCE_SITE, now)) return
        store.publish(
            connection,
            GameEventKind.ANNONCE,
            xuid,
            buildJsonObject {
                put("action", ACTION)
                put("cosmeticId", ShopRules.PIONEER_TITLE_ID)
            },
        )
    }

    companion object {
        /** La valeur de `player_cosmetics.source` pour un titre que le site fait donner. */
        const val SOURCE_SITE = "site"

        /** Le `payload.action` de l'`ANNONCE` du titre. */
        const val ACTION = "pionnier"
    }
}
