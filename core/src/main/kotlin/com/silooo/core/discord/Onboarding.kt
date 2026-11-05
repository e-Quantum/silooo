package com.silooo.core.discord

/**
 * L'accueil guidé de Discord, tel que son API le décrit : des questions dont chaque réponse donne des rôles, les
 * salons que tout nouveau membre voit d'office, et l'interrupteur. JDA ne le couvre pas ; `discord/rest/` l'envoie
 * en REST (ADR 0007). Les identifiants sont ceux de Discord ; une entrée nouvelle porte un numéro provisoire, que
 * Discord remplace ([OnboardingPlanner]).
 */
data class OnboardingState(
    val prompts: List<OnboardingPrompt>,
    val defaultChannelIds: List<String>,
    val enabled: Boolean,
    val mode: Int = MODE_DEFAULT,
) {
    companion object {
        /** Le mode où seuls les salons par défaut comptent pour les contraintes de Discord. */
        const val MODE_DEFAULT = 0

        /** Un serveur sans accueil guidé. */
        val NONE = OnboardingState(emptyList(), emptyList(), enabled = false)
    }
}

/**
 * Une question. [singleSelect] : une seule réponse ; [required] : il faut répondre pour finir l'accueil ;
 * [inOnboarding] : posée à l'arrivée, et pas seulement dans « Salons et rôles ».
 */
data class OnboardingPrompt(
    val id: String,
    val title: String,
    val singleSelect: Boolean,
    val required: Boolean,
    val inOnboarding: Boolean,
    val options: List<OnboardingOption>,
)

/** Une réponse : les rôles qu'elle donne, l'emoji Unicode devant son titre. */
data class OnboardingOption(
    val id: String,
    val title: String,
    val description: String?,
    val emoji: String?,
    val roleIds: Set<String>,
    val channelIds: Set<String> = emptySet(),
)

/** Ce que le plan veut pour l'accueil guidé : l'état à envoyer, ou ce qui l'empêche ; [summary] tient en une ligne. */
class OnboardingBuild(val state: OnboardingState?, val problems: List<String>, val summary: String)

/**
 * Traduit la clé `accueil` du plan pour la phase courante : les questions telles quelles, et pour salons par défaut
 * les salons publics non vocaux de la phase. Discord exige au moins [MIN_DEFAULT_CHANNELS] salons par défaut, dont
 * [MIN_WRITABLE_CHANNELS] où tout le monde écrit : le compte se fait ici, pour que le journal dise le chiffre plutôt
 * qu'un refus de Discord.
 */
object OnboardingPlanner {
    const val MIN_DEFAULT_CHANNELS = 7
    const val MIN_WRITABLE_CHANNELS = 5

    /** Les salons non vocaux du plan public de la [phase] ; les droits effectifs sont vérifiés avant l'envoi. */
    fun publicChannels(plan: ServerPlan, phase: ServerPhase): List<PlannedChannel> =
        plan.categories
            .filter { it.phase.existsIn(phase) && it.visible == null }
            .flatMap { category -> category.salons.filter { it.phase.existsIn(phase) && it.visible == null && it.type != ChannelKind.VOCAL } }

    /** Tout le monde écrit dans un salon sans clé `ecriture`. */
    fun everyoneWrites(channel: PlannedChannel): Boolean = channel.type != ChannelKind.VOCAL && channel.ecriture == null

    fun build(plan: ServerPlan, phase: ServerPhase, ids: ServerIds): OnboardingBuild {
        val onboarding = plan.accueil
            ?: return OnboardingBuild(null, listOf("le plan n'a pas de clé accueil"), "pas d'accueil guidé dans le plan")
        val rolesWithoutId = mutableListOf<String>()
        val prompts = onboarding.questions.mapIndexed { questionIndex, question ->
            val options = question.options.mapIndexed { optionIndex, option ->
                val roleId = ids.role(option.role)
                if (roleId == null) rolesWithoutId += option.role
                OnboardingOption(
                    id = (optionIndex + 1).toString(),
                    title = option.titre,
                    description = option.description,
                    emoji = option.emoji,
                    roleIds = setOfNotNull(roleId),
                )
            }
            OnboardingPrompt(
                id = (questionIndex + 1).toString(),
                title = question.titre,
                singleSelect = question.unique,
                required = question.obligatoire,
                inOnboarding = true,
                options = options,
            )
        }
        val channels = publicChannels(plan, phase)
        val channelsWithoutId = channels.filter { ids.channel(it.id) == null }
        val writable = channels.count(::everyoneWrites)
        val summary = prompts.size.toString() + " question(s), " + prompts.sumOf { it.options.size } + " réponse(s), " +
            channels.size + " salon(s) par défaut dont " + writable + " où tout le monde écrit"

        val problems = mutableListOf<String>()
        if (rolesWithoutId.isNotEmpty()) problems += "les rôles sans identifiant Discord : " + rolesWithoutId.joinToString(", ")
        if (channelsWithoutId.isNotEmpty()) {
            problems += "les salons sans identifiant Discord : " + channelsWithoutId.joinToString(", ") { it.discordName }
        }
        if (channels.size < MIN_DEFAULT_CHANNELS) {
            problems += "Discord veut " + MIN_DEFAULT_CHANNELS + " salons par défaut, le plan en a " + channels.size
        }
        if (writable < MIN_WRITABLE_CHANNELS) {
            problems += "Discord veut " + MIN_WRITABLE_CHANNELS + " salons par défaut où tout le monde écrit, le plan en a " + writable
        }
        if (problems.isNotEmpty()) return OnboardingBuild(null, problems, summary)
        val defaultChannelIds = channels.mapNotNull { ids.channel(it.id) }
        return OnboardingBuild(OnboardingState(prompts, defaultChannelIds, enabled = true), problems, summary)
    }
}

/** Compare l'accueil guidé voulu à celui du serveur et prépare l'envoi, en gardant les identifiants que Discord connaît. */
object OnboardingSync {
    /** Ce qu'il faut envoyer pour que le serveur ait [wanted] ; `null` quand [current] le dit déjà. */
    fun changes(current: OnboardingState, wanted: OnboardingState): OnboardingState? {
        val merged = wanted.copy(
            prompts = wanted.prompts.map { prompt ->
                val known = current.prompts.firstOrNull { it.title == prompt.title }
                val options = prompt.options.map { option ->
                    val knownOption = known?.options?.firstOrNull { it.title == option.title }
                    if (knownOption == null) option else option.copy(id = knownOption.id)
                }
                if (known == null) prompt.copy(options = options) else prompt.copy(id = known.id, options = options)
            },
        )
        return if (shape(current) == shape(merged)) null else merged
    }

    /** Ce qui compte dans un état, identifiants mis à part. */
    private fun shape(state: OnboardingState): List<Any> = listOf(
        state.enabled,
        state.mode,
        state.defaultChannelIds.toSet(),
        state.prompts.map { prompt ->
            listOf(
                prompt.title,
                prompt.singleSelect,
                prompt.required,
                prompt.inOnboarding,
                prompt.options.map { listOf(it.title, it.description.orEmpty(), plainEmoji(it.emoji), it.roleIds, it.channelIds) },
            )
        },
    )

    /** Discord rend parfois un emoji sans son sélecteur de variante (U+FE0F) : la comparaison l'ignore. */
    private fun plainEmoji(emoji: String?): String = emoji.orEmpty().replace(VARIATION_SELECTOR, "")

    private val VARIATION_SELECTOR = Char(0xFE0F).toString()
}

/** L'accueil guidé dans le compte rendu : [status] pour la ligne de journal du service, [line] pour Oooscar, s'il a quelque chose à dire. */
class OnboardingOutcome(val status: String, val line: String?)

/**
 * Pose l'accueil guidé du plan, une fois les rôles et les salons en place. À blanc, lit l'état du serveur et dit ce
 * qu'il enverrait ; un refus de Discord va dans les échecs du compte rendu, comme pour un salon.
 */
class OnboardingApplier(
    private val plan: ServerPlan,
    private val phase: ServerPhase,
    private val dryRun: Boolean,
) {
    suspend fun run(server: DiscordServer, ids: ServerIds, report: PlanReport) {
        if (plan.accueil == null) return
        val build = OnboardingPlanner.build(plan, phase, ids)
        val wanted = build.state
        if (wanted == null) {
            report.onboarding = OnboardingOutcome("en attente", "L'accueil guidé attend : " + build.problems.joinToString(" ; ") + ".")
            return
        }
        report.onboarding = OnboardingOutcome("refusé", null)
        report.attempt("l'accueil guidé") {
            val access = wanted.defaultChannelIds.map { server.publicChannelAccess(it) }
            if (access.any { !it.view || !it.history } || access.count { it.view && it.send && it.forumWrite } < OnboardingPlanner.MIN_WRITABLE_CHANNELS) {
                report.onboarding = OnboardingOutcome("en attente", "L'accueil guidé attend : les droits publics effectifs des salons ne satisfont pas les exigences de Discord.")
                return@attempt
            }
            val changes = OnboardingSync.changes(server.onboarding(), wanted)
            report.onboarding = when {
                changes == null -> OnboardingOutcome("en place", "L'accueil guidé est en place : " + build.summary + ".")
                dryRun -> OnboardingOutcome("à blanc", "À blanc, je poserais l'accueil guidé : " + build.summary + ".")
                else -> {
                    server.setOnboarding(changes)
                    OnboardingOutcome("posé", "J'ai posé l'accueil guidé : " + build.summary + ".")
                }
            }
        }
    }
}
