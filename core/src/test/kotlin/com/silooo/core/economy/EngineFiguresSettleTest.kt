package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.db.VersionConflict
import com.silooo.core.economy.EngineFixture.Companion.OTHER
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
import com.silooo.core.session.Regime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `settle` : le Donar écoulé depuis `last_collect_at` de l'entreprise, versé selon le régime (hors ligne : plafond de
 * l'entreprise et efficacité ; en ligne : revenu plein), écrit par le verrou optimiste. La connexion est un enregistreur : on lit l'`UPDATE` exact qui partirait vers
 * MariaDB. Que cet `UPDATE` change bien la base se vérifie dans `EngineFiguresSqlTest`.
 */
class EngineFiguresSettleTest {
    private val fx = EngineFixture()
    private val engine = fx.engine
    private val offline = fx.definitions.economy.offline
    private val update = "UPDATE companies SET donar = ?, donar_earned = ?, last_collect_at = ?, version = version + 1 WHERE id = ? AND version = ?"

    /** Le revenu de l'entreprise neuve (Carrière seule) au multiplicateur donné, par défaut sans Lingots ni étoile. */
    private fun startingRate(multiplier: Double = engine.globalMultiplier(0.0, 0)): Double =
        engine.ratePerHour(engine.startingLevels(), multiplier)

    private fun settle(company: CompanyRecord, regime: Regime = Regime.OFFLINE, playerLastSeenAt: Instant? = null): Double =
        fx.figures.settle(fx.connection, company, regime, playerLastSeenAt)

    /** Le seul `UPDATE` noté doit être celui de l'encaissement, avec ces paramètres exacts. */
    private fun assertWrittenOnce(company: CompanyRecord, earned: Double, writtenAt: Instant = fx.clock.instant()) {
        val statement = fx.recording.statements.single()
        assertEquals(update, statement.sql)
        assertEquals(1, statement.executions)
        assertEquals(company.donar + earned, statement.parameters[1], "donar")
        assertEquals(company.donarEarned + earned, statement.parameters[2], "donar_earned")
        assertEquals(LocalDateTime.ofInstant(writtenAt, ZoneOffset.UTC), statement.parameters[3], "last_collect_at")
        assertEquals(company.id, statement.parameters[4], "id")
        assertEquals(company.version, statement.parameters[5], "version attendue")
    }

    @Test
    @DisplayName("Une absence plus courte que le plafond rapporte revenu x heures x efficacité, ajouté au Donar de l'entreprise")
    fun paysTheEngineFormulaForTheClockGap() {
        val company = fx.update(fx.newCompany().copy(donar = 125.0, donarEarned = 400.0, version = 3))
        val away = offline.capHours / 2
        fx.clock.advance(hours(away))

        val earned = settle(company)

        assertTrue(earned > 0.0)
        assertEquals(engine.offlineEarnings(startingRate(), away, company.offlineCapHours), earned)
        assertGolden(startingRate() * away * offline.efficiency, earned, "revenu x heures x efficacité")
        assertWrittenOnce(company, earned)
    }

    @Test
    @DisplayName("Au-delà du plafond de l'entreprise, le surplus est perdu : le gain est celui du plafond et l'horloge repart de maintenant")
    fun capIsRespected() {
        val company = fx.newCompany()
        fx.clock.advance(hours(offline.capHours + 10))

        val earned = settle(company)

        assertGolden(startingRate() * offline.capHours * offline.efficiency, earned, "gain au plafond")
        assertEquals(engine.offlineEarnings(startingRate(), offline.capHours), earned)
        assertWrittenOnce(company, earned)
    }

    @Test
    @DisplayName("Un plafond plus court dans la colonne de l'entreprise l'emporte sur horsLigne.plafondHeures")
    fun shorterColumnCapWins() {
        val cap = offline.capHours / 2
        val company = fx.update(fx.newCompany().copy(offlineCapHours = cap))
        fx.clock.advance(hours(offline.capHours))

        val earned = settle(company)

        assertGolden(startingRate() * cap * offline.efficiency, earned, "gain au plafond de l'entreprise")
        assertWrittenOnce(company, earned)
    }

    @Test
    @DisplayName("Un plafond étendu dans la colonne de l'entreprise l'emporte aussi : les améliorations allongent le hors ligne")
    fun longerColumnCapWins() {
        val cap = offline.capHours * 2
        val company = fx.update(fx.newCompany().copy(offlineCapHours = cap))
        fx.clock.advance(hours(cap * 2))

        val earned = settle(company)

        assertGolden(startingRate() * cap * offline.efficiency, earned, "gain au plafond étendu")
        assertWrittenOnce(company, earned)
    }

    @Test
    @DisplayName("Le revenu est celui d'aujourd'hui : moyenne des Lingots des membres et étoiles de maintenant")
    fun usesTodaysMultiplier() {
        val company = fx.newCompany(ownerLingots = 4)
        fx.addMember(company.id, OTHER, lingots = 8)
        val starred = fx.update(fx.store.loadCompany(fx.connection, company.id).copy(stars = 1))
        val away = offline.capHours / 2
        fx.clock.advance(hours(away))

        val earned = settle(starred)

        val rate = startingRate(engine.globalMultiplier(6.0, 1))
        assertGolden(engine.offlineEarnings(rate, away, starred.offlineCapHours), earned, "gain avec Lingots et étoile")
        assertTrue(earned > startingRate() * away * offline.efficiency)
        assertWrittenOnce(starred, earned)
    }

    @Test
    @DisplayName("Seules les usines ACTIVE connues comptent dans le gain hors ligne")
    fun onlyActiveKnownFactoriesEarn() {
        val (ruined, restoring) = fx.otherFactories()
        val company = fx.newCompany()
        fx.putFactory(company.id, FactoryRecord(ruined, FactoryState.EN_RUINE, 0, 20, null))
        fx.putFactory(company.id, FactoryRecord(restoring, FactoryState.REMISE_EN_ETAT, 1, 5, null))
        fx.putFactory(company.id, FactoryRecord("usine-disparue", FactoryState.ACTIVE, 0, 50, null))
        val away = offline.capHours / 2
        fx.clock.advance(hours(away))

        val earned = settle(company)

        assertGolden(startingRate() * away * offline.efficiency, earned, "gain de la Carrière seule")
        assertWrittenOnce(company, earned)
    }

    @Test
    @DisplayName("L'absence se compte depuis last_collect_at de l'entreprise, jamais depuis le départ du joueur")
    fun ignoresThePlayersLastSeenAt() {
        val company = fx.newCompany()
        fx.clock.advance(hours(offline.capHours / 2))

        val unknown = settle(company, playerLastSeenAt = null)
        val longAgo = settle(company, playerLastSeenAt = company.lastCollectAt.minus(hours(1000.0)))
        val justNow = settle(company, playerLastSeenAt = fx.clock.instant())

        assertTrue(unknown > 0.0)
        assertEquals(unknown, longAgo)
        assertEquals(unknown, justNow)
    }

    @Test
    @DisplayName("L'horloge s'écrit à la milliseconde, comme DATETIME(3) la garde")
    fun writesMillisecondPrecision() {
        val company = fx.newCompany()
        fx.clock.now = company.lastCollectAt.plus(hours(1.0)).plusNanos(789_123_456)

        settle(company)

        val written = fx.recording.statements.single().parameters[3] as LocalDateTime
        assertEquals(789_000_000, written.nano)
    }

    @Test
    @DisplayName("Rien n'est écrit quand le temps n'a pas avancé pour la base : même instant, ou horloge du service en retard, dans les deux régimes")
    fun writesNothingWhenNoTimeElapsed() {
        val company = fx.newCompany()

        for (regime in Regime.entries) {
            fx.clock.now = company.lastCollectAt
            assertEquals(0.0, settle(company, regime), "même instant que le dernier calcul ($regime)")

            fx.clock.now = company.lastCollectAt.minus(hours(1.0))
            assertEquals(0.0, settle(company, regime), "horloge en retard sur la base ($regime)")
        }

        assertTrue(fx.recording.statements.isEmpty(), "aucune écriture : ${fx.recording.statements}")
    }

    @Test
    @DisplayName("Un gain nul sur du temps écoulé remet quand même la date à maintenant : plafond nul, aucune usine qui produit")
    fun zeroGainStillResetsTheDate() {
        val company = fx.newCompany()
        fx.clock.now = company.lastCollectAt.plus(hours(1.0))

        assertEquals(0.0, settle(company.copy(offlineCapHours = 0.0)), "plafond nul, hors ligne")
        val capped = fx.recording.statements.single()
        assertEquals(LocalDateTime.ofInstant(fx.clock.instant(), ZoneOffset.UTC), capped.parameters[3], "last_collect_at, plafond nul")
        assertEquals(company.donar, capped.parameters[1], "donar inchangé")
        assertEquals(company.donarEarned, capped.parameters[2], "donar_earned inchangé")

        fx.recording.statements.clear()
        fx.putFactory(company.id, FactoryRecord(fx.starting.factoryId, FactoryState.EN_RUINE, 0, fx.starting.level, null))
        for (regime in Regime.entries) {
            assertEquals(0.0, settle(company, regime), "aucune usine ne produit ($regime)")
        }
        assertEquals(Regime.entries.size, fx.recording.statements.size, "une écriture par régime, qui ne verse rien")
        for (statement in fx.recording.statements) {
            assertEquals(company.donar, statement.parameters[1], "donar inchangé")
            assertEquals(LocalDateTime.ofInstant(fx.clock.instant(), ZoneOffset.UTC), statement.parameters[3], "last_collect_at")
        }
    }

    @Test
    @DisplayName("En ligne : revenu plein sur toutes les heures, sans efficacité, ajouté au Donar de l'entreprise")
    fun onlinePaysTheFullRate() {
        val company = fx.update(fx.newCompany().copy(donar = 125.0, donarEarned = 400.0, version = 3))
        val away = offline.capHours / 2
        fx.clock.advance(hours(away))

        val earned = settle(company, Regime.ONLINE)

        assertEquals(engine.onlineEarnings(startingRate(), away), earned)
        assertGolden(startingRate() * away, earned, "revenu x heures, sans efficacité")
        assertTrue(earned > engine.offlineEarnings(startingRate(), away, company.offlineCapHours), "plus que le hors ligne")
        assertWrittenOnce(company, earned)
    }

    @Test
    @DisplayName("En ligne, le plafond de l'entreprise ne coupe rien : toutes les heures écoulées sont payées, même au-delà du plafond ou avec un plafond nul")
    fun onlineHasNoCap() {
        val company = fx.newCompany()
        val away = offline.capHours + 10
        fx.clock.advance(hours(away))

        val earned = settle(company, Regime.ONLINE)

        assertGolden(startingRate() * away, earned, "toutes les heures, au revenu plein")
        assertWrittenOnce(company, earned)

        fx.recording.statements.clear()
        assertEquals(earned, settle(company.copy(offlineCapHours = 0.0), Regime.ONLINE), "un plafond nul ne change rien en ligne")
        assertEquals(1, fx.recording.statements.size)
    }

    @Test
    @DisplayName("En ligne, le revenu est aussi celui d'aujourd'hui : Lingots des membres et étoiles de maintenant")
    fun onlineUsesTodaysMultiplier() {
        val company = fx.newCompany(ownerLingots = 4)
        fx.addMember(company.id, OTHER, lingots = 8)
        val starred = fx.update(fx.store.loadCompany(fx.connection, company.id).copy(stars = 1))
        val away = offline.capHours / 2
        fx.clock.advance(hours(away))

        val earned = settle(starred, Regime.ONLINE)

        assertGolden(engine.onlineEarnings(startingRate(engine.globalMultiplier(6.0, 1)), away), earned, "gain en ligne avec Lingots et étoile")
        assertWrittenOnce(starred, earned)
    }

    @Test
    @DisplayName("pendingProduction rend les mêmes montants que settle sans rien écrire : toutes les heures en ligne, les heures plafonnées hors ligne")
    fun pendingProductionMatchesSettleWithoutWriting() {
        val company = fx.newCompany()
        val away = offline.capHours + 10
        fx.clock.advance(hours(away))
        val factories = fx.store.loadFactories(fx.connection, company.id)

        val online = fx.figures.pendingProduction(fx.connection, company, factories, Regime.ONLINE)
        val offlineProduction = fx.figures.pendingProduction(fx.connection, company, factories, Regime.OFFLINE)

        assertEquals(engine.onlineEarnings(startingRate(), away), online.earned)
        assertGolden(away, online.countedHours, "heures en ligne : toutes celles de l'horloge")
        assertEquals(engine.offlineEarnings(startingRate(), away, company.offlineCapHours), offlineProduction.earned)
        assertGolden(offline.capHours, offlineProduction.countedHours, "heures hors ligne : au plafond")
        assertEquals(fx.clock.instant(), online.at)
        assertEquals(fx.clock.instant(), offlineProduction.at)
        assertTrue(fx.recording.statements.isEmpty(), "pendingProduction n'écrit rien : ${fx.recording.statements}")
    }

    @Test
    @DisplayName("Si un autre associé a écrit entre-temps (zéro ligne touchée), settle lève le conflit de version")
    fun versionConflictIsRaised() {
        val company = fx.newCompany()
        fx.clock.advance(hours(1.0))
        fx.recording.rowsTouched = 0

        assertThrows<VersionConflict> { settle(company) }

        assertEquals(1, fx.recording.statements.size)
        assertEquals(1, fx.recording.statements.single().executions)
    }
}
