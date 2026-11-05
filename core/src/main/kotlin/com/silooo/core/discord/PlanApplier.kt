package com.silooo.core.discord

/**
 * Applique le [plan] au serveur : crée les rôles, catégories et salons de la phase courante qui manquent, met à
 * jour la couleur d'un rôle et le sujet d'un salon qui diffèrent, et ne supprime jamais rien (ADR 0007). Les
 * entrées se reconnaissent à leur nom Discord : renommer un salon dans le plan en crée un nouveau, l'ancien reste.
 *
 * En mode [dryRun], rien ne change : le compte rendu dit ce qui manque. Une seule exception, le salon du journal
 * (et sa catégorie) : s'il manque, il est créé pour y écrire le compte rendu, sinon Kr ne le lirait pas.
 *
 * Chaque création ou modification est tentée à part : un refus de Discord (un forum sur un serveur qui n'est pas
 * en mode Communauté, un droit qui manque) va dans le compte rendu et n'arrête pas les autres.
 */
class PlanApplier(
    private val plan: ServerPlan,
    private val server: DiscordServer,
    private val phase: ServerPhase,
    private val dryRun: Boolean,
) {
    suspend fun run(): PlanReport {
        val report = PlanReport(phase, dryRun)
        val snapshot = server.snapshot()
        val roleIds = report.roleIds
        for (role in plan.roles.filter { it.phase.existsIn(phase) }) {
            val spec = RoleSpec(role.nom, role.couleur, role.separe, role.mentionnable)
            val existing = snapshot.roles.firstOrNull { it.name == role.nom }
            if (existing == null) {
                report.missingRoles += role.nom
                if (!dryRun) {
                    report.attempt("le rôle « ${role.nom} »") {
                        roleIds[role.id] = server.createRole(spec)
                        report.createdRoles += role.nom
                    }
                }
            } else {
                roleIds[role.id] = existing.id
                if (existing.color != role.couleur) {
                    report.changedRoles += role.nom
                    if (!dryRun) report.attempt("la couleur du rôle « ${role.nom} »") { server.updateRole(existing.id, spec) }
                }
            }
        }

        var journalId: String? = null
        for (category in plan.categories.filter { it.phase.existsIn(phase) }) {
            val viewers = category.visible?.let { resolve(it, roleIds) }
            var categoryId = snapshot.categories.firstOrNull { it.name == category.nom }?.id
            val holdsJournal = category.salons.any { it.id == ServerPlan.JOURNAL_ID }
            if (categoryId == null) {
                report.missingCategories += category.nom
                if (!dryRun || holdsJournal) {
                    report.attempt("la catégorie « ${category.nom} »") {
                        categoryId = server.createCategory(CategorySpec(category.nom, viewers))
                        report.createdCategories += category.nom
                    }
                }
            }
            for (channel in category.salons.filter { it.phase.existsIn(phase) }) {
                val spec = ChannelSpec(
                    name = channel.discordName,
                    kind = channel.type,
                    topic = channel.sujet,
                    writers = channel.ecriture?.let { resolve(it, roleIds) },
                    viewers = (channel.visible ?: category.visible)?.let { resolve(it, roleIds) },
                    locked = channel.verrouille,
                )
                val isJournal = channel.id == ServerPlan.JOURNAL_ID
                val existing = snapshot.channels.firstOrNull { it.name == channel.discordName && it.kind == channel.type }
                if (existing == null) {
                    report.missingChannels += channel.discordName
                    if (!dryRun || isJournal) {
                        val parent = categoryId
                        if (parent == null) {
                            report.failures += "le salon « ${channel.discordName} » : sa catégorie n'a pas pu être créée"
                        } else {
                            report.attempt("le salon « ${channel.discordName} »") {
                                val id = server.createChannel(parent, spec)
                                report.createdChannels += channel.discordName
                                report.channelIds[channel.id] = id
                                if (isJournal) journalId = id
                            }
                        }
                    }
                } else {
                    report.channelIds[channel.id] = existing.id
                    if (isJournal) journalId = existing.id
                    if (existing.topic.orEmpty() != channel.sujet.orEmpty()) {
                        report.changedChannels += channel.discordName
                        if (!dryRun) report.attempt("le sujet du salon « ${channel.discordName} »") { server.updateChannel(existing.id, spec) }
                    }
                }
            }
        }

        val journal = journalId
        if (journal == null) {
            report.failures += "pas de salon du journal : le compte rendu reste dans les journaux du service"
        } else {
            report.attempt("le compte rendu dans le journal") { server.send(journal, report.text()) }
        }
        return report
    }

    /** Les identifiants Discord des rôles cités ; un rôle inconnu de Discord (création ratée, mode à blanc) est omis. */
    private fun resolve(ids: List<String>, roleIds: Map<String, String>): Set<String> = ids.mapNotNull { roleIds[it] }.toSet()
}

/**
 * Ce que l'application du plan a trouvé et fait. [text] est le message du journal, écrit par Oooscar à la
 * première personne ; [summary] tient sur une ligne de journal du service.
 */
class PlanReport(val phase: ServerPhase, val dryRun: Boolean) {
    val missingRoles = mutableListOf<String>()
    val missingCategories = mutableListOf<String>()
    val missingChannels = mutableListOf<String>()
    val createdRoles = mutableListOf<String>()
    val createdCategories = mutableListOf<String>()
    val createdChannels = mutableListOf<String>()
    val changedRoles = mutableListOf<String>()
    val changedChannels = mutableListOf<String>()
    val failures = mutableListOf<String>()

    /** Les identifiants Discord trouvés ou créés, par identifiant du plan ; ce qui manque (à blanc, échec) n'y est pas. */
    val roleIds = mutableMapOf<String, String>()
    val channelIds = mutableMapOf<String, String>()

    val ids: ServerIds get() = ServerIds(roleIds.toMap(), channelIds.toMap())

    val nothingToDo: Boolean
        get() = missingRoles.isEmpty() && missingCategories.isEmpty() && missingChannels.isEmpty() &&
            changedRoles.isEmpty() && changedChannels.isEmpty()

    /** Tente une action de Discord ; un échec s'écrit dans [failures] avec [what], sans le détail des jetons. */
    internal suspend fun attempt(what: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            failures += "$what : ${e.message ?: e::class.simpleName}"
        }
    }

    fun summary(): String {
        val mode = if (dryRun) "à blanc" else "appliqué"
        return "plan du serveur (phase ${phase.word}, $mode) : ${missingRoles.size} rôle(s), ${missingCategories.size} catégorie(s) et " +
            "${missingChannels.size} salon(s) manquaient ; ${createdRoles.size + createdCategories.size + createdChannels.size} création(s), " +
            "${changedRoles.size + changedChannels.size} modification(s), ${failures.size} échec(s)"
    }

    fun text(): String {
        val lines = mutableListOf<String>()
        lines += if (dryRun) "Plan du serveur, phase ${phase.word}, à blanc." else "Plan du serveur, phase ${phase.word}."
        if (nothingToDo) {
            lines += "Tout est en place : rien à créer, rien à modifier."
        } else if (dryRun) {
            lines += "Il manque " + counted(missingRoles, "rôle", "rôles") + ", " + counted(missingCategories, "catégorie", "catégories") +
                " et " + counted(missingChannels, "salon", "salons") + "."
            if (changedRoles.isNotEmpty()) lines += "À modifier : la couleur de " + counted(changedRoles, "rôle", "rôles") + "."
            if (changedChannels.isNotEmpty()) lines += "À modifier : le sujet de " + counted(changedChannels, "salon", "salons") + "."
            lines += if (createdChannels.isEmpty()) "Je n'ai rien changé." else "Je n'ai rien changé, sauf le salon du journal, créé pour écrire ceci."
        } else {
            lines += "J'ai créé " + counted(createdRoles, "rôle", "rôles") + ", " + counted(createdCategories, "catégorie", "catégories") +
                " et " + counted(createdChannels, "salon", "salons") + "."
            if (changedRoles.isNotEmpty()) lines += "J'ai modifié la couleur de " + counted(changedRoles, "rôle", "rôles") + "."
            if (changedChannels.isNotEmpty()) lines += "J'ai modifié le sujet de " + counted(changedChannels, "salon", "salons") + "."
            lines += "Je ne supprime jamais rien."
        }
        if (failures.isNotEmpty()) lines += "Échecs : " + failures.joinToString(" ; ") + "."
        val text = lines.joinToString("\n")
        return if (text.length <= MAX_MESSAGE) text else text.substring(0, MAX_MESSAGE - CUT.length) + CUT
    }

    private fun counted(names: List<String>, one: String, many: String): String {
        if (names.isEmpty()) return "0 $many"
        val shown = names.take(MAX_NAMES).joinToString(", ")
        val rest = names.size - MAX_NAMES
        val list = if (rest > 0) "$shown et $rest autre(s)" else shown
        return "${names.size} ${if (names.size == 1) one else many} ($list)"
    }

    companion object {
        /** Discord refuse un message de plus de 2000 caractères. */
        const val MAX_MESSAGE = 1990
        const val MAX_NAMES = 12
        const val CUT = " (suite coupée)"
    }
}
