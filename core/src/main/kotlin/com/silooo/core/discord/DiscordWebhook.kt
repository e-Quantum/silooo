package com.silooo.core.discord

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * L'adresse du webhook Discord (`SILOOO_DISCORD_WEBHOOK`). C'est un secret : qui la connaît écrit dans le salon. Elle ne
 * s'affiche jamais, ni dans `toString`, ni dans un message d'erreur, ni dans les journaux.
 */
class WebhookAddress private constructor(val uri: URI) {
    override fun toString() = "WebhookAddress(***)"

    companion object {
        /** `null` si [raw] n'est pas une adresse `https://hôte/…`. Le texte lu n'est repris dans aucune erreur. */
        fun parse(raw: String): WebhookAddress? {
            val uri = try {
                URI(raw.trim())
            } catch (e: URISyntaxException) {
                return null
            }
            return if (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) WebhookAddress(uri) else null
        }

        /** Pour un test qui parle à un faux serveur sur la boucle locale : une adresse absolue, sans contrôle du schéma. */
        internal fun unchecked(uri: URI) = WebhookAddress(uri)
    }
}

/** Ce que le service sait envoyer sur Discord. [DiscordWebhook] en production, un faux dans les tests, [DiscordOff] sans adresse. */
interface DiscordSender {
    /** Faux : l'envoyeur est éteint (pas d'adresse), rien ne part et le suiveur ne lit rien. */
    val enabled: Boolean

    /** Vrai si Discord a pris le message (en entier : un long message part en plusieurs). Ne lève jamais, hors annulation. */
    suspend fun send(message: String): Boolean
}

/** L'envoyeur éteint : `SILOOO_DISCORD_WEBHOOK` n'est pas posée. */
object DiscordOff : DiscordSender {
    override val enabled = false
    override suspend fun send(message: String) = false
}

/** La réponse de Discord à un envoi : le code HTTP, et l'attente demandée par un 429 (en millisecondes). */
data class DiscordReply(val status: Int, val retryAfterMillis: Long? = null)

/**
 * Le dernier maillon, derrière une interface pour que les tests n'aient pas de réseau : poste [body] (déjà du JSON) sur le
 * webhook. Une panne de réseau ou de délai lève une [IOException] ; un code HTTP, même d'erreur, est rendu tel quel.
 */
fun interface DiscordTransport {
    suspend fun post(body: String): DiscordReply
}

/**
 * Le client de webhook : `POST {"content": "…"}` (voir [bodyOf]) sur l'adresse du salon.
 *
 *  - Un message de plus de [DISCORD_LIMIT] caractères part en plusieurs ([splitForDiscord]), dans l'ordre.
 *  - Un envoi qui échoue pour une raison qui peut passer (réseau, délai, 429, 5xx) est retenté UNE fois, après une courte pause
 *    ([RETRY_PAUSE], ou l'attente que Discord demande, bornée). Une réponse qui ne passera pas (400, 401, 404 : webhook supprimé)
 *    n'est pas retentée.
 *  - Un échec se note sur une ligne, sans l'adresse ni le texte : le code HTTP ou le nom de l'exception, rien d'autre.
 *  - Les mentions sont coupées (`allowed_mentions.parse` vide) : un nom d'entreprise ou un pseudo `@everyone` s'affiche, il ne
 *    prévient personne.
 */
class DiscordWebhook(
    private val transport: DiscordTransport,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : DiscordSender {
    override val enabled = true

    override suspend fun send(message: String): Boolean {
        val parts = splitForDiscord(message)
        if (parts.isEmpty()) return false
        for (part in parts) {
            if (!sendOne(part)) return false
        }
        return true
    }

    private suspend fun sendOne(content: String): Boolean {
        val body = bodyOf(content)
        var wait = RETRY_PAUSE.toMillis()
        for (attempt in 1..ATTEMPTS) {
            val reply = try {
                transport.post(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Le nom de l'exception seulement : son message peut citer l'adresse.
                log.warn("Discord : message non envoyé (essai {}/{}), erreur {}.", attempt, ATTEMPTS, e.javaClass.simpleName)
                null
            }
            if (reply != null) {
                if (reply.status in 200..299) return true
                log.warn("Discord : message non envoyé (essai {}/{}), réponse {}.", attempt, ATTEMPTS, reply.status)
                if (!canPass(reply.status)) return false
                reply.retryAfterMillis?.let { wait = it.coerceIn(RETRY_PAUSE_MIN.toMillis(), RETRY_PAUSE_MAX.toMillis()) }
            }
            if (attempt < ATTEMPTS) pause(wait)
        }
        return false
    }

    /** Un 429 (trop de messages) et un 5xx (Discord en panne) peuvent passer au second essai ; les autres refus, non. */
    private fun canPass(status: Int) = status == TOO_MANY_REQUESTS || status >= 500

    companion object {
        /** Un premier essai, une nouvelle tentative. */
        const val ATTEMPTS = 2

        /** La pause avant la nouvelle tentative quand Discord n'a rien demandé. Une durée technique de liaison. */
        val RETRY_PAUSE: Duration = Duration.ofSeconds(2)
        private val RETRY_PAUSE_MIN: Duration = Duration.ofMillis(200)
        private val RETRY_PAUSE_MAX: Duration = Duration.ofSeconds(10)
        private const val TOO_MANY_REQUESTS = 429

        /** Le corps JSON d'un envoi : le texte, et aucune mention permise. */
        fun bodyOf(content: String): String = buildJsonObject {
            put("content", content)
            put("allowed_mentions", buildJsonObject { put("parse", JsonArray(emptyList())) })
        }.toString()

        private val log = LoggerFactory.getLogger(DiscordWebhook::class.java)
    }
}

/** La longueur maximale du texte d'un message de webhook Discord. */
const val DISCORD_LIMIT = 2000

/**
 * Coupe [text] en morceaux de [limit] caractères au plus, dans l'ordre : de préférence à un saut de ligne, sinon à une espace,
 * sinon en pleine chaîne (jamais entre les deux moitiés d'un caractère hors du plan de base). Le saut de ligne ou l'espace de
 * la coupure est retiré, les morceaux vides sont écartés ; un texte vide ne donne aucun morceau.
 */
fun splitForDiscord(text: String, limit: Int = DISCORD_LIMIT): List<String> {
    require(limit >= 2) { "La limite doit permettre au moins deux caractères : $limit" }
    val parts = mutableListOf<String>()
    var rest = text.trim()
    while (rest.length > limit) {
        val newline = rest.lastIndexOf('\n', limit)
        val space = rest.lastIndexOf(' ', limit)
        val cut = when {
            newline > 0 -> newline
            space > 0 -> space
            Character.isHighSurrogate(rest[limit - 1]) -> limit - 1
            else -> limit
        }
        parts += rest.substring(0, cut).trim()
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) parts += rest
    return parts.filter { it.isNotEmpty() }
}

/**
 * Le transport de production, sur le `HttpClient` du JDK (aucune dépendance de plus) : un délai de connexion et un délai de
 * requête courts, `Content-Type: application/json`. Un 429 rend l'attente de `Retry-After` (en secondes, éventuellement avec
 * décimales). Aucune de ses erreurs n'est notée avec son message : [DiscordWebhook] n'en garde que le nom.
 */
class HttpDiscordTransport(
    private val address: WebhookAddress,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val timeout: Duration = REQUEST_TIMEOUT,
) : DiscordTransport {
    override suspend fun post(body: String): DiscordReply {
        val request = HttpRequest.newBuilder(address.uri)
            .timeout(timeout)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", "silooo-core")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()
        val retryAfter = response.headers().firstValue("Retry-After").orElse(null)?.trim()?.toDoubleOrNull()
        return DiscordReply(response.statusCode(), retryAfter?.takeIf { it >= 0.0 }?.let { (it * MILLIS_PER_SECOND).toLong() })
    }

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val MILLIS_PER_SECOND = 1000
    }
}
