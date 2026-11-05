package com.silooo.core.shop

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.Balances
import com.silooo.core.api.CatalogItem
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.Idempotency
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.db.RecordingConnection
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.events.GameEventKind
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.shop.InMemoryShopStore.BoostRow
import com.silooo.core.shop.InMemoryShopStore.CosmeticRow
import com.silooo.core.shop.InMemoryShopStore.Movement
import com.silooo.core.shop.InMemoryShopStore.Purchase
import com.silooo.core.shop.InMemoryShopStore.RankRow
import com.silooo.core.siloooModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `catalog` et `buyItem` avec des faux (pas de base) : le vrai [ShopRules] sur les `definitions/` du dépôt, un faux dépôt
 * ([InMemoryShopStore]) qui tient les soldes du faux dépôt de joueurs, et une horloge que le test avance. Les chiffres attendus
 * (prix, jours, Gemmes rendues) sortent de [ShopRules] et de `boutique.json`, jamais d'un littéral. Une base qui annule une
 * transaction ne se joue pas ici : ce que le service a écrit avant un refus ne s'efface pas dans le faux, donc chaque refus se
 * vérifie sur ce qu'il laisse AVANT toute écriture. Le SQL est dans `ShopSqlTest`, le contrat dans `ContractTest`.
 */
class ShopRoutesTest {
    private val key = "cle-de-test"
    private val kr = EngineFixture.KR
    private val friend = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER
    private val noon = EngineFixture.START

    private val rules = ShopRules(TestDefinitions.loaded)
    private val grades = rules.grades
    private val pilier = rules.subscriptions.single { it.id == ShopRules.PILIER_ID }
    private val perso = rules.subscriptions.single { it.requiredGradeId != null }
    private val ultimate = checkNotNull(perso.requiredGradeId)

    private class Harness(val sessions: InMemorySessionStore, val shop: InMemoryShopStore, val clock: MutableClock) {
        /** Inscrit [xuid] (il est alors connu du service) avec [gemmes] Gemmes. */
        fun player(xuid: Xuid, gamertag: String, gemmes: Long = 0L) {
            sessions.enterPlayer(RecordingConnection().connection, xuid, gamertag)
            sessions.balances[xuid] = Balances(0, gemmes, 0, 0)
        }

        fun gemmes(xuid: Xuid): Long = shop.gemmes(xuid)
    }

    private fun withApp(block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val clock = MutableClock(noon)
        val sessions = InMemorySessionStore(noon)
        val shop = InMemoryShopStore(sessions)
        val database = PassThroughDatabase()
        application {
            siloooModule(
                Dependencies(
                    database = database,
                    definitions = TestDefinitions.loaded,
                    apiKey = key,
                    idempotency = Idempotency(database, InMemoryApiRequestStore(noon)),
                    sessionStore = sessions,
                    clock = clock,
                    shopStore = shop,
                ),
            )
        }
        block(Harness(sessions, shop, clock))
    }

    // --- outils

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private suspend fun ApplicationTestBuilder.buy(
        itemId: String,
        n: Int = 1,
        actor: Xuid = kr,
        forXuid: Xuid? = null,
        requestId: String = requestId(n),
    ): HttpResponse {
        val gift = forXuid?.let { ""","forXuid":"${it.text}"""" }.orEmpty()
        return client.post("/v1/shop/purchases") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"$requestId","actor":"${actor.text}","itemId":"$itemId"$gift}""")
        }
    }

    private suspend fun ApplicationTestBuilder.catalogOf(actor: Xuid): HttpResponse =
        client.get("/v1/shop/catalog?actor=${actor.text}") { header(SERVER_KEY_HEADER, key) }

    private suspend fun balancesOf(response: HttpResponse): Balances {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return SiloooJson.decodeFromString(Balances.serializer(), body)
    }

    private suspend fun refusalOf(response: HttpResponse, code: String): RefusedBody {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Conflict, response.status, body)
        return SiloooJson.decodeFromString(RefusedBody.serializer(), body).also { assertEquals(code, it.reason, body) }
    }

    private suspend fun assertBadRequest(response: HttpResponse, what: String) {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.BadRequest, response.status, "$what : $body")
        assertEquals(ErrorCodes.BAD_REQUEST, SiloooJson.decodeFromString(ErrorBody.serializer(), body).reason, what)
    }

    private suspend fun catalogItems(response: HttpResponse): List<CatalogItem> {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return SiloooJson.decodeFromString(ListSerializer(CatalogItem.serializer()), body)
    }

    /** Rien n'a été écrit : ni achat, ni mouvement de Gemmes, ni grade, ni abonnement, ni cosmétique, ni boost, ni événement. */
    private fun assertNothingWritten(app: Harness) {
        assertTrue(app.shop.purchases.isEmpty(), "achats : ${app.shop.purchases}")
        assertTrue(app.shop.movements.isEmpty(), "mouvements : ${app.shop.movements}")
        assertTrue(app.shop.ranks.values.all { it.isEmpty() }, "grades : ${app.shop.ranks}")
        assertTrue(app.shop.subscriptions.isEmpty(), "abonnements : ${app.shop.subscriptions}")
        assertTrue(app.shop.cosmetics.values.all { it.isEmpty() }, "cosmétiques : ${app.shop.cosmetics}")
        assertTrue(app.shop.boosts.isEmpty(), "boosts : ${app.shop.boosts}")
        assertTrue(app.shop.published.isEmpty(), "événements : ${app.shop.published}")
    }

    // --- achat de grade

    @Test
    @DisplayName("buyItem : le grade est donné à l'acheteur (source achat), ses Gemmes baissent du prix, l'achat et le mouvement sont notés")
    fun buyingTheFirstGrade() = withApp { app ->
        val first = grades[0]
        app.player(kr, "Kr", gemmes = first.gems + 7)

        val balances = balancesOf(buy(first.id))

        assertEquals(7L, balances.gemmes)
        assertEquals(listOf(RankRow(first.id, noon, "achat")), app.shop.ranksOf(kr))
        assertEquals(listOf(Purchase(kr, first.id, first.gems, null, requestId(1), noon)), app.shop.purchases)
        assertEquals(listOf(Movement(kr, -first.gems, "boutique", first.id)), app.shop.movements)
        assertTrue(app.shop.published.isEmpty(), "un achat n'écrit aucun événement")
    }

    @Test
    @DisplayName("buyItem rejoué avec le même requestId : la même réponse, rien n'est payé deux fois")
    fun replayedPurchaseIsPaidOnce() = withApp { app ->
        val first = grades[0]
        app.player(kr, "Kr", gemmes = first.gems + 7)

        val body = buy(first.id).bodyAsText()
        val replay = buy(first.id)

        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(body, replay.bodyAsText())
        assertEquals(1, app.shop.purchases.size)
        assertEquals(1, app.shop.movements.size)
        assertEquals(7L, app.gemmes(kr))
    }

    @Test
    @DisplayName("buyItem : le requestId est gardé sous sa forme canonique (minuscules), celle de shop_purchases.request_id")
    fun requestIdIsStoredCanonical() = withApp { app ->
        app.player(kr, "Kr", gemmes = grades[0].gems)
        val raw = "6F1C2B7E-0D4A-4A53-9C1E-3F6A8D2B5E90"

        balancesOf(buy(grades[0].id, requestId = raw))

        assertEquals(listOf(raw.lowercase()), app.shop.purchases.map { it.requestId })
    }

    @Test
    @DisplayName("buyItem : les six grades s'achètent dans l'ordre, chacun à son prix ; le dernier racheté donne DEJA_POSSEDE")
    fun buyingEveryGradeInOrder() = withApp { app ->
        app.player(kr, "Kr", gemmes = grades.sumOf { it.gems } + 3)

        for ((index, grade) in grades.withIndex()) {
            val before = app.gemmes(kr)
            assertEquals(before - grade.gems, balancesOf(buy(grade.id, n = index + 1)).gemmes, grade.id)
        }

        assertEquals(3L, app.gemmes(kr))
        assertEquals(grades.map { it.id }, app.shop.ranksOf(kr).map { it.rankId })
        assertEquals(grades.map { -it.gems }, app.shop.movements.map { it.delta })
        assertEquals(grades.map { it.id }, app.shop.movements.map { it.refId })
        refusalOf(buy(grades.last().id, n = 50), RefusalCodes.ALREADY_OWNED)
        assertEquals(grades.size, app.shop.purchases.size)
    }

    @Test
    @DisplayName("buyItem : avec juste le prix, l'achat passe et le solde tombe à zéro")
    fun exactlyEnoughGemmes() = withApp { app ->
        app.player(kr, "Kr", gemmes = grades[0].gems)

        assertEquals(0L, balancesOf(buy(grades[0].id)).gemmes)
    }

    @Test
    @DisplayName("buyItem : un grade déjà possédé donne 409 DEJA_POSSEDE, sans rien retirer")
    fun ownedGradeIsRefused() = withApp { app ->
        app.player(kr, "Kr", gemmes = grades[0].gems * 3)
        balancesOf(buy(grades[0].id, n = 1))
        val purchases = app.shop.purchases.size
        val movements = app.shop.movements.size
        val held = app.gemmes(kr)

        refusalOf(buy(grades[0].id, n = 2), RefusalCodes.ALREADY_OWNED)

        assertEquals(purchases, app.shop.purchases.size)
        assertEquals(movements, app.shop.movements.size)
        assertEquals(held, app.gemmes(kr))
    }

    @Test
    @DisplayName("buyItem : un grade qui saute une marche donne 409 GRADE_PRECEDENT_REQUIS, même avec assez de Gemmes")
    fun skippingAGradeIsRefused() = withApp { app ->
        app.player(kr, "Kr", gemmes = grades.sumOf { it.gems })

        refusalOf(buy(grades[1].id), RefusalCodes.PREVIOUS_RANK_REQUIRED)
        refusalOf(buy(grades.last().id, n = 2), RefusalCodes.PREVIOUS_RANK_REQUIRED)

        assertNothingWritten(app)
    }

    @Test
    @DisplayName("buyItem : sans assez de Gemmes, 409 PAS_ASSEZ_DE_GEMMES avec ce qui manque, sans rien retirer ni donner")
    fun notEnoughGemmes() = withApp { app ->
        val first = grades[0]
        app.player(kr, "Kr", gemmes = first.gems - 1)

        val refusal = refusalOf(buy(first.id), RefusalCodes.NOT_ENOUGH_GEMS)

        assertEquals(1.0, refusal.missing)
        assertEquals(first.gems - 1, app.gemmes(kr))
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("buyItem : sans aucune Gemme, ce qui manque est le prix entier")
    fun noGemmesAtAll() = withApp { app ->
        app.player(kr, "Kr")

        val refusal = refusalOf(buy(grades[0].id), RefusalCodes.NOT_ENOUGH_GEMS)

        assertEquals(grades[0].gems.toDouble(), refusal.missing)
    }

    // --- les 400

    @Test
    @DisplayName("buyItem : un article inconnu, vide ou que le jeu ne vend pas encore donne 400, sans rien écrire")
    fun unknownItemIsABadRequest() = withApp { app ->
        app.player(kr, "Kr", gemmes = 100_000)

        for ((n, itemId) in listOf("inconnu", "MVIP", "cosmetique-1", "pass-saison", ShopRules.PIONEER_TITLE_ID, "").withIndex()) {
            assertBadRequest(buy(itemId, n = n + 1), "article « $itemId »")
        }

        assertNothingWritten(app)
        assertEquals(100_000L, app.gemmes(kr))
    }

    @Test
    @DisplayName("buyItem : un acteur que le service ne connaît pas donne 400, sans rien écrire")
    fun unknownBuyerIsABadRequest() = withApp { app ->
        app.player(friend, "Ami", gemmes = 100_000)

        assertBadRequest(buy(grades[0].id, actor = stranger), "acheteur inconnu")

        assertNothingWritten(app)
    }

    @Test
    @DisplayName("buyItem en cadeau : un destinataire que le service ne connaît pas donne 400, l'acheteur ne paie rien")
    fun unknownRecipientIsABadRequest() = withApp { app ->
        app.player(kr, "Kr", gemmes = 100_000)

        assertBadRequest(buy(grades[0].id, forXuid = stranger), "destinataire inconnu")

        assertNothingWritten(app)
        assertEquals(100_000L, app.gemmes(kr))
    }

    @Test
    @DisplayName("buyItem : un requestId déjà noté dans shop_purchases (sa réponse gardée a été purgée) donne 400 et ne paie rien")
    fun requestIdAlreadyUsedByAPurchase() = withApp { app ->
        app.player(kr, "Kr", gemmes = grades[0].gems)
        app.shop.purchases += Purchase(kr, grades[1].id, grades[1].gems, null, requestId(5), noon)

        assertBadRequest(buy(grades[0].id, n = 5), "requestId déjà servi")

        assertEquals(grades[0].gems, app.gemmes(kr))
        assertTrue(app.shop.movements.isEmpty())
        assertTrue(app.shop.ranksOf(kr).isEmpty())
    }

    // --- cadeau

    @Test
    @DisplayName("buyItem en cadeau : l'acheteur paie, le destinataire reçoit le grade (source cadeau), la réponse est le solde de l'acheteur")
    fun giftingAGrade() = withApp { app ->
        val first = grades[0]
        app.player(kr, "Kr", gemmes = first.gems + 9)
        app.player(friend, "Ami", gemmes = 4)

        val balances = balancesOf(buy(first.id, forXuid = friend))

        assertEquals(9L, balances.gemmes, "le solde de l'acheteur")
        assertEquals(9L, app.gemmes(kr))
        assertEquals(4L, app.gemmes(friend), "le destinataire ne reçoit pas de Gemmes")
        assertEquals(listOf(RankRow(first.id, noon, "cadeau")), app.shop.ranksOf(friend))
        assertTrue(app.shop.ranksOf(kr).isEmpty(), "l'acheteur n'a pas le grade")
        assertEquals(listOf(Purchase(kr, first.id, first.gems, friend, requestId(1), noon)), app.shop.purchases)
        assertEquals(listOf(Movement(kr, -first.gems, "boutique", first.id)), app.shop.movements)
    }

    @Test
    @DisplayName("buyItem en cadeau : les vérifications portent sur le destinataire, pas sur l'acheteur")
    fun giftChecksTheRecipient() = withApp { app ->
        app.player(kr, "Kr", gemmes = grades.sumOf { it.gems })
        app.player(friend, "Ami")
        // L'acheteur a le premier grade ; l'ami, non.
        balancesOf(buy(grades[0].id, n = 1))
        // L'ami n'a rien : le deuxième grade saute une marche pour lui, quoi que l'acheteur possède.
        refusalOf(buy(grades[1].id, n = 2, forXuid = friend), RefusalCodes.PREVIOUS_RANK_REQUIRED)
        // Le premier grade, déjà à l'acheteur, est encore à vendre pour l'ami.
        balancesOf(buy(grades[0].id, n = 3, forXuid = friend))
        // L'ami l'a maintenant : un second cadeau du même grade est refusé.
        refusalOf(buy(grades[0].id, n = 4, forXuid = friend), RefusalCodes.ALREADY_OWNED)
        // Et le deuxième grade passe pour l'ami.
        balancesOf(buy(grades[1].id, n = 5, forXuid = friend))

        assertEquals(listOf(grades[0].id, grades[1].id), app.shop.ranksOf(friend).map { it.rankId })
        assertEquals(listOf(grades[0].id), app.shop.ranksOf(kr).map { it.rankId })
    }

    @Test
    @DisplayName("buyItem en cadeau, sans assez de Gemmes : c'est l'acheteur qui doit les avoir, même si le destinataire en a")
    fun giftNeedsTheBuyersGemmes() = withApp { app ->
        val first = grades[0]
        app.player(kr, "Kr", gemmes = 1)
        app.player(friend, "Ami", gemmes = first.gems * 10)

        val refusal = refusalOf(buy(first.id, forXuid = friend), RefusalCodes.NOT_ENOUGH_GEMS)

        assertEquals((first.gems - 1).toDouble(), refusal.missing)
        assertTrue(app.shop.ranksOf(friend).isEmpty())
        assertEquals(first.gems * 10, app.gemmes(friend))
    }

    @Test
    @DisplayName("buyItem : se l'offrir à soi-même est un achat pour soi (source achat, pas de destinataire noté)")
    fun giftToSelfIsAPlainPurchase() = withApp { app ->
        app.player(kr, "Kr", gemmes = grades[0].gems)

        balancesOf(buy(grades[0].id, forXuid = kr))

        assertEquals(listOf("achat"), app.shop.ranksOf(kr).map { it.source })
        assertEquals(listOf(null), app.shop.purchases.map { it.recipient })
    }

    // --- le Pilier

    @Test
    @DisplayName("buyItem pilier : l'abonnement commence maintenant et dure `jours` jours, l'acheteur paie, `gemmesRendues` lui reviennent aussitôt")
    fun buyingThePilier() = withApp { app ->
        app.player(kr, "Kr", gemmes = pilier.gems + 5)

        val balances = balancesOf(buy(pilier.id))

        assertEquals(5L + pilier.gemsReturned, balances.gemmes)
        assertEquals(
            SubscriptionTerm(noon, noon.plus(Duration.ofDays(pilier.days.toLong())), 1),
            app.shop.loadSubscription(RecordingConnection().connection, kr, pilier.id),
        )
        assertEquals(
            listOf(Movement(kr, -pilier.gems, "boutique", pilier.id), Movement(kr, pilier.gemsReturned, "pilier", pilier.id)),
            app.shop.movements,
        )
        assertEquals(listOf(Purchase(kr, pilier.id, pilier.gems, null, requestId(1), noon)), app.shop.purchases)
    }

    @Test
    @DisplayName("buyItem pilier : racheté en cours de route, la fin recule de `jours` jours et le début reste ; chaque achat rend ses Gemmes")
    fun extendingThePilier() = withApp { app ->
        app.player(kr, "Kr", gemmes = pilier.gems * 3)
        balancesOf(buy(pilier.id, n = 1))
        val firstEnd = noon.plus(Duration.ofDays(pilier.days.toLong()))
        app.clock.advance(Duration.ofDays(10))

        balancesOf(buy(pilier.id, n = 2))

        assertEquals(SubscriptionTerm(noon, firstEnd.plus(Duration.ofDays(pilier.days.toLong())), 2), app.shop.subscriptions.getValue(kr to pilier.id))
        assertEquals(pilier.gems * 3 - 2 * (pilier.gems - pilier.gemsReturned), app.gemmes(kr))
        assertEquals(2, app.shop.movements.count { it.reason == "pilier" })
    }

    @Test
    @DisplayName("buyItem pilier : racheté après la fin, il repart de maintenant ; le compte des mois continue")
    fun restartingTheExpiredPilier() = withApp { app ->
        app.player(kr, "Kr", gemmes = pilier.gems * 3)
        balancesOf(buy(pilier.id, n = 1))
        app.clock.advance(Duration.ofDays(pilier.days.toLong() + 30))

        balancesOf(buy(pilier.id, n = 2))

        val now = app.clock.instant()
        assertEquals(SubscriptionTerm(now, now.plus(Duration.ofDays(pilier.days.toLong())), 2), app.shop.subscriptions.getValue(kr to pilier.id))
    }

    @Test
    @DisplayName("buyItem pilier en cadeau : le destinataire reçoit l'abonnement et les Gemmes rendues, l'acheteur ne fait que payer")
    fun giftingThePilier() = withApp { app ->
        app.player(kr, "Kr", gemmes = pilier.gems)
        app.player(friend, "Ami", gemmes = 1)

        val balances = balancesOf(buy(pilier.id, forXuid = friend))

        assertEquals(0L, balances.gemmes, "l'acheteur n'est pas remboursé")
        assertEquals(1L + pilier.gemsReturned, app.gemmes(friend), "les Gemmes rendues vont au destinataire")
        assertTrue(app.shop.subscriptions.containsKey(friend to pilier.id))
        assertTrue(!app.shop.subscriptions.containsKey(kr to pilier.id))
        assertEquals(
            listOf(Movement(kr, -pilier.gems, "boutique", pilier.id), Movement(friend, pilier.gemsReturned, "pilier", pilier.id)),
            app.shop.movements,
        )
    }

    @Test
    @DisplayName("buyItem pilier : sans assez de Gemmes pour le prix, 409 PAS_ASSEZ_DE_GEMMES ; les Gemmes rendues ne comptent pas d'avance")
    fun pilierNeedsTheFullPrice() = withApp { app ->
        app.player(kr, "Kr", gemmes = pilier.gems - 1)

        val refusal = refusalOf(buy(pilier.id), RefusalCodes.NOT_ENOUGH_GEMS)

        assertEquals(1.0, refusal.missing)
        assertNothingWritten(app)
    }

    // --- le Perso

    @Test
    @DisplayName("buyItem perso : sans le grade qu'il demande, 409 GRADE_PRECEDENT_REQUIS, même avec les Gemmes")
    fun persoNeedsItsGrade() = withApp { app ->
        app.player(kr, "Kr", gemmes = perso.gems * 2)
        // Tous les grades sauf celui que le Perso demande.
        grades.filter { it.id != ultimate }.forEach { app.shop.seedRank(kr, it.id) }

        refusalOf(buy(perso.id), RefusalCodes.PREVIOUS_RANK_REQUIRED)

        assertEquals(perso.gems * 2, app.gemmes(kr))
        assertTrue(app.shop.subscriptions.isEmpty())
    }

    @Test
    @DisplayName("buyItem perso : avec son grade, l'abonnement commence maintenant, `jours` jours, au prix de boutique.json, sans Gemmes rendues")
    fun buyingTheperso() = withApp { app ->
        app.player(kr, "Kr", gemmes = perso.gems + 2)
        app.shop.seedRank(kr, ultimate)

        val balances = balancesOf(buy(perso.id))

        assertEquals(2L, balances.gemmes)
        assertEquals(SubscriptionTerm(noon, noon.plus(Duration.ofDays(perso.days.toLong())), 1), app.shop.subscriptions.getValue(kr to perso.id))
        assertEquals(listOf(Movement(kr, -perso.gems, "boutique", perso.id)), app.shop.movements)
    }

    @Test
    @DisplayName("buyItem perso en cadeau : c'est le destinataire qui doit avoir le grade, pas l'acheteur")
    fun persoGiftChecksTheRecipient() = withApp { app ->
        app.player(kr, "Kr", gemmes = perso.gems * 2)
        app.player(friend, "Ami")
        app.shop.seedRank(kr, ultimate)

        refusalOf(buy(perso.id, n = 1, forXuid = friend), RefusalCodes.PREVIOUS_RANK_REQUIRED)
        app.shop.seedRank(friend, ultimate)
        balancesOf(buy(perso.id, n = 2, forXuid = friend))

        assertTrue(app.shop.subscriptions.containsKey(friend to perso.id))
        assertTrue(!app.shop.subscriptions.containsKey(kr to perso.id))
    }

    // --- les cosmétiques

    @Test
    @DisplayName("buyItem d'un cosmétique : il est écrit pour l'acheteur (source boutique), ses Gemmes baissent du prix, l'achat et le mouvement sont notés")
    fun buyingACosmetic() = withApp { app ->
        val cosmetic = rules.cosmetics.first()
        app.player(kr, "Kr", gemmes = cosmetic.gems + 7)

        val balances = balancesOf(buy(cosmetic.id))

        assertEquals(7L, balances.gemmes)
        assertEquals(listOf(CosmeticRow(cosmetic.id, noon, "boutique")), app.shop.cosmeticsOf(kr))
        assertEquals(listOf(Purchase(kr, cosmetic.id, cosmetic.gems, null, requestId(1), noon)), app.shop.purchases)
        assertEquals(listOf(Movement(kr, -cosmetic.gems, "boutique", cosmetic.id)), app.shop.movements)
        assertTrue(app.shop.published.isEmpty(), "un cosmétique n'annonce rien")
        assertTrue(app.shop.boosts.isEmpty() && app.shop.ranks.values.all { it.isEmpty() } && app.shop.subscriptions.isEmpty())
    }

    @Test
    @DisplayName("buyItem : chaque cosmétique en vente s'achète à son prix, sans grade ni abonnement ; le dernier racheté donne DEJA_POSSEDE")
    fun buyingEveryCosmetic() = withApp { app ->
        app.player(kr, "Kr", gemmes = rules.cosmetics.sumOf { it.gems } + 3)

        for ((index, cosmetic) in rules.cosmetics.withIndex()) {
            val before = app.gemmes(kr)
            assertEquals(before - cosmetic.gems, balancesOf(buy(cosmetic.id, n = index + 1)).gemmes, cosmetic.id)
        }

        assertEquals(3L, app.gemmes(kr))
        assertEquals(rules.cosmetics.map { it.id }, app.shop.cosmeticsOf(kr).map { it.cosmeticId })
        refusalOf(buy(rules.cosmetics.last().id, n = 100), RefusalCodes.ALREADY_OWNED)
        assertEquals(3L, app.gemmes(kr))
    }

    @Test
    @DisplayName("buyItem : un cosmétique déjà possédé (acheté, ou donné par ailleurs) donne 409 DEJA_POSSEDE, sans rien retirer ni noter")
    fun ownedCosmeticIsRefused() = withApp { app ->
        val (bought, given) = rules.cosmetics
        app.player(kr, "Kr", gemmes = bought.gems * 2 + given.gems)
        balancesOf(buy(bought.id, n = 1))
        app.shop.seedCosmetic(kr, given.id, source = "evenement")
        val gemmesBefore = app.gemmes(kr)

        refusalOf(buy(bought.id, n = 2), RefusalCodes.ALREADY_OWNED)
        refusalOf(buy(given.id, n = 3), RefusalCodes.ALREADY_OWNED)

        assertEquals(gemmesBefore, app.gemmes(kr))
        assertEquals(1, app.shop.purchases.size)
        assertEquals(1, app.shop.movements.size)
        assertEquals(listOf(bought.id to "boutique", given.id to "evenement"), app.shop.cosmeticsOf(kr).map { it.cosmeticId to it.source })
    }

    @Test
    @DisplayName("buyItem d'un cosmétique rejoué avec le même requestId : la même réponse, un seul cosmétique, rien de payé deux fois")
    fun replayedCosmeticIsPaidOnce() = withApp { app ->
        val cosmetic = rules.cosmetics.first()
        app.player(kr, "Kr", gemmes = cosmetic.gems * 2)

        val body = buy(cosmetic.id).bodyAsText()
        val replay = buy(cosmetic.id)

        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(body, replay.bodyAsText())
        assertEquals(1, app.shop.cosmeticsOf(kr).size)
        assertEquals(1, app.shop.movements.size)
        assertEquals(cosmetic.gems, app.gemmes(kr))
    }

    @Test
    @DisplayName("buyItem d'un cosmétique : sans assez de Gemmes, 409 PAS_ASSEZ_DE_GEMMES avec ce qui manque, sans rien écrire")
    fun cosmeticNeedsItsPrice() = withApp { app ->
        val cosmetic = rules.cosmetics.first()
        app.player(kr, "Kr", gemmes = cosmetic.gems - 1)

        val refusal = refusalOf(buy(cosmetic.id), RefusalCodes.NOT_ENOUGH_GEMS)

        assertEquals(1.0, refusal.missing)
        assertEquals(cosmetic.gems - 1, app.gemmes(kr))
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("buyItem en cadeau d'un cosmétique : l'acheteur paie, le destinataire le reçoit (source boutique), la réponse est le solde de l'acheteur")
    fun giftingACosmetic() = withApp { app ->
        val cosmetic = rules.cosmetics.first()
        app.player(kr, "Kr", gemmes = cosmetic.gems + 4)
        app.player(friend, "Ami")

        val balances = balancesOf(buy(cosmetic.id, forXuid = friend))

        assertEquals(4L, balances.gemmes)
        assertEquals(listOf(CosmeticRow(cosmetic.id, noon, "boutique")), app.shop.cosmeticsOf(friend))
        assertTrue(app.shop.cosmeticsOf(kr).isEmpty(), "l'acheteur ne l'a pas")
        assertEquals(listOf(Purchase(kr, cosmetic.id, cosmetic.gems, friend, requestId(1), noon)), app.shop.purchases)
        assertEquals(listOf(Movement(kr, -cosmetic.gems, "boutique", cosmetic.id)), app.shop.movements)
        assertEquals(0L, app.gemmes(friend))
    }

    @Test
    @DisplayName("buyItem en cadeau d'un cosmétique : la vérification porte sur le destinataire, pas sur l'acheteur")
    fun cosmeticGiftChecksTheRecipient() = withApp { app ->
        val cosmetic = rules.cosmetics.first()
        app.player(kr, "Kr", gemmes = cosmetic.gems * 3)
        app.player(friend, "Ami")
        app.shop.seedCosmetic(kr, cosmetic.id)

        // L'acheteur l'a déjà : le cadeau passe, c'est l'ami qui compte.
        balancesOf(buy(cosmetic.id, n = 1, forXuid = friend))
        assertEquals(listOf(cosmetic.id), app.shop.cosmeticsOf(friend).map { it.cosmeticId })

        // L'ami l'a maintenant : le même cadeau est refusé, et l'acheteur ne paie rien de plus.
        val after = app.gemmes(kr)
        refusalOf(buy(cosmetic.id, n = 2, forXuid = friend), RefusalCodes.ALREADY_OWNED)
        assertEquals(after, app.gemmes(kr))
        assertEquals(1, app.shop.purchases.size)
    }

    @Test
    @DisplayName("buyItem : le titre Pionnier ne s'achète pas, même rempli de Gemmes ni offert : 400, rien d'écrit")
    fun pioneerTitleIsNotForSale() = withApp { app ->
        app.player(kr, "Kr", gemmes = 100_000)
        app.player(friend, "Ami")

        assertBadRequest(buy(ShopRules.PIONEER_TITLE_ID, n = 1), "titre Pionnier")
        assertBadRequest(buy(ShopRules.PIONEER_TITLE_ID, n = 2, forXuid = friend), "titre Pionnier offert")

        assertNothingWritten(app)
        assertEquals(100_000L, app.gemmes(kr))
    }

    // --- le boost collectif

    @Test
    @DisplayName("buyItem du boost : il commence maintenant et dure dureeMinutes, l'acheteur paie, l'événement BOOST_COLLECTIF part sans xuid avec ses bornes")
    fun buyingTheBoost() = withApp { app ->
        val boost = rules.boost
        app.player(kr, "Kr", gemmes = boost.gems + 7)

        val balances = balancesOf(buy(boost.id))

        assertEquals(7L, balances.gemmes)
        val window = BoostWindow(noon, noon.plus(Duration.ofMinutes(boost.minutes)))
        assertEquals(listOf(BoostRow(kr, window, boost.bonusPercent)), app.shop.boosts)
        assertEquals(listOf(Purchase(kr, boost.id, boost.gems, null, requestId(1), noon)), app.shop.purchases)
        assertEquals(listOf(Movement(kr, -boost.gems, "boutique", boost.id)), app.shop.movements)
        val event = app.shop.published.single()
        assertEquals(GameEventKind.BOOST_COLLECTIF, event.kind)
        assertNull(event.xuid, "un événement pour tout le serveur")
        assertEquals(
            buildJsonObject {
                put("buyerName", "Kr")
                put("percent", boost.bonusPercent)
                put("minutes", boost.minutes)
                put("startsAt", window.startsAt.toString())
                put("endsAt", window.endsAt.toString())
            },
            event.payload,
        )
        assertTrue(app.shop.cosmetics.values.all { it.isEmpty() } && app.shop.ranks.values.all { it.isEmpty() })
    }

    @Test
    @DisplayName("buyItem du boost : un boost acheté pendant un autre commence à la fin du dernier réservé, quel que soit l'acheteur ; les boosts ne s'empilent pas")
    fun boostsFollowEachOther() = withApp { app ->
        val boost = rules.boost
        val length = Duration.ofMinutes(boost.minutes)
        app.player(kr, "Kr", gemmes = boost.gems * 2)
        app.player(friend, "Ami", gemmes = boost.gems)

        balancesOf(buy(boost.id, n = 1))
        app.clock.advance(length.dividedBy(6))
        balancesOf(buy(boost.id, n = 2, actor = friend))
        app.clock.advance(length.dividedBy(6))
        balancesOf(buy(boost.id, n = 3))

        val (first, second, third) = app.shop.boosts
        assertEquals(noon, first.window.startsAt)
        assertEquals(first.window.endsAt, second.window.startsAt, "le deuxième attend la fin du premier")
        assertEquals(second.window.endsAt, third.window.startsAt, "le troisième attend la fin du deuxième")
        assertEquals(listOf(length, length, length), app.shop.boosts.map { Duration.between(it.window.startsAt, it.window.endsAt) })
        assertEquals(listOf(kr, friend, kr), app.shop.boosts.map { it.boughtBy })
        // Aucune minute n'est couverte par deux boosts : à chaque instant, au plus un boost court.
        for (minute in 0L until length.toMinutes() * 3) {
            val at = noon.plus(Duration.ofMinutes(minute))
            assertEquals(1, app.shop.boosts.count { !it.window.startsAt.isAfter(at) && it.window.endsAt.isAfter(at) }, "à ${minute} minutes")
        }
        // Chaque achat annonce ses propres bornes, à l'achat.
        assertEquals(
            app.shop.boosts.map { it.window.startsAt.toString() to it.window.endsAt.toString() },
            app.shop.published.map { it.payload.getValue("startsAt").jsonPrimitive.content to it.payload.getValue("endsAt").jsonPrimitive.content },
        )
        assertEquals(listOf("Kr", "Ami", "Kr"), app.shop.published.map { it.payload.getValue("buyerName").jsonPrimitive.content })
        assertEquals(3, app.shop.boostLockReads, "un achat de boost lit la fin du dernier boost, une fois")
    }

    @Test
    @DisplayName("buyItem du boost : acheté après la fin du dernier (à l'instant même compris), il recommence à l'instant de l'achat")
    fun boostAfterTheLastOneStartsNow() = withApp { app ->
        val boost = rules.boost
        val length = Duration.ofMinutes(boost.minutes)
        app.player(kr, "Kr", gemmes = boost.gems * 3)

        balancesOf(buy(boost.id, n = 1))
        app.clock.advance(length)
        balancesOf(buy(boost.id, n = 2))
        app.clock.advance(length.multipliedBy(2))
        balancesOf(buy(boost.id, n = 3))

        assertEquals(
            listOf(noon, noon.plus(length), noon.plus(length.multipliedBy(3))),
            app.shop.boosts.map { it.window.startsAt },
        )
    }

    @Test
    @DisplayName("buyItem du boost : sans assez de Gemmes, 409 PAS_ASSEZ_DE_GEMMES avec ce qui manque, ni boost ni événement")
    fun boostNeedsItsPrice() = withApp { app ->
        val boost = rules.boost
        app.player(kr, "Kr", gemmes = boost.gems - 1)

        val refusal = refusalOf(buy(boost.id), RefusalCodes.NOT_ENOUGH_GEMS)

        assertEquals(1.0, refusal.missing)
        assertEquals(boost.gems - 1, app.gemmes(kr))
        assertNothingWritten(app)
    }

    @Test
    @DisplayName("buyItem du boost rejoué avec le même requestId : la même réponse, un seul boost, un seul événement")
    fun replayedBoostIsBoughtOnce() = withApp { app ->
        val boost = rules.boost
        app.player(kr, "Kr", gemmes = boost.gems * 2)

        val body = buy(boost.id).bodyAsText()
        val replay = buy(boost.id)

        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(body, replay.bodyAsText())
        assertEquals(1, app.shop.boosts.size)
        assertEquals(1, app.shop.published.size)
        assertEquals(boost.gems, app.gemmes(kr))
    }

    @Test
    @DisplayName("buyItem du boost : il ne s'offre pas (un autre destinataire donne 400, rien d'écrit) ; se l'offrir à soi-même est un achat pour soi")
    fun boostIsNotAGift() = withApp { app ->
        val boost = rules.boost
        app.player(kr, "Kr", gemmes = boost.gems * 2)
        app.player(friend, "Ami")

        assertBadRequest(buy(boost.id, n = 1, forXuid = friend), "boost offert")
        assertNothingWritten(app)

        balancesOf(buy(boost.id, n = 2, forXuid = kr))
        assertEquals(listOf(null), app.shop.purchases.map { it.recipient })
        assertEquals(1, app.shop.boosts.size)
    }

    // --- le journal

    @Test
    @DisplayName("Les motifs et les références du journal tiennent dans currency_ledger (32 et 64 caractères)")
    fun ledgerFieldsFit() = withApp { app ->
        app.player(kr, "Kr", gemmes = 100_000)
        app.shop.seedRank(kr, ultimate)
        var n = 0
        for (item in listOf(grades[0].id, pilier.id, perso.id) + rules.cosmetics.map { it.id } + rules.boost.id) balancesOf(buy(item, n = ++n))

        assertTrue(app.shop.movements.isNotEmpty())
        assertTrue(app.shop.movements.all { it.reason.length <= 32 && it.refId.length <= 64 }, "${app.shop.movements}")
        assertTrue(app.shop.purchases.all { it.itemId.length <= ITEM_ID_MAX_LENGTH })
    }

    // --- catalog

    @Test
    @DisplayName("catalog : le catalogue de ShopRules pour ce que le joueur possède, qui suit ses achats")
    fun catalogFollowsPurchases() = withApp { app ->
        app.player(kr, "Kr", gemmes = grades[0].gems + pilier.gems)
        assertEquals(rules.catalog(Holdings.NOTHING, noon), catalogItems(catalogOf(kr)))

        balancesOf(buy(grades[0].id, n = 1))
        balancesOf(buy(pilier.id, n = 2))

        val holdings = Holdings(setOf(grades[0].id), mapOf(pilier.id to noon.plus(Duration.ofDays(pilier.days.toLong()))))
        assertEquals(rules.catalog(holdings, noon), catalogItems(catalogOf(kr)))
    }

    @Test
    @DisplayName("catalog : le Pilier n'est plus owned une fois l'abonnement fini, et reste à vendre")
    fun catalogSeesTheEnd() = withApp { app ->
        app.player(kr, "Kr", gemmes = pilier.gems)
        balancesOf(buy(pilier.id))
        assertTrue(catalogItems(catalogOf(kr)).single { it.itemId == pilier.id }.owned)

        app.clock.advance(Duration.ofDays(pilier.days.toLong()))

        val entry = catalogItems(catalogOf(kr)).single { it.itemId == pilier.id }
        assertTrue(!entry.owned)
        assertTrue(entry.available)
    }

    @Test
    @DisplayName("catalog : le Perso s'ouvre avec le grade qu'il demande")
    fun catalogOpensThePerso() = withApp { app ->
        app.player(kr, "Kr")
        assertTrue(!catalogItems(catalogOf(kr)).single { it.itemId == perso.id }.available)

        app.shop.seedRank(kr, ultimate)

        assertTrue(catalogItems(catalogOf(kr)).single { it.itemId == perso.id }.available)
    }

    @Test
    @DisplayName("catalog : un cosmétique acheté passe owned et n'est plus à vendre, le boost ne passe jamais owned et reste à vendre")
    fun catalogFollowsCosmeticsAndBoost() = withApp { app ->
        val cosmetic = rules.cosmetics.first()
        app.player(kr, "Kr", gemmes = cosmetic.gems + rules.boost.gems)
        app.player(friend, "Ami")

        balancesOf(buy(cosmetic.id, n = 1))
        balancesOf(buy(rules.boost.id, n = 2))

        val mine = catalogItems(catalogOf(kr))
        assertTrue(mine.single { it.itemId == cosmetic.id }.let { it.owned && !it.available })
        assertTrue(mine.single { it.itemId == ShopRules.BOOST_ID }.let { !it.owned && it.available })
        assertEquals(
            rules.catalog(Holdings(emptySet(), emptyMap(), cosmetics = setOf(cosmetic.id)), noon),
            mine,
        )
        // Ni le cosmétique d'un joueur ni le boost de tout le serveur ne changent ce que les autres possèdent.
        assertTrue(catalogItems(catalogOf(friend)).none { it.owned })
    }

    @Test
    @DisplayName("catalog : un acteur mal formé ou que le service ne connaît pas donne 400")
    fun catalogBadActor() = withApp { app ->
        app.player(kr, "Kr")

        assertBadRequest(catalogOf(stranger), "joueur inconnu")
        for (query in listOf("", "?actor=", "?actor=abc", "?actor=-1")) {
            assertBadRequest(client.get("/v1/shop/catalog$query") { header(SERVER_KEY_HEADER, key) }, "catalog$query")
        }
    }

    @Test
    @DisplayName("Un achat ne change pas le catalogue des autres joueurs")
    fun purchasesAreIndividual() = withApp { app ->
        app.player(kr, "Kr", gemmes = grades[0].gems)
        app.player(friend, "Ami")
        val before = catalogItems(catalogOf(friend))

        balancesOf(buy(grades[0].id))

        assertEquals(before, catalogItems(catalogOf(friend)))
        assertNotEquals(before, catalogItems(catalogOf(kr)))
    }
}
