package com.silooo.core.discord

/**
 * Les textes des messages Discord du serveur, tous ici et nulle part ailleurs. Ce sont des textes vus par les joueurs : **tous à
 * valider par Kr** (le filtre « Écrire sans slop » de design/README.md vaut pour eux : une phrase, un fait, pas de mot-réclame, pas
 * de point d'exclamation, pas d'emoji). Chaque fonction prend ses chiffres et ses noms en paramètres : ils viennent de
 * l'événement, jamais d'ici.
 *
 * Le français : « 1er » puis « 2e », « 1 Lingot » puis « 2 Lingots », les milliers séparés par une espace insécable.
 */
object DiscordTexts {
    /** Le prestige d'une entreprise (`PRESTIGE_ENTREPRISE`) ; sans Lingot gagné, la phrase s'arrête au prestige. */
    fun prestige(company: String, lingots: Long, prestigeCount: Long): String {
        val done = "$company a fait son ${ordinal(prestigeCount)} prestige"
        return if (lingots > 0) "$done et gagne ${lingotsText(lingots)}." else "$done."
    }

    /** La Vote party atteinte (`ANNONCE`, `payload.action = voteParty`) ; sans récompense en Lingots dans l'événement, elle n'en promet pas. */
    fun voteParty(votes: Long, lingots: Long?): String {
        val reached = "Vote party : le serveur a atteint ${number(votes)} votes"
        return if (lingots != null && lingots > 0) "$reached, chaque joueur présent reçoit ${lingotsText(lingots)}." else "$reached."
    }

    /** Le boost collectif (`BOOST_COLLECTIF`) ; sans acheteur (un boost gagné), la phrase ne nomme personne. */
    fun collectiveBoost(buyer: String?, percent: Double, minutes: Long): String {
        val effect = "production +${percentText(percent)} % pour tout le monde pendant ${durationText(minutes)}."
        return if (buyer != null) "$buyer offre un boost collectif : $effect" else "Boost collectif : $effect"
    }

    /** Une usine à l'arrêt faute de courant (`ANNONCE`, `payload.action = sansCourant`). */
    fun noPower(player: String, factory: String): String = "L'usine $factory de $player est à l'arrêt faute de courant."

    /**
     * Un nom écrit par un joueur (entreprise, pseudo), rendu sûr pour un message : les sauts de ligne et les caractères de contrôle
     * deviennent des espaces, les espaces se réduisent, le nom est coupé à [MAX_NAME] caractères. `null` s'il ne reste rien.
     */
    fun name(raw: String): String? {
        val cleaned = raw.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("").replace(SPACES, " ").trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= MAX_NAME) cleaned else cleaned.take(MAX_NAME).trimEnd()
    }

    /** « 1er », « 2e », « 1 000e » : le numéro d'une course. */
    internal fun ordinal(n: Long): String = if (n == 1L) "1er" else "${number(n)}e"

    /** « 1 Lingot », « 340 Lingots ». */
    internal fun lingotsText(n: Long): String = "${number(n)} ${if (n == 1L) "Lingot" else "Lingots"}"

    /** Les milliers séparés par une espace insécable : 1234567 donne « 1 234 567 ». */
    internal fun number(n: Long): String {
        val digits = Math.abs(n).toString()
        val grouped = digits.reversed().chunked(3).joinToString(NBSP.toString()).reversed()
        return if (n < 0) "-$grouped" else grouped
    }

    /** Un pourcentage sans zéro inutile : 25 donne « 25 », 12,5 donne « 12,5 » (la virgule du français). */
    internal fun percentText(percent: Double): String {
        if (percent == Math.rint(percent) && Math.abs(percent) < MAX_WHOLE) return number(percent.toLong())
        return String.format(java.util.Locale.ROOT, "%.1f", percent).trimEnd('0').trimEnd('.').replace('.', ',')
    }

    /** « 15 minutes », « une heure », « 2 heures », « 1 h 30 ». */
    internal fun durationText(minutes: Long): String {
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        return when {
            minutes < MINUTES_PER_HOUR -> "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
            rest == 0L -> if (hours == 1L) "une heure" else "$hours heures"
            else -> "$hours h ${rest.toString().padStart(2, '0')}"
        }
    }

    /** La longueur d'un nom dans un message : un nom d'entreprise ou un pseudo en fait 32 au plus, le reste est du bruit. */
    const val MAX_NAME = 48

    private const val NBSP = '\u00A0'
    private const val MINUTES_PER_HOUR = 60L
    private const val MAX_WHOLE = 1e15
    private val SPACES = Regex(" {2,}")
}
