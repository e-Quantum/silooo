package com.silooo.core.economy

import com.silooo.core.api.FactoryState
import com.silooo.core.db.VersionConflict
import com.silooo.core.economy.EngineFixture.Companion.OTHER
import com.silooo.core.economy.EngineFixture.Companion.hours
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `settleOffline` : le Donar de l'absence, compté depuis `last_collect_at` de l'entreprise, au plafond de l'entreprise,
 * écrit par le verrou optimiste. La connexion est un enregistreur : on lit l'`UPDATE` exact qui partirait vers
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

    private fun settle(company: CompanyRecord, playerLastSeenAt: Instant? = null): Double =
        fx.figures.settleOffline(fx.connection, company, playerLastSeenAt)

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
    @DisplayName("Rien n'est écrit quand il n'y a rien à payer : même instant, horloge en retard, plafond nul, aucune usine active")
    fun writesNothingWhenThereIsNothingToPay() {
        val company = fx.newCompany()

        assertEquals(0.0, settle(company), "même instant que le dernier calcul")

        fx.clock.now = company.lastCollectAt.minus(hours(1.0))
        assertEquals(0.0, settle(company), "horloge en retard sur la base")

        fx.clock.now = company.lastCollectAt.plus(hours(1.0))
        assertEquals(0.0, settle(company.copy(offlineCapHours = 0.0)), "plafond nul")

        fx.putFactory(company.id, FactoryRecord(fx.starting.factoryId, FactoryState.EN_RUINE, 0, fx.starting.level, null))
        assertEquals(0.0, settle(company), "aucune usine ne produit")

        assertTrue(fx.recording.statements.isEmpty(), "aucune écriture : ${fx.recording.statements}")
    }

    @Test
    @DisplayName("Si un autre associé a écrit entre-temps (zéro ligne touchée), settleOffline lève le conflit de version")
    fun versionConflictIsRaised() {
        val company = fx.newCompany()
        fx.clock.advance(hours(1.0))
        fx.recording.rowsTouched = 0

        assertThrows<VersionConflict> { settle(company) }

        assertEquals(1, fx.recording.statements.size)
        assertEquals(1, fx.recording.statements.single().executions)
    }
}
