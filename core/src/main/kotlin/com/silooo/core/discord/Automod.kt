package com.silooo.core.discord

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Les listes de l'AutoMod (`core/src/main/resources/discord/automod.json`) : les mots refusés, les demandes de contact,
 * la limite de mentions et les liens. Les mêmes listes serviront au filtre du tchat relié (`docs/discord.md`, « Le
 * tchat relié ») : le jeu et Discord refusent les mêmes choses. Les mots sont ceux de Discord, avec ses jokers `*` au
 * début ou à la fin ; les motifs sont des expressions régulières de Discord (celles de Rust : pas de retour arrière).
 * La liste de départ est **à compléter par Kr**.
 */
@Serializable
data class AutomodLists(
    @SerialName("motsRefuses") val refusedWords: AutomodWords,
    @SerialName("demandesDeContact") val contactRequests: AutomodPatterns,
    @SerialName("spamDeMentions") val mentionSpam: AutomodMentions,
    @SerialName("liens") val links: AutomodLinks,
) {
    /** Les mots refusés, en clair. */
    val motsRefuses: List<String> get() = refusedWords.words

    /** Les motifs des demandes de contact, en clair. */
    val demandesDeContact: List<String> get() = contactRequests.patterns
}

/** Des mots refusés ; [allowed] : ce qui ne déclenche jamais la règle. */
@Serializable
data class AutomodWords(
    @SerialName("mots") val words: List<String>,
    @SerialName("autorises") val allowed: List<String>,
)

@Serializable
data class AutomodPatterns(@SerialName("motifs") val patterns: List<String>)

/** [limit] : à partir de combien de mentions dans un message la règle bloque. */
@Serializable
data class AutomodMentions(@SerialName("limite") val limit: Int)

/** Les liens : [words] les repèrent, [allowed] sont ceux que le serveur laisse passer. */
@Serializable
data class AutomodLinks(
    @SerialName("autorises") val allowed: List<String>,
    @SerialName("mots") val words: List<String>,
)

/** Lit les listes de l'AutoMod dans les ressources du service. */
object AutomodRules {
    const val RESOURCE = "discord/automod.json"

    // ignoreUnknownKeys reste faux : le fichier et les classes Kotlin disent la même chose.
    private val json = Json { ignoreUnknownKeys = false }

    /** Les listes du dépôt ; lève [IllegalStateException] si le fichier manque ou s'il est illisible. */
    fun load(): AutomodLists {
        val stream = AutomodRules::class.java.classLoader.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException(RESOURCE + " est absent des ressources")
        return parse(stream.use { it.readBytes().decodeToString() })
    }

    fun parse(text: String): AutomodLists =
        try {
            json.decodeFromString(AutomodLists.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalStateException(RESOURCE + " : " + (e.message?.lineSequence()?.firstOrNull() ?: "illisible"), e)
        }
}

/**
 * Les mots du bot pour l'AutoMod, **à valider par Kr**. Seul le journal du bot (le staff) les lit ; Discord montre
 * aux membres ses propres mots quand une règle bloque un message.
 */
object AutomodTexts {
    const val RULE_WORDS = "Oooscar : mots refusés"
    const val RULE_CONTACT = "Oooscar : demandes de contact"
    const val RULE_MENTIONS = "Oooscar : spam de mentions"
    const val RULE_LINKS = "Oooscar : liens"

    /** Toutes les règles sont posées. */
    fun posted(count: Int): String = "AutoMod : " + count + (if (count > 1) " règles posées." else " règle posée.")

    /** Des règles refusées : [reasons] dit pourquoi, une ligne par règle. */
    fun partly(posted: Int, total: Int, reasons: List<String>): String =
        "AutoMod : " + posted + " sur " + total + (if (total > 1) " règles posées." else " règle posée.") + reasons.joinToString("") { "\n- " + it }
}

/**
 * L'AutoMod de Discord, posé par le bot à chaque démarrage (`docs/discord.md`, « Le tchat relié ») : quatre règles
 * qui bloquent le message avant qu'il paraisse et alertent dans `#signalements`. Les règles portent le nom du bot et
 * se remplacent d'un démarrage à l'autre : changer `automod.json` suffit, et celles que Kr pose à la main ne sont pas
 * touchées. Le staff et la modération ne sont jamais bloqués ; les créateurs peuvent poster des liens.
 *
 * Sans le droit « Gérer le serveur », Discord refuse : une ligne au journal du bot et le bot continue. Aucun
 * événement à gérer.
 */
class AutomodFeature(
    private val context: BotContext,
    private val journal: BotJournal,
    private val lists: AutomodLists = AutomodRules.load(),
) : Feature {
    override val name = "automod"

    override suspend fun start() {
        val rules = rules()
        var posted = 0
        val reasons = mutableListOf<String>()
        for (rule in rules) {
            try {
                context.server.setAutomodRule(rule)
                posted++
            } catch (e: CancellationException) {
                throw e
            } catch (e: DiscordRefusal) {
                log.warn("Bot Discord, AutoMod : la règle « {} » n'a pas été posée : {}", rule.name, e.message)
                reasons += e.message ?: rule.name
            } catch (e: Exception) {
                log.warn("Bot Discord, AutoMod : la règle « {} » a échoué : {}", rule.name, e.toString())
                reasons += "la règle « " + rule.name + " » a échoué, le détail est dans les journaux du service"
            }
        }
        journal.line(if (reasons.isEmpty()) AutomodTexts.posted(posted) else AutomodTexts.partly(posted, rules.size, reasons))
    }

    /** Les quatre règles du bot, avec le salon d'alerte et les rôles exemptés du plan. */
    fun rules(): List<AutomodRule> {
        val alert = context.ids.channel(REPORTS)
        val staff = setOfNotNull(context.ids.role(STAFF), context.ids.role(MODERATION))
        val withCreators = staff + setOfNotNull(context.ids.role(CREATOR))
        return listOf(
            AutomodRule(
                AutomodTexts.RULE_WORDS,
                AutomodTrigger.Keywords(lists.refusedWords.words, lists.refusedWords.allowed),
                alert,
                staff,
            ),
            AutomodRule(
                AutomodTexts.RULE_CONTACT,
                AutomodTrigger.Patterns(lists.contactRequests.patterns, emptyList()),
                alert,
                staff,
            ),
            AutomodRule(
                AutomodTexts.RULE_MENTIONS,
                AutomodTrigger.MentionSpam(lists.mentionSpam.limit),
                alert,
                staff,
            ),
            AutomodRule(
                AutomodTexts.RULE_LINKS,
                AutomodTrigger.Keywords(lists.links.words, lists.links.allowed),
                alert,
                withCreators,
            ),
        )
    }

    private companion object {
        const val REPORTS = "signalements"
        const val STAFF = "staff"
        const val MODERATION = "moderation"
        const val CREATOR = "createur"

        val log = LoggerFactory.getLogger(AutomodFeature::class.java)
    }
}
