package com.silooo.core.discord

import com.silooo.core.definitions.DefinitionsException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.DateTimeException
import java.time.ZoneId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * La forme de definitions/discord.json (schéma : definitions/schemas/discord.schema.json) : les chiffres du Discord,
 * tenus par le bot Oooscar et nulle part ailleurs ; ils sont **à valider par Kr**. Chargé à part des définitions du
 * jeu : le jeu n'en a pas besoin, et le bot peut manquer sans que le jeu s'arrête. Les salons et les rôles sont cités
 * par leur identifiant dans le plan du serveur ; [problems] vérifie qu'ils y sont.
 */
@Serializable
data class DiscordDefinitions(
    val version: Int,
    @SerialName("commentaire") val comment: String,
    /** Le fuseau des heures du fichier, au format IANA. */
    @SerialName("fuseau") val timeZone: String,
    @SerialName("accueil") val welcome: WelcomeRules,
    @SerialName("statut") val status: StatusRules,
    @SerialName("vocauxInfo") val infoVoice: InfoVoiceRules,
    @SerialName("spoils") val spoils: SpoilRules,
    @SerialName("xp") val xp: XpRules,
    @SerialName("reactions") val reactions: ReactionRules,
    @SerialName("meilleursMoments") val highlights: HighlightRules,
    @SerialName("monteCharge") val elevator: ElevatorRules,
    @SerialName("rapport") val report: ReportRules,
    @SerialName("rappels") val reminders: ReminderRules,
    @SerialName("giveaways") val giveaways: GiveawayRules,
    @SerialName("concours") val contests: ContestRules,
    @SerialName("aides") val helpers: HelperRules,
    @SerialName("admissions") val admissions: AdmissionRules,
    @SerialName("bienvenue") val banner: BannerRules,
) {
    /** Le fuseau des heures du fichier. */
    val zone: ZoneId get() = ZoneId.of(timeZone)

    /** Ce qui cloche entre ce fichier et le plan du serveur [plan] : vide quand tout se tient. */
    fun problems(plan: ServerPlan): List<String> {
        val problems = mutableListOf<String>()
        try {
            ZoneId.of(timeZone)
        } catch (e: DateTimeException) {
            problems += "fuseau : « $timeZone » est inconnu"
        }
        if (xp.pointsMax < xp.pointsMin) problems += "xp : pointsMax (" + xp.pointsMax + ") est sous pointsMin (" + xp.pointsMin + ")"
        for ((lower, upper) in xp.roles.zipWithNext()) {
            if (upper.level <= lower.level) problems += "xp.roles : le niveau " + upper.level + " devrait dépasser " + lower.level
        }
        val roleIds = plan.roles.map { it.id }.toSet()
        for (levelRole in xp.roles) {
            if (levelRole.role !in roleIds) problems += "xp.roles : le rôle « " + levelRole.role + " » n'est pas dans le plan"
        }
        val channelIds = plan.channels.map { it.id }.toSet()
        for (channel in xp.channels) {
            if (channel !in channelIds) problems += "xp.salons : le salon « $channel » n'est pas dans le plan"
        }
        for (channel in reactions.channels) {
            if (channel !in channelIds) problems += "reactions.salons : le salon « $channel » n'est pas dans le plan"
        }
        return problems
    }

    /** Les points qu'il faut pour le niveau [level] : base × n + croissance × n × (n - 1) / 2. */
    fun pointsForLevel(level: Int): Long =
        xp.levelBase.toLong() * level + xp.levelGrowth.toLong() * level * (level - 1) / 2

    /** Le niveau atteint avec [points]. */
    fun levelFor(points: Long): Int {
        var level = 0
        while (pointsForLevel(level + 1) <= points) level++
        return level
    }

    companion object {
        const val FILE = "discord.json"

        // ignoreUnknownKeys reste faux : le fichier et les classes Kotlin disent la même chose.
        private val json = Json { ignoreUnknownKeys = false }

        /** Lit `discord.json` dans [directory] ; lève [DefinitionsException] s'il manque ou s'il est illisible. */
        fun load(directory: Path): DiscordDefinitions {
            val file = directory.resolve(FILE)
            if (!Files.isRegularFile(file)) {
                throw DefinitionsException("Le fichier " + FILE + " manque dans le dossier des définitions : " + directory)
            }
            val text = try {
                String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            } catch (e: IOException) {
                throw DefinitionsException("Impossible de lire $file : ${e.message}", e)
            }
            return parse(text)
        }

        /** Charge et vérifie contre [plan] ; lève [DefinitionsException] avec tous les problèmes. */
        fun loadChecked(directory: Path, plan: ServerPlan): DiscordDefinitions {
            val definitions = load(directory)
            val problems = definitions.problems(plan)
            if (problems.isNotEmpty()) {
                throw DefinitionsException(FILE + " ne colle pas au plan du serveur :\n" + problems.joinToString("\n") { "  - $it" })
            }
            return definitions
        }

        // missingFields est marqué expérimental dans kotlinx.serialization ; on s'en sert pour nommer la clé.
        @OptIn(ExperimentalSerializationApi::class)
        fun parse(text: String): DiscordDefinitions =
            try {
                json.decodeFromString(serializer(), text)
            } catch (e: MissingFieldException) {
                throw DefinitionsException(FILE + " : clé manquante " + e.missingFields.joinToString(", ") { "« $it »" }, e)
            } catch (e: SerializationException) {
                throw DefinitionsException(FILE + " : " + firstLine(e), e)
            } catch (e: IllegalArgumentException) {
                throw DefinitionsException(FILE + " : " + firstLine(e), e)
            }

        private fun firstLine(e: Exception): String = e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName
    }
}

/** Le bonjour d'Oooscar après l'accueil guidé, et les arrivées groupées. */
@Serializable
data class WelcomeRules(
    @SerialName("bonjourSecondes") val helloSeconds: HelloSeconds,
    /** Quand les arrivées se bousculent, un seul bonjour toutes ces secondes. */
    @SerialName("groupeSecondes") val groupSeconds: Int,
    /** Combien de nouveaux au plus un bonjour groupé mentionne. */
    @SerialName("groupeMentionsMax") val groupMentionsMax: Int,
)

/** Les secondes entre la fin de l'accueil guidé et le bonjour, selon le rôle de plateforme. */
@Serializable
data class HelloSeconds(val console: Int, val telephone: Int, val pc: Int) {
    /** Le délai pour [platform] (un identifiant de rôle du plan) ; le plus long quand la plateforme est inconnue. */
    fun forPlatform(platform: String?): Int = when (platform) {
        "console" -> console
        "telephone" -> telephone
        "pc" -> pc
        else -> maxOf(console, telephone, pc)
    }
}

/** Le statut d'Oooscar, qui alterne deux lignes. */
@Serializable
data class StatusRules(@SerialName("alternanceSecondes") val alternateSeconds: Int)

/** Les vocaux d'info renommés par le bot, dans la limite de Discord. */
@Serializable
data class InfoVoiceRules(
    @SerialName("renommagesMax") val renamesMax: Int,
    @SerialName("fenetreMinutes") val windowMinutes: Int,
)

/** Les coulisses. */
@Serializable
data class SpoilRules(
    /** Le seuil proposé par défaut, en part des membres. */
    @SerialName("partDesMembres") val memberShare: Double,
    @SerialName("miseAJourSecondes") val refreshSeconds: Int,
    /** Le nom de l'emoji du serveur qui compte. */
    val emoji: String,
    @SerialName("nuitDebutHeure") val nightStartHour: Int,
    @SerialName("nuitFinHeure") val nightEndHour: Int,
)

/** L'XP de tchat. */
@Serializable
data class XpRules(
    @SerialName("pointsMin") val pointsMin: Int,
    @SerialName("pointsMax") val pointsMax: Int,
    @SerialName("intervalleSecondes") val intervalSeconds: Int,
    @SerialName("niveauBase") val levelBase: Int,
    @SerialName("niveauCroissance") val levelGrowth: Int,
    /** Les salons du plan où les messages comptent. */
    @SerialName("salons") val channels: List<String>,
    /** Les rôles du plan donnés aux niveaux, du plus bas au plus haut. */
    @SerialName("roles") val roles: List<LevelRole>,
    @SerialName("classementTaille") val leaderboardSize: Int,
)

/** Un rôle du plan donné à un niveau. */
@Serializable
data class LevelRole(@SerialName("niveau") val level: Int, val role: String)

/** Le top réactions de la semaine. */
@Serializable
data class ReactionRules(
    @SerialName("topTaille") val topSize: Int,
    /** Les salons du plan où les réactions comptent. */
    @SerialName("salons") val channels: List<String>,
)

/** Les meilleurs moments. */
@Serializable
data class HighlightRules(val emoji: String, @SerialName("seuil") val threshold: Int)

/** Le monte-charge. */
@Serializable
data class ElevatorRules(@SerialName("attenteMinutes") val waitMinutes: Int)

/** Le rapport du lundi. */
@Serializable
data class ReportRules(
    @SerialName("heure") val hour: Int,
    @SerialName("reliesSousJours") val linkedWithinDays: Int,
    @SerialName("revenusApresJours") val returnedAfterDays: Int,
)

/** Les rappels privés. */
@Serializable
data class ReminderRules(
    @SerialName("parJour") val perDay: Int,
    @SerialName("nuitDebutHeure") val nightStartHour: Int,
    @SerialName("nuitFinHeure") val nightEndHour: Int,
)

/** Les giveaways. */
@Serializable
data class GiveawayRules(@SerialName("parMois") val perMonth: Int)

/** Les concours. */
@Serializable
data class ContestRules(
    @SerialName("parMoisAvantBeta") val perMonthBeforeBeta: Int,
    @SerialName("membresPourHebdo") val membersForWeekly: Int,
)

/** Les Aides sur candidature. */
@Serializable
data class HelperRules(@SerialName("candidaturesDesMembres") val applicationsFromMembers: Int)

/** Les cartes d'admission du staff. */
@Serializable
data class AdmissionRules(
    /** Toutes ces secondes, le bot cherche les inscriptions sans carte. */
    @SerialName("relectureSecondes") val refreshSeconds: Int,
)

/** L'image du message de `#bienvenue` : celle du jour de [dayHour] à [nightHour], celle de la nuit le reste du temps. */
@Serializable
data class BannerRules(
    @SerialName("jourHeure") val dayHour: Int,
    @SerialName("nuitHeure") val nightHour: Int,
)
