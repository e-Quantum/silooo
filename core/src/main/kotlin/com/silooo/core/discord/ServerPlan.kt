package com.silooo.core.discord

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Le plan du serveur Discord (`discord/plan-serveur.json`, ADR 0007) : les rôles, les catégories et leurs salons,
 * chacun avec la phase où il apparaît. C'est la seule description du serveur ; le bot le lit au démarrage et crée
 * ce qui manque, sans jamais rien supprimer ([PlanApplier]).
 *
 * Les clés sont en français, comme celles de `definitions/` : Kr lit et modifie ce fichier (noms, sujets, emoji).
 * Le schéma `plan-serveur.schema.json` est contrôlé par `tools/verifier.sh` ; [check] refait ici les contrôles
 * que le schéma ne sait pas écrire (unicité, références entre rôles et salons, phases).
 */
@Serializable
data class ServerPlan(
    @SerialName("\$schema") val schema: String? = null,
    val commentaire: String? = null,
    val roles: List<PlannedRole> = emptyList(),
    val categories: List<PlannedCategory> = emptyList(),
) {
    val channels: List<PlannedChannel> get() = categories.flatMap { it.salons }

    /** Le salon où le bot écrit ses comptes rendus. Le plan doit l'avoir ([check]). */
    val journal: PlannedChannel? get() = channels.firstOrNull { it.id == JOURNAL_ID }

    /** Les problèmes du plan que le schéma JSON ne voit pas ; vide quand le plan est bon. */
    fun check(): List<String> {
        val problems = mutableListOf<String>()
        fun unique(what: String, values: List<String>) {
            values.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problems += "$what « $it » est en double" }
        }
        unique("le rôle", roles.map { it.id })
        unique("le nom de rôle", roles.map { it.nom })
        unique("la catégorie", categories.map { it.id })
        unique("le nom de catégorie", categories.map { it.nom })
        unique("le salon", channels.map { it.id })
        unique("le nom de salon", channels.map { it.discordName })
        val roleIds = roles.map { it.id }.toSet()
        fun references(owner: String, listed: List<String>?) {
            listed.orEmpty().filter { it !in roleIds }.forEach { problems += "$owner cite le rôle inconnu « $it »" }
        }
        for (category in categories) {
            references("la catégorie « ${category.id} »", category.visible)
            for (channel in category.salons) {
                references("le salon « ${channel.id} »", channel.ecriture)
                references("le salon « ${channel.id} »", channel.visible)
                if (channel.phase.ordinal < category.phase.ordinal) {
                    problems += "le salon « ${channel.id} » apparaît avant sa catégorie « ${category.id} »"
                }
                if (channel.emoji.isBlank()) problems += "le salon « ${channel.id} » n'a pas d'emoji"
                if (EMOJI.containsMatchIn(channel.nom)) problems += "le nom du salon « ${channel.id} » contient un emoji : il va dans la clé emoji"
                if (channel.type != ChannelKind.VOCAL && !TEXT_NAME.matches(channel.nom)) {
                    problems += "le nom du salon « ${channel.id} » doit être en minuscules, sans espace (Discord le réécrirait)"
                }
            }
        }
        roles.filter { it.couleur != null && !COLOR.matches(it.couleur) }.forEach { problems += "la couleur du rôle « ${it.id} » doit s'écrire #rrggbb en minuscules" }
        if (journal == null) problems += "le plan n'a pas de salon « $JOURNAL_ID », où le bot écrit ses comptes rendus"
        return problems
    }

    companion object {
        const val JOURNAL_ID = "journal-du-bot"
        const val RESOURCE = "discord/plan-serveur.json"

        /** Les caractères que Discord et le filtre anti-IA tiennent pour des emoji. */
        val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B50}\\x{2B55}\\x{2B1B}\\x{2B1C}\\x{231A}\\x{231B}\\x{23E9}-\\x{23F3}\\x{23F8}-\\x{23FA}\\x{FE0F}\\x{200D}]")
        private val TEXT_NAME = Regex("^[\\p{Ll}0-9][\\p{Ll}0-9-]*$")
        private val COLOR = Regex("^#[0-9a-f]{6}$")

        private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

        /** Lit un plan ; une clé inconnue ou un problème de [check] lève [PlanException] avec son détail. */
        fun parse(text: String, origin: String = RESOURCE): ServerPlan {
            val plan = try {
                json.decodeFromString(serializer(), text)
            } catch (e: IllegalArgumentException) {
                throw PlanException("$origin : ${e.message}")
            }
            val problems = plan.check()
            if (problems.isNotEmpty()) throw PlanException("$origin : " + problems.joinToString(" ; "))
            return plan
        }

        /** Le plan du dépôt, dans les ressources du service. */
        fun fromClasspath(): ServerPlan {
            val stream = ServerPlan::class.java.classLoader.getResourceAsStream(RESOURCE)
                ?: throw PlanException(RESOURCE + " est absent des ressources")
            return parse(stream.use { it.readBytes().decodeToString() })
        }
    }
}

/** Le plan est illisible ou incohérent : le bot ne démarre pas, le service continue. */
class PlanException(message: String) : Exception(message)

@Serializable
enum class ChannelKind {
    @SerialName("texte") TEXTE,
    @SerialName("annonces") ANNONCES,
    @SerialName("forum") FORUM,
    @SerialName("vocal") VOCAL,
}

/**
 * Un rôle du plan. [couleur] : une couleur de la palette (`#rrggbb`), ou rien ; [separe] : affiché à part dans la
 * liste des membres ; [mentionnable] : par tout le monde (le bot, lui, mentionne ce qu'il veut).
 */
@Serializable
data class PlannedRole(
    val id: String,
    val nom: String,
    val couleur: String? = null,
    val separe: Boolean = false,
    val mentionnable: Boolean = false,
    val phase: PlanPhase = PlanPhase.ATTENTE,
)

/**
 * Une catégorie et ses salons. [visible] : les rôles qui la voient, elle et ses salons ; absent, tout le monde.
 */
@Serializable
data class PlannedCategory(
    val id: String,
    val nom: String,
    val phase: PlanPhase = PlanPhase.ATTENTE,
    val visible: List<String>? = null,
    val salons: List<PlannedChannel> = emptyList(),
)

/**
 * Un salon. [emoji] est le seul texte du dépôt avec un emoji (décision de Kr, `anti_ia.py` saute cette clé) ;
 * [nom] n'en a pas. [ecriture] : les rôles qui écrivent, vide pour le bot seul, absent pour tout le monde.
 * [visible] : qui voit le salon ; absent, la règle de sa catégorie. [verrouille] : un vocal où personne n'entre
 * (les trois vocaux d'information du haut).
 */
@Serializable
data class PlannedChannel(
    val id: String,
    val emoji: String,
    val nom: String,
    val type: ChannelKind,
    val sujet: String? = null,
    val phase: PlanPhase = PlanPhase.ATTENTE,
    val ecriture: List<String>? = null,
    val visible: List<String>? = null,
    val verrouille: Boolean = false,
) {
    /** Le nom tel que Discord l'affiche : « 👋・bienvenue » pour un salon écrit, « 🌐 play.silooo.com » pour un vocal. */
    val discordName: String
        get() = if (type == ChannelKind.VOCAL) "$emoji $nom" else emoji + SEPARATOR + nom

    companion object {
        /** Le point médian japonais, celui des serveurs qui ont servi de modèle. */
        const val SEPARATOR = "・"
    }
}

/** La phase d'une entrée du plan, sérialisée par son mot ; [ServerPhase] est la phase courante du serveur. */
@Serializable
enum class PlanPhase(val word: String) {
    @SerialName("attente") ATTENTE("attente"),
    @SerialName("beta") BETA("beta"),
    @SerialName("ouvert") OUVERT("ouvert");

    fun existsIn(current: ServerPhase): Boolean = ordinal <= current.ordinal
}
