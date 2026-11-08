package com.silooo.core.discord

import java.time.Instant
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TicketArchiveRules(
    @SerialName("messagesParPage") val pageSize: Int,
    @SerialName("etapesParPassage") val stepsPerTick: Int,
    @SerialName("ticketsParPassage") val jobsPerTick: Int,
    @SerialName("repriseSecondes") val retrySeconds: Long,
    @SerialName("bailSecondes") val leaseSeconds: Long,
    @SerialName("conservationJours") val retentionDays: Long,
    @SerialName("recherchePages") val lookupPages: Int,
    @SerialName("fichierMaxOctets") val maxPartBytes: Int,
) {
    init {
        require(pageSize in 1..100 && stepsPerTick in 1..20 && jobsPerTick in 1..20)
        require(retrySeconds in 5..3600 && leaseSeconds in 60..3600 && leaseSeconds > retrySeconds)
        require(retentionDays in 1..365 && lookupPages in 1..100 && maxPartBytes in 1024..TextFile.MAX_BYTES)
    }
}

data class TranscriptAttachment(val id: String, val name: String, val url: String, val size: Long, val contentType: String? = null)

data class TranscriptMessage(
    val id: String, val authorId: String, val authorName: String, val text: String, val at: Instant,
    val attachments: List<TranscriptAttachment> = emptyList(), val embeds: List<String> = emptyList(),
    val editedAt: Instant? = null,
)

/** Les messages d'une seule page, rangés du plus ancien au plus récent. Le curseur reste un identifiant Discord. */
data class TranscriptPage(val messages: List<TranscriptMessage>)

sealed interface ArchiveLookup {
    data class Found(val messageId: String) : ArchiveLookup
    data object Absent : ArchiveLookup
    data object Incomplete : ArchiveLookup
}

/** Identifiants non signés : ne pas comparer leurs chaînes ni les convertir en Long signé. */
internal fun archiveMessageNumber(value: String): java.math.BigInteger {
    require(value.matches(Regex("[0-9]{1,20}")))
    return value.toBigInteger()
}

internal fun archiveNonce(reference: String): String =
    MessageDigest.getInstance("SHA-256").digest(reference.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Instantané textuel ; aucune pièce jointe n'est téléchargée. Les URL de Discord peuvent expirer. */
internal fun transcriptBody(job: TicketArchive, messages: List<TranscriptMessage>): String = buildString {
    append("Ticket n°").append(job.ticketId).append("\nFermeture demandée par ").append(job.requestedBy)
    append(" le ").append(job.requestedAt).append("\nDates en UTC. Pièces jointes : références, sans copie des fichiers.\n\n")
    for (m in messages) {
        append(m.at).append(" | ").append(m.authorName).append(" (").append(m.authorId).append(") | message ").append(m.id)
        m.editedAt?.let { append(" | modifié ").append(it) }
        append('\n').append(m.text).append('\n')
        for (a in m.attachments) {
            append("Fichier : ").append(a.name).append(" | ").append(a.id).append(" | ").append(a.size).append(" octets")
            a.contentType?.let { append(" | ").append(it) }
            append('\n').append(a.url).append('\n')
        }
        for (embed in m.embeds) append("Carte : ").append(embed).append('\n')
        append('\n')
    }
}

/** Seul le salon source confirmé absent est assimilable à une suppression déjà réussie. */
class DiscordChannelMissing(val channelId: String) : DiscordRefusal("Le salon " + channelId + " n'existe plus.")

/** Refus sûr et codé, sans contenu de message ni détail de requête dans les journaux. */
internal class TicketArchiveBlocked(val code: String) : DiscordRefusal("L'archive du ticket doit être vérifiée avant la fermeture.")
