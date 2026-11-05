package com.silooo.core.pass

import com.silooo.core.Dependencies
import com.silooo.core.MutableClock
import com.silooo.core.TestDefinitions
import com.silooo.core.api.Balances
import com.silooo.core.api.CatalogItem
import com.silooo.core.api.ErrorBody
import com.silooo.core.api.ErrorCodes
import com.silooo.core.api.Idempotency
import com.silooo.core.api.PassClaimed
import com.silooo.core.api.PassState
import com.silooo.core.api.RefusalCodes
import com.silooo.core.api.RefusedBody
import com.silooo.core.api.SERVER_KEY_HEADER
import com.silooo.core.api.SiloooJson
import com.silooo.core.db.InMemoryApiRequestStore
import com.silooo.core.db.PassThroughDatabase
import com.silooo.core.db.RecordingConnection
import com.silooo.core.db.Xuid
import com.silooo.core.economy.EngineFixture
import com.silooo.core.session.InMemorySessionStore
import com.silooo.core.shop.InMemoryShopStore
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
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `getPass`, `claimPass` et l'achat de la piste premium (`buyItem`, article `pass-premium`) avec des faux (pas de base) : le vrai
 * [PassRules] sur les `definitions/` du dépôt, un faux dépôt ([InMemoryPassStore]) et celui de la boutique, une horloge que le test
 * place dans la saison ou hors d'elle. Les chiffres attendus (points, Lingots, prix, dates) sortent de `pass.json`, jamais d'un
 * littéral. Les faux ne reviennent pas en arrière quand une transaction est annulée : chaque refus se vérifie sur ce qu'il laisse
 * AVANT toute écriture. Le SQL est dans `PassSqlTest`, le contrat dans `ContractTest`.
 */
class PassRoutesTest {
    private val key = "cle-de-test"
    private val kr = EngineFixture.KR
    private val friend = EngineFixture.OTHER
    private val stranger = EngineFixture.STRANGER

    private val definitions = TestDefinitions.loaded
    private val pass = definitions.pass
    private val seasonId = pass.season.id
    private val price = pass.premium.gems

    private val inSeason: Instant = pass.season.startDay.atTime(12, 0).toInstant(ZoneOffset.UTC)
    private val afterSeason: Instant = pass.season.endDay.plusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC)
    private val beforeSeason: Instant = pass.season.startDay.minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC)

    /** Un palier qui a une récompense sur chaque piste, et un qui n'en a pas sur la piste premium. */
    private val full = pass.tiers.first { it.free != null && it.premium != null }
    private val freeOnly = pass.tiers.first { it.free != null && it.premium == null }
    private val fullLingots = checkNotNull(full.free).lingots
    private val fullCosmetic = checkNotNull(full.premium).cosmeticId

    private class Harness(
        val sessions: InMemorySessionStore,
        val shop: InMemoryShopStore,
        val pass: InMemoryPassStore,
        val clock: MutableClock,
    ) {
        /** Inscrit [xuid] (il est alors connu du service) avec [gemmes] Gemmes et [lingots] Lingots. */
        fun player(xuid: Xuid, gamertag: String = "Joueur${xuid.text.takeLast(2)}", gemmes: Long = 0L, lingots: Long = 0L) {
            sessions.enterPlayer(RecordingConnection().connection, xuid, gamertag)
            sessions.balances[xuid] = Balances(lingots, gemmes, 0, 0)
        }

        fun lingots(xuid: Xuid): Long = sessions.balances.getValue(xuid).lingots

        fun gemmes(xuid: Xuid): Long = sessions.balances.getValue(xuid).gemmes
    }

    private fun withApp(withPass: Boolean = true, at: Instant = inSeason, block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
        val clock = MutableClock(at)
        val sessions = InMemorySessionStore(at)
        val shop = InMemoryShopStore(sessions)
        val store = InMemoryPassStore(sessions)
        val database = PassThroughDatabase()
        application {
            siloooModule(
                Dependencies(
                    database = database,
                    definitions = definitions,
                    apiKey = key,
                    idempotency = Idempotency(database, InMemoryApiRequestStore(at)),
                    sessionStore = sessions,
                    clock = clock,
                    shopStore = shop,
                    pass = if (withPass) PassService(definitions, store, sessions, clock) else null,
                ),
            )
        }
        block(Harness(sessions, shop, store, clock))
    }

    // --- outils

    private fun requestId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    private suspend fun ApplicationTestBuilder.getPass(actor: String): HttpResponse =
        client.get("/v1/pass?actor=$actor") { header(SERVER_KEY_HEADER, key) }

    private suspend fun ApplicationTestBuilder.claim(
        tier: Int,
        track: String,
        n: Int = 1,
        actor: String = kr.text,
        requestId: String = requestId(n),
    ): HttpResponse = client.post("/v1/pass/claims") {
        header(SERVER_KEY_HEADER, key)
        contentType(ContentType.Application.Json)
        setBody("""{"requestId":"$requestId","actor":"$actor","tier":$tier,"track":"$track"}""")
    }

    private suspend fun ApplicationTestBuilder.buy(itemId: String, n: Int = 1, actor: Xuid = kr, forXuid: Xuid? = null): HttpResponse {
        val gift = forXuid?.let { ""","forXuid":"${it.text}"""" }.orEmpty()
        return client.post("/v1/shop/purchases") {
            header(SERVER_KEY_HEADER, key)
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"${requestId(n)}","actor":"${actor.text}","itemId":"$itemId"$gift}""")
        }
    }

    private suspend fun ApplicationTestBuilder.catalogOf(actor: Xuid): HttpResponse =
        client.get("/v1/shop/catalog?actor=${actor.text}") { header(SERVER_KEY_HEADER, key) }

    private suspend fun stateOf(response: HttpResponse): PassState {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return SiloooJson.decodeFromString(PassState.serializer(), body)
    }

    private suspend fun claimedOf(response: HttpResponse): PassClaimed {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return SiloooJson.decodeFromString(PassClaimed.serializer(), body)
    }

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

    // --- getPass

    @Test
    @DisplayName("getPass : un joueur sans point voit toute la saison de pass.json à zéro, et rien n'est écrit")
    fun getPassOfANewPlayer() = withApp { app ->
        app.player(kr)

        val state = stateOf(getPass(kr.text))

        assertEquals(seasonId, state.seasonId)
        assertEquals(pass.season.name, state.name)
        assertEquals(pass.season.start, state.startsOn)
        assertEquals(pass.season.end, state.endsOn)
        assertEquals(true, state.active)
        assertEquals(0, state.points)
        assertEquals(false, state.premium)
        assertEquals(emptyList<String>(), state.claimed)
        assertEquals(pass.tiers.map { it.tier to it.points }, state.tiers.map { it.tier to it.points })
        assertEquals(fullCosmetic, state.tiers.first { it.tier == full.tier }.premium?.cosmeticId)
        assertEquals(fullLingots, state.tiers.first { it.tier == full.tier }.free?.lingots)
        assertNull(state.tiers.first { it.tier == freeOnly.tier }.premium)
        assertTrue(app.pass.rows.isEmpty() && app.pass.claims.isEmpty() && app.pass.movements.isEmpty(), "lire n'écrit rien")
    }

    @Test
    @DisplayName("getPass : les points, la piste premium et les paliers réclamés du joueur, dans l'ordre palier puis piste")
    fun getPassShowsTheProgress() = withApp { app ->
        app.player(kr)
        app.pass.seed(kr, seasonId, points = full.points + 3, premium = true)
        app.pass.seedClaim(kr, seasonId, full.tier, PassTrack.PREMIUM)
        app.pass.seedClaim(kr, seasonId, full.tier, PassTrack.FREE)
        app.pass.seed(friend, seasonId, points = 999)

        val state = stateOf(getPass(kr.text))

        assertEquals(full.points + 3, state.points)
        assertEquals(true, state.premium)
        assertEquals(listOf("${full.tier}-GRATUIT", "${full.tier}-PREMIUM"), state.claimed)
    }

    @Test
    @DisplayName("getPass : hors saison, active est faux et les points restent lisibles")
    fun getPassOutsideTheSeason() = withApp(at = afterSeason) { app ->
        app.player(kr)
        app.pass.seed(kr, seasonId, points = full.points)

        val state = stateOf(getPass(kr.text))

        assertEquals(false, state.active)
        assertEquals(full.points, state.points)
        assertEquals(pass.tiers.size, state.tiers.size)
    }

    @Test
    @DisplayName("getPass : un acteur absent, mal formé ou que le service ne connaît pas donne 400 REQUETE_INVALIDE")
    fun getPassChecksTheActor() = withApp { app ->
        app.player(kr)

        assertBadRequest(client.get("/v1/pass") { header(SERVER_KEY_HEADER, key) }, "acteur absent")
        assertBadRequest(getPass("pas-un-xuid"), "acteur mal formé")
        assertBadRequest(getPass(stranger.text), "acteur inconnu")
    }

    @Test
    @DisplayName("getPass et claimPass : sans la clé du serveur, la route répond comme toutes les autres")
    fun routesNeedTheServerKey() = withApp { app ->
        app.player(kr)

        val read = client.get("/v1/pass?actor=${kr.text}")
        val write = client.post("/v1/pass/claims") {
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"${requestId(1)}","actor":"${kr.text}","tier":1,"track":"GRATUIT"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, read.status)
        assertEquals(HttpStatusCode.Unauthorized, write.status)
    }

    @Test
    @DisplayName("Sans pass branché, les routes du pass ne sont pas posées : 501 comme pour une route que le service n'a pas encore")
    fun withoutAPassTheRoutesAreNotThere() = withApp(withPass = false) { app ->
        app.player(kr)

        assertEquals(HttpStatusCode.NotImplemented, getPass(kr.text).status)
        assertEquals(HttpStatusCode.NotImplemented, claim(full.tier, "GRATUIT").status)
    }

    // --- claimPass : la piste gratuite

    @Test
    @DisplayName("claimPass : un palier atteint sur la piste gratuite verse ses Lingots (motif pass), est noté, et la réponse rend les soldes")
    fun freeTierPaysLingots() = withApp { app ->
        app.player(kr, lingots = 5)
        app.pass.seed(kr, seasonId, points = full.points)

        val claimed = claimedOf(claim(full.tier, "GRATUIT"))

        assertEquals(PassClaimed(seasonId, full.tier, "GRATUIT", fullLingots, null, Balances(5 + fullLingots, 0, 0, 0)), claimed)
        assertEquals(5 + fullLingots, app.lingots(kr))
        assertEquals(
            listOf(InMemoryPassStore.Movement(kr, fullLingots, "pass", "$seasonId:${full.tier}:GRATUIT")),
            app.pass.movements,
        )
        assertEquals(setOf(PassClaim(full.tier, PassTrack.FREE)), app.pass.claims.getValue(kr to seasonId))
        assertEquals(full.points, app.pass.pointsOf(kr, seasonId), "réclamer ne dépense pas les points")
        assertEquals(emptyList<String>(), app.pass.cosmeticsOf(kr), "la piste gratuite ne donne aucun cosmétique")
        assertEquals(listOf("soldes", "pass"), app.pass.locks, "la ligne de soldes se verrouille avant celle du pass")
    }

    @Test
    @DisplayName("claimPass rejoué avec le même requestId : la même réponse, rien n'est versé deux fois ; un autre requestId sur le même palier : DEJA_RECLAME")
    fun claimIsPaidOnce() = withApp { app ->
        app.player(kr)
        app.pass.seed(kr, seasonId, points = full.points)

        val first = claim(full.tier, "GRATUIT", n = 1)
        val firstBody = first.bodyAsText()
        assertEquals(HttpStatusCode.OK, first.status, firstBody)
        val replay = claim(full.tier, "GRATUIT", n = 1)

        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(firstBody, replay.bodyAsText())
        assertEquals(1, app.pass.movements.size)
        assertEquals(fullLingots, app.lingots(kr))

        refusalOf(claim(full.tier, "GRATUIT", n = 2), RefusalCodes.ALREADY_CLAIMED)
        assertEquals(1, app.pass.movements.size, "le refus ne verse rien")
        assertEquals(fullLingots, app.lingots(kr))
    }

    @Test
    @DisplayName("claimPass : chaque palier se réclame à part et dans n'importe quel ordre ; les Lingots s'additionnent")
    fun tiersAreClaimedSeparately() = withApp { app ->
        app.player(kr)
        val top = pass.tiers.last()
        app.pass.seed(kr, seasonId, points = top.points)

        val later = claimedOf(claim(top.tier, "GRATUIT", n = 1))
        val earlier = claimedOf(claim(pass.tiers.first().tier, "GRATUIT", n = 2))

        val expected = checkNotNull(top.free).lingots + checkNotNull(pass.tiers.first().free).lingots
        assertEquals(expected, earlier.balances.lingots)
        assertEquals(checkNotNull(top.free).lingots, later.rewardLingots)
        assertEquals(expected, app.lingots(kr))
        assertEquals(2, app.pass.movements.size)
    }

    @Test
    @DisplayName("claimPass : sous le seuil, 409 PALIER_NON_ATTEINT avec les points qui manquent, et rien n'est écrit")
    fun tierNotReached() = withApp { app ->
        app.player(kr)
        app.pass.seed(kr, seasonId, points = full.points - 1)

        val refusal = refusalOf(claim(full.tier, "GRATUIT"), RefusalCodes.TIER_NOT_REACHED)

        assertEquals(1.0, refusal.missing)
        assertTrue(app.pass.claims.isEmpty() && app.pass.movements.isEmpty())
        assertEquals(0L, app.lingots(kr))
    }

    @Test
    @DisplayName("claimPass : un joueur sans aucun point manque tous les points du palier")
    fun nothingEarnedYet() = withApp { app ->
        app.player(kr)

        val refusal = refusalOf(claim(full.tier, "GRATUIT"), RefusalCodes.TIER_NOT_REACHED)

        assertEquals(full.points.toDouble(), refusal.missing)
    }

    @Test
    @DisplayName("claimPass : une fois la saison finie, les paliers atteints se réclament encore")
    fun claimsStayOpenAfterTheSeason() = withApp(at = afterSeason) { app ->
        app.player(kr)
        app.pass.seed(kr, seasonId, points = full.points)

        val claimed = claimedOf(claim(full.tier, "GRATUIT"))

        assertEquals(fullLingots, claimed.rewardLingots)
        assertEquals(false, stateOf(getPass(kr.text)).active)
    }

    // --- claimPass : la piste premium

    @Test
    @DisplayName("claimPass : la piste premium exige l'achat, même avec les points : 409 PREMIUM_REQUIS, rien n'est donné")
    fun premiumTrackNeedsThePurchase() = withApp { app ->
        app.player(kr)
        app.pass.seed(kr, seasonId, points = full.points)

        refusalOf(claim(full.tier, "PREMIUM"), RefusalCodes.PREMIUM_REQUIRED)

        assertTrue(app.pass.claims.isEmpty() && app.pass.cosmetics.isEmpty() && app.pass.movements.isEmpty())
    }

    @Test
    @DisplayName("claimPass : avec la piste premium, le palier atteint donne son cosmétique (source pass) et aucun Lingot ; une seconde fois : DEJA_RECLAME")
    fun premiumTierGivesTheCosmetic() = withApp { app ->
        app.player(kr, lingots = 7)
        app.pass.seed(kr, seasonId, points = full.points, premium = true)

        val claimed = claimedOf(claim(full.tier, "PREMIUM"))

        assertEquals(PassClaimed(seasonId, full.tier, "PREMIUM", 0L, fullCosmetic, Balances(7, 0, 0, 0)), claimed)
        assertEquals(listOf(InMemoryPassStore.Cosmetic(kr, fullCosmetic, "pass")), app.pass.cosmetics)
        assertEquals(7L, app.lingots(kr), "la piste premium ne verse jamais de monnaie")
        assertTrue(app.pass.movements.isEmpty())
        assertEquals(listOf("${full.tier}-PREMIUM"), stateOf(getPass(kr.text)).claimed)

        refusalOf(claim(full.tier, "PREMIUM", n = 2), RefusalCodes.ALREADY_CLAIMED)
        assertEquals(1, app.pass.cosmetics.size)
    }

    @Test
    @DisplayName("claimPass : avec la piste premium mais sous le seuil, 409 PALIER_NON_ATTEINT")
    fun premiumTierStillNeedsThePoints() = withApp { app ->
        app.player(kr)
        app.pass.seed(kr, seasonId, points = 0, premium = true)

        val refusal = refusalOf(claim(full.tier, "PREMIUM"), RefusalCodes.TIER_NOT_REACHED)

        assertEquals(full.points.toDouble(), refusal.missing)
        assertTrue(app.pass.cosmetics.isEmpty())
    }

    @Test
    @DisplayName("claimPass : un cosmétique que le joueur a déjà (une autre source) ne bloque pas le palier, et il n'est pas donné deux fois")
    fun anOwnedCosmeticDoesNotBlockTheTier() = withApp { app ->
        app.player(kr)
        app.pass.seed(kr, seasonId, points = full.points, premium = true)
        app.pass.cosmetics += InMemoryPassStore.Cosmetic(kr, fullCosmetic, "boutique")

        val claimed = claimedOf(claim(full.tier, "PREMIUM"))

        assertEquals(fullCosmetic, claimed.cosmeticId)
        assertEquals(1, app.pass.cosmeticsOf(kr).size)
        assertEquals(setOf(PassClaim(full.tier, PassTrack.PREMIUM)), app.pass.claims.getValue(kr to seasonId))
    }

    @Test
    @DisplayName("claimPass : la piste gratuite et la piste premium d'un même palier sont deux réclamations")
    fun bothTracksOfOneTier() = withApp { app ->
        app.player(kr)
        app.pass.seed(kr, seasonId, points = full.points, premium = true)

        claimedOf(claim(full.tier, "GRATUIT", n = 1))
        val premium = claimedOf(claim(full.tier, "PREMIUM", n = 2))

        assertEquals(fullCosmetic, premium.cosmeticId)
        assertEquals(listOf("${full.tier}-GRATUIT", "${full.tier}-PREMIUM"), stateOf(getPass(kr.text)).claimed)
        assertEquals(fullLingots, app.lingots(kr))
    }

    // --- claimPass : les requêtes mal formées

    @Test
    @DisplayName("claimPass : une piste inconnue, un palier qui n'existe pas, une piste sans récompense à ce palier ou un acteur inconnu donnent 400, rien n'est écrit")
    fun badClaimRequests() = withApp { app ->
        app.player(kr)
        app.pass.seed(kr, seasonId, points = 1_000_000, premium = true)
        val beyond = pass.tiers.size + 1

        assertBadRequest(claim(full.tier, "BRONZE", n = 1), "piste inconnue")
        assertBadRequest(claim(full.tier, "gratuit", n = 2), "piste en minuscules")
        assertBadRequest(claim(0, "GRATUIT", n = 3), "palier zéro")
        assertBadRequest(claim(-1, "GRATUIT", n = 4), "palier négatif")
        assertBadRequest(claim(beyond, "GRATUIT", n = 5), "palier après le dernier")
        assertBadRequest(claim(freeOnly.tier, "PREMIUM", n = 6), "piste premium sans récompense à ce palier")
        assertBadRequest(claim(full.tier, "GRATUIT", n = 7, actor = stranger.text), "acteur inconnu")
        assertBadRequest(claim(full.tier, "GRATUIT", n = 8, actor = "pas-un-xuid"), "acteur mal formé")

        assertTrue(app.pass.claims.isEmpty() && app.pass.movements.isEmpty() && app.pass.cosmetics.isEmpty())
        assertEquals(0L, app.lingots(kr))
    }

    // --- l'achat de la piste premium

    @Test
    @DisplayName("catalog : l'article pass-premium vient en dernier, au prix de pass.json, à acheter pendant la saison")
    fun catalogListsThePremiumTrack() = withApp { app ->
        app.player(kr, gemmes = price)

        val items = catalogItems(catalogOf(kr))

        assertEquals(CatalogItem("pass-premium", price, owned = false, available = true), items.last())
        assertEquals(1, items.count { it.itemId == "pass-premium" })
    }

    @Test
    @DisplayName("buyItem pass-premium : le prix de pass.json est dépensé, la piste premium s'ouvre, le catalogue la montre possédée ; rien de plus")
    fun buyingThePremiumTrack() = withApp { app ->
        app.player(kr, gemmes = price + 10)

        val balances = balancesOf(buy("pass-premium"))

        assertEquals(10L, balances.gemmes)
        assertEquals(10L, app.gemmes(kr))
        assertEquals(true, app.pass.premiumOf(kr, seasonId))
        assertEquals(true, stateOf(getPass(kr.text)).premium)
        assertEquals(CatalogItem("pass-premium", price, owned = true, available = false), catalogItems(catalogOf(kr)).last())
        val purchase = app.shop.purchases.single()
        assertEquals("pass-premium", purchase.itemId)
        assertEquals(price, purchase.gems)
        assertNull(purchase.recipient)
        assertEquals(listOf(InMemoryShopStore.Movement(kr, -price, "boutique", "pass-premium")), app.shop.movements)
        assertEquals(0, app.pass.claims.size, "acheter la piste ne réclame aucun palier")
        assertTrue(app.pass.movements.isEmpty(), "ni Lingots ni points")
    }

    @Test
    @DisplayName("buyItem pass-premium : une fois la piste ouverte, un nouvel achat est refusé (DEJA_POSSEDE) et ne coûte rien")
    fun premiumIsBoughtOnce() = withApp { app ->
        app.player(kr, gemmes = 3 * price)
        balancesOf(buy("pass-premium", n = 1))

        refusalOf(buy("pass-premium", n = 2), RefusalCodes.ALREADY_OWNED)

        assertEquals(2 * price, app.gemmes(kr))
        assertEquals(1, app.shop.purchases.size)
    }

    @Test
    @DisplayName("buyItem pass-premium : sans assez de Gemmes, 409 PAS_ASSEZ_DE_GEMMES avec ce qui manque, et la piste reste fermée")
    fun notEnoughGemsForThePremiumTrack() = withApp { app ->
        app.player(kr, gemmes = price - 1)

        val refusal = refusalOf(buy("pass-premium"), RefusalCodes.NOT_ENOUGH_GEMS)

        assertEquals(1.0, refusal.missing)
        assertEquals(price - 1, app.gemmes(kr))
        assertEquals(false, app.pass.premiumOf(kr, seasonId))
        assertTrue(app.shop.purchases.isEmpty())
    }

    @Test
    @DisplayName("buyItem pass-premium : avant comme après la saison, 409 SAISON_INACTIVE, rien n'est payé ; le catalogue la montre indisponible")
    fun premiumIsSoldDuringTheSeasonOnly() {
        listOf(beforeSeason, afterSeason).forEach { moment ->
            withApp(at = moment) { app ->
                app.player(kr, gemmes = 2 * price)

                refusalOf(buy("pass-premium"), RefusalCodes.SEASON_INACTIVE)

                assertEquals(2 * price, app.gemmes(kr))
                assertEquals(false, app.pass.premiumOf(kr, seasonId))
                assertTrue(app.shop.purchases.isEmpty())
                assertEquals(CatalogItem("pass-premium", price, owned = false, available = false), catalogItems(catalogOf(kr)).last())
            }
        }
    }

    @Test
    @DisplayName("buyItem pass-premium en cadeau : l'acheteur paie, le destinataire reçoit la piste ; si le destinataire l'a déjà, DEJA_POSSEDE et rien n'est payé")
    fun premiumAsAGift() = withApp { app ->
        app.player(kr, gemmes = 3 * price)
        app.player(friend)

        balancesOf(buy("pass-premium", n = 1, forXuid = friend))

        assertEquals(2 * price, app.gemmes(kr))
        assertEquals(true, app.pass.premiumOf(friend, seasonId))
        assertEquals(false, app.pass.premiumOf(kr, seasonId), "le cadeau n'ouvre pas la piste de l'acheteur")
        assertEquals(friend, app.shop.purchases.single().recipient)

        refusalOf(buy("pass-premium", n = 2, forXuid = friend), RefusalCodes.ALREADY_OWNED)
        assertEquals(2 * price, app.gemmes(kr))

        balancesOf(buy("pass-premium", n = 3))
        assertEquals(price, app.gemmes(kr))
        assertEquals(true, app.pass.premiumOf(kr, seasonId))
    }

    @Test
    @DisplayName("buyItem pass-premium : un destinataire que le service ne connaît pas donne 400, rien n'est payé")
    fun giftToAStranger() = withApp { app ->
        app.player(kr, gemmes = price)

        assertBadRequest(buy("pass-premium", forXuid = stranger), "destinataire inconnu")

        assertEquals(price, app.gemmes(kr))
        assertTrue(app.shop.purchases.isEmpty())
    }

    @Test
    @DisplayName("buyItem pass-premium : sans pass branché, l'article est inconnu (400) et le catalogue ne le liste pas")
    fun withoutAPassTheShopDoesNotSellIt() = withApp(withPass = false) { app ->
        app.player(kr, gemmes = 2 * price)

        assertBadRequest(buy("pass-premium"), "article que le jeu ne vend pas")

        assertEquals(2 * price, app.gemmes(kr))
        assertTrue(catalogItems(catalogOf(kr)).none { it.itemId == "pass-premium" })
    }

    @Test
    @DisplayName("buyItem : un cosmétique du pass, comme tout cosmétique que boutique.json ne vend pas, est refusé (400), pour soi et en cadeau, sans toucher aux Gemmes")
    fun passCosmeticsCannotBeBought() = withApp { app ->
        app.player(kr, gemmes = 10 * price)
        app.player(friend)
        val notSold = definitions.shop.cosmetics.filter { !it.purchasable }.map { it.id }
        assertTrue(notSold.containsAll(pass.tiers.mapNotNull { it.premium?.cosmeticId }), "ceux du pass sont déclarés sans être vendus")

        var n = 0
        for (id in notSold) {
            assertBadRequest(buy(id, n = ++n), "« $id » acheté")
            assertBadRequest(buy(id, n = ++n, forXuid = friend), "« $id » offert")
        }

        assertEquals(10 * price, app.gemmes(kr))
        assertTrue(app.shop.purchases.isEmpty())
        assertTrue(catalogItems(catalogOf(kr)).none { it.itemId in notSold })
    }

    @Test
    @DisplayName("Du début à la fin : acheter la piste, atteindre le palier, réclamer les deux pistes")
    fun buyThenClaim() = withApp { app ->
        app.player(kr, gemmes = price)
        app.pass.seed(kr, seasonId, points = full.points)

        refusalOf(claim(full.tier, "PREMIUM", n = 1), RefusalCodes.PREMIUM_REQUIRED)
        balancesOf(buy("pass-premium", n = 2))
        val premium = claimedOf(claim(full.tier, "PREMIUM", n = 3))
        val free = claimedOf(claim(full.tier, "GRATUIT", n = 4))

        assertEquals(fullCosmetic, premium.cosmeticId)
        assertEquals(fullLingots, free.rewardLingots)
        assertEquals(0L, app.gemmes(kr))
        assertEquals(fullLingots, app.lingots(kr))
    }
}
