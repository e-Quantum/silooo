package com.silooo.core.site

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Tout ce que le site suppose du webhook de PayNow, et rien d'autre ailleurs : où est la signature, comment elle se calcule, à quoi le corps
 * ressemble. **Le format exact des webhooks de PayNow est à confirmer par Kr** (dans le tableau de bord de la boutique, ou la documentation de
 * PayNow). Ce qui est supposé ici :
 *
 *  - la signature est dans l'en-tête [SIGNATURE_HEADER] : l'HMAC-SHA256 du corps brut (octets reçus, avant tout décodage), écrit en hexadécimal
 *    minuscule, avec `SILOOO_PAYNOW_WEBHOOK_SECRET` pour clé (une majuscule dans l'en-tête est acceptée) ;
 *  - le corps est un objet JSON à trois champs : [ORDER_ID_FIELD] (l'identifiant de la commande, un texte ou un nombre), [GAMERTAG_FIELD] (le pseudo
 *    saisi à l'achat) et [GEMS_FIELD] (la quantité de Gemmes, un entier) ; les champs en plus sont ignorés.
 *
 * Si PayNow signe autrement (autre en-tête, préfixe `sha256=`, horodatage signé avec le corps) ou nomme autrement ses champs (ou les imbrique dans
 * un objet `data`), c'est cette classe, ses constantes et son test (`PayNowWebhookTest`) qu'on change. `WebhookRoutes` ne sait rien de tout cela.
 */
class PayNowWebhook(secret: String) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC)

    /** Une commande lue dans le corps, ou la raison du refus (un message qui nomme le champ, jamais un extrait du corps). */
    sealed interface Reading {
        data class Order(val orderId: String, val gamertag: String, val gemmes: Long) : Reading

        data class Refused(val message: String) : Reading
    }

    /** Vrai si [header] est la signature de [body]. La comparaison est en temps constant, comme celle de la clé du serveur. */
    fun isAuthentic(body: ByteArray, header: String?): Boolean {
        if (header == null) return false
        val expected = hex(Mac.getInstance(HMAC).apply { init(key) }.doFinal(body))
        return MessageDigest.isEqual(sha256(header.trim().lowercase()), sha256(expected))
    }

    /** Lit une commande dans [body]. Un champ manquant ou illisible rend [Reading.Refused] avec son nom. */
    fun read(body: ByteArray): Reading {
        val json = try {
            Json.parseToJsonElement(body.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            return Reading.Refused("Corps illisible : un objet JSON est attendu")
        } as? JsonObject ?: return Reading.Refused("Corps illisible : un objet JSON est attendu")

        val orderId = text(json, ORDER_ID_FIELD) ?: return missing(ORDER_ID_FIELD)
        val gamertag = text(json, GAMERTAG_FIELD) ?: return missing(GAMERTAG_FIELD)
        val gemsPrimitive = (json[GEMS_FIELD] as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return missing(GEMS_FIELD)
        val gemmes = gemsPrimitive.longOrNull?.takeIf { it > 0 } ?: return Reading.Refused("Le champ $GEMS_FIELD doit être un entier strictement positif")
        if (orderId.length > MAX_ORDER_ID_LENGTH) return Reading.Refused("Le champ $ORDER_ID_FIELD est trop long")
        if (gamertag.length > MAX_GAMERTAG_LENGTH) return Reading.Refused("Le champ $GAMERTAG_FIELD est trop long")
        return Reading.Order(orderId, gamertag, gemmes)
    }

    private fun missing(field: String) = Reading.Refused("Champ manquant ou vide : $field")

    /** Le texte d'un champ (une chaîne ou un nombre), sans espace aux bords ; `null` s'il manque, vaut `null` ou est vide. */
    private fun text(json: JsonObject, field: String): String? =
        (json[field] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** L'en-tête de la signature (supposé). */
        const val SIGNATURE_HEADER = "PayNow-Signature"

        const val ORDER_ID_FIELD = "order_id"
        const val GAMERTAG_FIELD = "gamertag"
        const val GEMS_FIELD = "gemmes"

        /** Les longueurs des colonnes de `shop_deliveries` (`order_id` VARCHAR(64), `gamertag` VARCHAR(32)). */
        const val MAX_ORDER_ID_LENGTH = 64
        const val MAX_GAMERTAG_LENGTH = 32

        /** Le corps d'un webhook tient en quelques lignes : au-delà de 4 Ko, il est refusé sans être lu. */
        const val MAX_BODY_BYTES = 4096L

        private const val HMAC = "HmacSHA256"

        private fun hex(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(bytes)

        private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    }
}
