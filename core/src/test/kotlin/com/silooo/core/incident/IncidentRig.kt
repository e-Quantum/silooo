package com.silooo.core.incident

import com.silooo.core.api.FactoryState
import com.silooo.core.db.RecordingDatabase
import com.silooo.core.definitions.Definitions
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.economy.InMemoryCompanyStore
import com.silooo.core.server.ServerPresence
import com.silooo.core.session.CompanyRecord
import com.silooo.core.session.FactoryRecord
import java.sql.Connection
import java.time.Instant

/**
 * Ce qu'il faut pour essayer [IncidentService] et [IncidentTicker] sans base : le moteur d'économie réel, les faux dépôts en mémoire,
 * une présence et une horloge que le test conduit. Le tirage joue à [chancePercent] % de chance par créneau (100 par défaut : chaque
 * créneau tire un incident, pour que le test regarde ce que le service fait d'un tirage, pas la chance ; la fréquence du tirage se
 * teste dans `IncidentDrawTest`). Chaque test prend son propre assemblage, donc ses propres entreprises, numérotées depuis 1.
 */
class IncidentRig(chancePercent: Double = 100.0, wrap: (IncidentStore) -> IncidentStore = { it }) {
    val fx = EngineFixture()
    val companies = InMemoryCompanyStore(fx.store)
    val incidents = InMemoryIncidentStore(fx.store)
    val presence = ServerPresence(clock = fx.clock)
    val database = RecordingDatabase(fx.recording)
    private val incidentDefinitions = fx.definitions.incidents.let { it.copy(frequency = it.frequency.copy(chancePercent = chancePercent)) }
    val draw = IncidentDraw(incidentDefinitions)

    /**
     * Les définitions du dépôt, avec la chance par créneau du test : pour un module Ktor complet, qui bâtit son propre tirage. Bâties à
     * la demande : [Definitions] refuse une chance nulle, que le tirage ([draw]) accepte pour les tests qui ne veulent aucun incident.
     */
    val definitions: Definitions by lazy {
        fx.definitions.let { Definitions(it.economy, it.factories, it.shop, it.world, it.missions, it.personnel, incidentDefinitions, it.city, it.vote, it.pass) }
    }
    val service = IncidentService(fx.store, companies, wrap(incidents), fx.engine, fx.figures, draw, presence, fx.clock)
    val connection: Connection get() = fx.connection

    val engine get() = fx.engine
    val starterId: String get() = fx.starting.factoryId

    /** Les usines ordinaires (sans électricité, ni la Centrale), hors celle de départ, dans l'ordre d'usines.json. */
    val ordinary: List<String> =
        fx.definitions.factories.factories
            .filter { !it.needsElectricity && it.id != fx.starting.factoryId && it.id != fx.definitions.incidents.electricity.powerPlantId }
            .map { it.id }

    fun companyUpdates() = fx.recording.statements.filter { it.sql.startsWith("UPDATE companies") }

    /** Une entreprise du PDG [owner], de l'époque [era], avec la Carrière de départ et [extra] usines ordinaires actives au niveau 5. */
    fun company(extra: Int = 3, era: Int = 1, donar: Double = 100.0, owner: Xuid = EngineFixture.KR): CompanyRecord {
        val company = fx.update(fx.newCompany(owner).copy(donar = donar, era = era, courseNumber = era))
        for (id in ordinary.take(extra)) fx.putFactory(company.id, FactoryRecord(id, FactoryState.ACTIVE, 0, 5, null))
        return company
    }

    /** Pose l'horloge à [at]. */
    fun clockAt(at: Instant) {
        fx.clock.now = at
    }
}

/** Un dépôt d'incidents qui échoue à l'ouverture pour les entreprises de [failing] : la panne de la base d'une seule entreprise. */
class FlakyIncidentStore(private val delegate: IncidentStore) : IncidentStore by delegate {
    val failing = mutableMapOf<Long, Throwable>()

    override fun open(connection: Connection, companyId: Long, factoryId: String, kind: String, slowdown: Double, at: Instant): Long {
        failing[companyId]?.let { throw it }
        return delegate.open(connection, companyId, factoryId, kind, slowdown, at)
    }
}
