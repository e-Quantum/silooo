package com.silooo.core.discord

import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/** Les mots du bot pour « Crée ton vocal » (`docs/discord.md`, « Les commandes, les rappels, le staff »), **à valider par Kr**. */
object VoiceRoomTexts {
    /** Le début du nom de tous les vocaux que le bot crée : c'est à lui que le bot reconnaît les siens au démarrage. */
    const val PREFIX = "Vocal"

    /** Discord refuse un nom de salon de plus de 100 caractères. */
    private const val MAX_NAME = 100

    /** « Vocal de Nom » ; sans nom (le membre est parti en route), « Vocal 3 » d'après [order]. */
    fun roomName(memberName: String?, order: Int): String =
        (if (memberName == null) PREFIX + " " + order else PREFIX + " de " + memberName).take(MAX_NAME)
}

/**
 * « Crée ton vocal » (`docs/discord.md`, « Les commandes, les rappels, le staff »). Un membre qui entre dans le vocal
 * `cree-ton-vocal` reçoit son propre vocal dans la catégorie Vocaux, où le bot le déplace ; un membre qui a déjà le
 * sien y retourne. Le vocal est supprimé quand il se vide. Le bot suit en mémoire les vocaux qu'il a créés et leurs
 * occupants d'après les [DiscordEvent.VoiceMoved].
 *
 * Au démarrage, les vocaux de la catégorie qui portent le nom des siens ([VoiceRoomTexts.PREFIX]) et sont vides sont
 * supprimés, ceux qui ont du monde sont repris avec leur compte. Un vocal dont le déplacement a été refusé (le membre
 * est parti entre-temps) est supprimé [EMPTY_GRACE] plus tard s'il est resté vide.
 */
class VoiceRoomFeature(private val context: BotContext) : Feature {
    override val name = "vocaux"

    /** Les vocaux suivis : l'identifiant du salon et combien de membres y sont. */
    private val occupants = mutableMapOf<String, Int>()

    /** Le vocal de chaque membre, par identifiant de membre. */
    private val owned = mutableMapOf<String, String>()

    /** Combien de vocaux ont été créés depuis le démarrage : le numéro d'un vocal dont le membre n'a plus de nom. */
    private var created = 0

    private val gate = Mutex()

    override suspend fun start() {
        val category = context.ids.category(CATEGORY) ?: return
        val planned = context.ids.channels.values.toSet()
        val candidates = context.server.snapshot().channels.filter {
            it.categoryId == category && it.kind == ChannelKind.VOCAL && it.id !in planned && it.name.startsWith(VoiceRoomTexts.PREFIX)
        }
        gate.withLock {
            for (room in candidates) {
                try {
                    val members = context.server.voiceMembers(room.id)
                    if (members == 0) context.server.deleteChannel(room.id) else occupants[room.id] = members
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DiscordRefusal) {
                    log.warn("Bot Discord, vocaux : le vocal {} n'a pas pu être nettoyé : {}", room.id, e.message)
                }
            }
        }
    }

    override suspend fun handle(event: DiscordEvent) {
        if (event is DiscordEvent.VoiceMoved) gate.withLock { moved(event) }
    }

    private suspend fun moved(event: DiscordEvent.VoiceMoved) {
        event.fromChannelId?.let { leave(it) }
        val to = event.toChannelId ?: return
        if (to in occupants) {
            occupants[to] = occupants.getValue(to) + 1
        } else if (to == context.ids.channel(HUB)) {
            enter(event.userId)
        }
    }

    /** Un membre quitte [channelId] ; un vocal suivi qui se vide est supprimé. */
    private suspend fun leave(channelId: String) {
        val left = (occupants[channelId] ?: return) - 1
        if (left > 0) {
            occupants[channelId] = left
            return
        }
        forget(channelId)
        try {
            context.server.deleteChannel(channelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DiscordRefusal) {
            log.warn("Bot Discord, vocaux : le vocal {} n'a pas pu être supprimé : {}", channelId, e.message)
        }
    }

    /** Un membre est entré dans « Crée ton vocal » : son vocal, existant ou neuf, puis le déplacement. */
    private suspend fun enter(userId: String) {
        val category = context.ids.category(CATEGORY) ?: return
        val existing = owned[userId]
        if (existing != null && existing in occupants) {
            try {
                context.server.moveToVoice(userId, existing)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: DiscordRefusal) {
                log.info("Bot Discord, vocaux : le membre {} n'a pas pu être déplacé : {}", userId, e.message)
                if (context.server.snapshot().channels.any { it.id == existing }) return
                forget(existing)
            }
        }
        val room = context.server.createChannel(
            category,
            ChannelSpec(VoiceRoomTexts.roomName(context.server.memberName(userId), ++created), ChannelKind.VOCAL, null, null, null, false),
        )
        occupants[room] = 0
        owned[userId] = room
        try {
            context.server.moveToVoice(userId, room)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DiscordRefusal) {
            log.info("Bot Discord, vocaux : le membre {} n'a pas pu être déplacé : {}", userId, e.message)
            context.timer.after(EMPTY_GRACE) { gate.withLock { dropIfEmpty(room) } }
        }
    }

    /** Supprime [channelId] s'il est suivi et que personne n'y est entré. */
    private suspend fun dropIfEmpty(channelId: String) {
        if (occupants[channelId] != 0) return
        forget(channelId)
        try {
            context.server.deleteChannel(channelId)
        } catch (e: DiscordRefusal) {
            log.warn("Bot Discord, vocaux : le vocal {} n'a pas pu être supprimé : {}", channelId, e.message)
        }
    }

    private fun forget(channelId: String) {
        occupants.remove(channelId)
        owned.values.removeAll { it == channelId }
    }

    companion object {
        /** Le temps laissé à un membre pour entrer dans le vocal que le bot lui a créé avant qu'il soit supprimé. */
        val EMPTY_GRACE: Duration = Duration.ofSeconds(30)

        private const val CATEGORY = "vocaux"
        private const val HUB = "cree-ton-vocal"
        private val log = LoggerFactory.getLogger(VoiceRoomFeature::class.java)
    }
}
