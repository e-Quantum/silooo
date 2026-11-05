package com.silooo.core.registration

import com.silooo.core.site.Empreinte
import java.sql.Connection
import java.time.Clock
import java.time.temporal.ChronoUnit

/** Ce que devient un code de « Relier Discord » tapé en jeu. */
sealed interface RedeemOutcome {
    /** Ce qui a été tapé n'est pas un code, ou aucun code ne le porte (un code déjà utilisé n'existe plus). */
    data object UnknownCode : RedeemOutcome

    /** Le code existe mais ses dix minutes sont passées. */
    data object Expired : RedeemOutcome

    /** Ce gamertag appartient déjà à un autre compte du site, ou à une inscription déjà reliée à un autre Discord : rien n'est écrit. */
    data object PlayerTaken : RedeemOutcome

    /** Le compte du code porte déjà un autre gamertag : rien n'est écrit (il faut d'abord le délier depuis le site). */
    data object AlreadyLinked : RedeemOutcome

    /** Le gamertag est posé sur le compte, l'inscription écrite et reliée, le code effacé. */
    data class Linked(val accountId: Long) : RedeemOutcome
}

/**
 * « Relier Discord » : le compte du site entré par Discord montre un code de six caractères (dix minutes), le joueur le tape en
 * jeu, et le service pose son gamertag sur le compte. Le service décide, le jeu ne fait que dire quel gamertag a tapé le code.
 *
 * Chaque méthode s'exécute dans la transaction de l'appelant. [generate] ne change que dans les tests.
 */
class DiscordLinks(
    private val accounts: WebAccountStore,
    private val links: DiscordLinkStore,
    private val registrations: SiteRegistrationStore,
    private val clock: Clock,
    private val generate: () -> String = { LinkCodes.generate() },
) {
    /**
     * Le code vivant du compte s'il en a un ; sinon un nouveau, tiré parmi ceux que la base ne porte pas, valable
     * [LinkCodes.LIFETIME]. Il prend la place d'un code expiré du même compte.
     */
    fun issue(connection: Connection, accountId: Long): LinkCode {
        // DATETIME(3) : l'heure rendue est celle que la base relira.
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        links.findByAccount(connection, accountId)?.takeIf { !it.expired(now) }?.let { return it }
        repeat(MAX_DRAWS) {
            val code = generate()
            // Un code encore en base, vivant ou périmé pas encore purgé, n'est pas repris : on en tire un autre.
            if (links.findByCode(connection, code) == null) {
                val expiresAt = now.plus(LinkCodes.LIFETIME)
                links.replace(connection, accountId, code, now, expiresAt)
                return LinkCode(code, accountId, now, expiresAt)
            }
        }
        throw IllegalStateException("Aucun code de liaison libre après $MAX_DRAWS tirages")
    }

    /**
     * Le joueur [gamertag] a tapé [typed] en jeu. [admit] est la règle d'admission au moment du lien Discord
     * (`SiteAdmission.admitsAt(DISCORD_LINK)`), [pioneer] dit si l'inscription est d'avant l'ouverture.
     *
     * En [RedeemOutcome.Linked] : l'empreinte du gamertag est posée sur le compte (sans réécriture si c'est déjà la sienne),
     * l'inscription de `site_registrations` est écrite (ou retrouvée) et reliée à l'identifiant Discord du compte, puis le
     * code est effacé. Tout refus est rendu avant la première écriture, et le code reste.
     *
     * Lève [IllegalStateException] dans un seul cas, que la transaction de l'appelant annule en entier : `site_registrations`
     * porte déjà l'identifiant Discord de ce compte sur un autre gamertag. Aucune lecture ne le montre avant d'écrire ; c'est une
     * donnée à corriger à la main.
     */
    fun redeem(connection: Connection, typed: String, gamertag: String, admit: Boolean, pioneer: Boolean): RedeemOutcome {
        val code = LinkCodes.normalize(typed) ?: return RedeemOutcome.UnknownCode
        val link = links.findByCode(connection, code) ?: return RedeemOutcome.UnknownCode
        if (link.expired(clock.instant())) return RedeemOutcome.Expired
        val account = accounts.find(connection, link.accountId) ?: return RedeemOutcome.UnknownCode

        val playerXuid = Empreinte.duGamertag(gamertag)
        val key = SiteRegistrations.key(gamertag)
        val alreadyHers = account.playerXuid == playerXuid
        if (!alreadyHers) {
            if (account.playerXuid != null) return RedeemOutcome.AlreadyLinked
            val holder = accounts.findByPlayer(connection, playerXuid)
            if (holder != null && holder.id != account.id) return RedeemOutcome.PlayerTaken
            // Une inscription déjà reliée à un Discord (ce n'est pas celui de ce compte, qui n'avait pas de gamertag) n'est pas reprise.
            if (registrations.find(connection, key)?.linked == true) return RedeemOutcome.PlayerTaken
            when (accounts.setPlayer(connection, account.id, gamertag, playerXuid)) {
                PlayerOutcome.SET -> Unit
                PlayerOutcome.PLAYER_TAKEN -> return RedeemOutcome.PlayerTaken
                PlayerOutcome.NOT_FOUND -> return RedeemOutcome.UnknownCode
            }
        }

        registrations.insert(connection, key, gamertag, admitted = admit, pioneer = pioneer)
        accounts.discordUserId(connection, account.id)?.let { discordUserId ->
            when (registrations.link(connection, key, discordUserId, admit)) {
                LinkOutcome.LINKED, LinkOutcome.ALREADY_LINKED -> Unit
                LinkOutcome.DISCORD_TAKEN -> throw IllegalStateException("Le compte Discord du code est déjà relié à un autre gamertag dans site_registrations")
                LinkOutcome.NOT_REGISTERED -> throw IllegalStateException("L'inscription de ce gamertag manque dans site_registrations")
            }
        }
        links.delete(connection, code)
        return RedeemOutcome.Linked(account.id)
    }

    private companion object {
        const val MAX_DRAWS = 20
    }
}
