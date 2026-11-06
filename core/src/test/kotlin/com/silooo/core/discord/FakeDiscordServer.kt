package com.silooo.core.discord

/**
 * Un Discord en mémoire pour les tests : ce que le bot crée s'y retrouve, avec la demande telle qu'il l'a faite,
 * et rien ne s'y efface, hors les salons que `deleteChannel` supprime. [community] faux : les forums et les salons
 * d'annonces sont refusés, comme sur un serveur qui n'est pas en mode Communauté. [refused] : les noms que le serveur
 * refuse de créer, pour isoler un échec.
 */
class FakeDiscordServer(
    private val community: Boolean = true,
    private val refused: Set<String> = emptySet(),
) : DiscordServer {
    val bureauWriters = mutableMapOf<String, Set<String>>()
    val bureauStaff = mutableMapOf<String, Set<String>>()
    override suspend fun syncPublicBureauWriters(id: String, members: Set<String>, staffRoles: Set<String>) {
        bureauWriters[id] = members
        bureauStaff[id] = staffRoles
        publicAccessOverrides[id] = PublicChannelAccess(true, true, false)
    }
    val nicknames = mutableMapOf<String, String?>()
    override suspend fun nickname(userId: String, nickname: String?) { nicknames[userId] = nickname }

    val roles = mutableListOf<ExistingRole>()
    val categories = mutableListOf<ExistingCategory>()
    val channels = mutableListOf<ExistingChannel>()

    /** Les demandes reçues, par identifiant créé. */
    val roleSpecs = mutableMapOf<String, RoleSpec>()
    val categorySpecs = mutableMapOf<String, CategorySpec>()
    val channelSpecs = mutableMapOf<String, ChannelSpec>()
    val permissionSpecs = mutableMapOf<String, Triple<Set<String>?, Set<String>?, Boolean>>()
    val permissionWrites = mutableListOf<String>()
    val publicAccessOverrides = mutableMapOf<String, PublicChannelAccess>()

    /** Les messages envoyés : identifiant du salon et texte ; [posted] garde le message entier. */
    val messages = mutableListOf<Pair<String, String>>()
    val posted = mutableListOf<Posted>()
    val edits = mutableListOf<Posted>()

    /** Les messages privés envoyés ; [closedDirects] : les membres qui les refusent. */
    val directs = mutableListOf<Pair<String, BotMessage>>()
    val closedDirects = mutableSetOf<String>()

    /** Les rôles donnés par le bot, par membre. */
    val memberRoles = mutableMapOf<String, MutableSet<String>>()
    val renames = mutableListOf<Pair<String, String>>()
    val statuses = mutableListOf<String>()
    var members = 0
    var commands: List<CommandSpec> = emptyList()

    /** Combien de fois les commandes ont été posées. */
    var commandInstalls = 0

    /** Qui a réagi, par (salon, message, nom de l'emoji) : les tests le remplissent, humains seulement (le vrai serveur exclut les bots). */
    val reactors = mutableMapOf<Triple<String, String, String>, MutableSet<String>>()

    /** Le refus que le faux Discord oppose à la lecture des réactions, ou rien. */
    var refusedReactors: String? = null

    /** Les salons supprimés par le bot, dans l'ordre ; ils quittent [channels] (les demandes de [channelSpecs] restent). */
    val deleted = mutableListOf<String>()

    /** Les messages de chaque salon que `history` rend, du plus ancien au plus récent : les tests le remplissent. */
    val histories = mutableMapOf<String, MutableList<ChannelMessage>>()

    /** Les déplacements demandés au bot : membre et vocal ; [notInVoice] : les membres qui ne sont pas en vocal (le déplacement est refusé). */
    val moves = mutableListOf<Pair<String, String>>()
    val notInVoice = mutableSetOf<String>()

    /** Combien de membres sont dans chaque vocal ; un vocal absent compte zéro. */
    val voiceCounts = mutableMapOf<String, Int>()

    /** Le nom affiché de chaque membre ; un membre absent de la table n'est plus sur le serveur. */
    val names = mutableMapOf<String, String>()

    /** Les messages épinglés : (salon, message), dans l'ordre. */
    val pinned = mutableListOf<Pair<String, String>>()

    /** Les emoji que le serveur possède ; `react` répond faux pour les autres. */
    val emojis = mutableSetOf<String>()

    /** Les réactions posées par le bot : (salon, message) vers les emoji, dans l'ordre. */
    val reactions = mutableMapOf<Pair<String, String>, MutableList<String>>()

    /** Les messages supprimés : (salon, message), dans l'ordre. */
    val deletedMessages = mutableListOf<Pair<String, String>>()

    /** Les messages des membres que `message` sait lire, par (salon, message) ; un message supprimé en sort. */
    val messagesById = mutableMapOf<Pair<String, String>, ChannelMessage>()

    /** L'accueil guidé tel que le faux Discord le garde ; [onboardingWrites] : chaque envoi, dans l'ordre. */
    var rulesChannel: String? = null

    override suspend fun rulesChannelId(): String? = rulesChannel

    var onboarding: OnboardingState = OnboardingState.NONE
    val onboardingWrites = mutableListOf<OnboardingState>()

    /** Le refus que le faux Discord oppose à l'accueil guidé, ou rien. */
    var refusedOnboarding: String? = null

    /** Les noms des règles AutoMod posées sur le faux serveur ; [automodWrites] : chaque règle reçue, dans l'ordre. */
    val automodRules = mutableListOf<String>()
    val automodWrites = mutableListOf<AutomodRule>()

    /** Le refus que le faux Discord oppose à l'AutoMod (droit « Gérer le serveur » absent), ou rien. */
    var refusedAutomod: String? = null

    /** Les icônes reçues du bot, dans l'ordre ; [refusedIcon] : le refus que le faux Discord leur oppose, ou rien. */
    val icons = mutableListOf<ImageFile>()
    var refusedIcon: String? = null

    /** Le serveur a une icône : posée à la main par le test, ou par `setIcon`. */
    var iconPresent = false
    private var nextSnowflake = 100_000_000_000_000_000L

    data class Posted(val channelId: String, val messageId: String, val message: BotMessage)

    private var nextId = 1000L

    private fun newId(): String = (nextId++).toString()

    private fun refuse(name: String) {
        if (name in refused) throw IllegalStateException("Discord refuse « $name »")
    }

    private fun channel(id: String): Int {
        val index = channels.indexOfFirst { it.id == id }
        check(index >= 0) { "le salon $id n'existe pas" }
        return index
    }

    override suspend fun snapshot() = ServerSnapshot(roles.toList(), categories.toList(), channels.toList())

    override suspend fun createRole(role: RoleSpec): String {
        refuse(role.name)
        val id = newId()
        roles += ExistingRole(id, role.name, role.color)
        roleSpecs[id] = role
        return id
    }

    override suspend fun updateRole(id: String, role: RoleSpec) {
        val index = roles.indexOfFirst { it.id == id }
        check(index >= 0) { "le rôle $id n'existe pas" }
        roles[index] = ExistingRole(id, role.name, role.color)
        roleSpecs[id] = role
    }

    override suspend fun createCategory(category: CategorySpec): String {
        refuse(category.name)
        val id = newId()
        categories += ExistingCategory(id, category.name)
        categorySpecs[id] = category
        permissionSpecs[id] = Triple(category.viewers, null, false)
        return id
    }

    override suspend fun createChannel(categoryId: String, channel: ChannelSpec): String {
        check(categories.any { it.id == categoryId }) { "la catégorie $categoryId n'existe pas" }
        refuse(channel.name)
        if (!community && (channel.kind == ChannelKind.FORUM || channel.kind == ChannelKind.ANNONCES)) {
            throw IllegalStateException("le serveur n'est pas en mode Communauté")
        }
        val id = newId()
        channels += ExistingChannel(id, channel.name, channel.kind, categoryId, channel.topic)
        channelSpecs[id] = channel
        permissionSpecs[id] = Triple(channel.viewers ?: if (channel.memberViewers.isEmpty()) null else emptySet(), channel.writers, channel.locked)
        return id
    }

    override suspend fun updateChannel(id: String, channel: ChannelSpec) {
        val index = channel(id)
        channels[index] = channels[index].copy(topic = channel.topic)
        channelSpecs[id] = channel
    }

    override suspend fun reconcilePlanPermissions(id: String, viewers: Set<String>?, writers: Set<String>?, locked: Boolean, managedViewers: Set<String>, dryRun: Boolean): Boolean {
        val wanted = Triple(viewers, writers, locked)
        val changed = permissionSpecs[id] != wanted
        if (changed && !dryRun) {
            permissionSpecs[id] = wanted
            permissionWrites += id
        }
        return changed
    }

    override suspend fun publicChannelAccess(id: String): PublicChannelAccess {
        publicAccessOverrides[id]?.let { return it }
        val spec = permissionSpecs[id]
        return PublicChannelAccess(spec?.first == null, true, spec?.second == null)
    }

    override suspend fun post(channelId: String, message: BotMessage): String {
        channel(channelId)
        val id = newId()
        posted += Posted(channelId, id, message)
        messages += channelId to message.text
        return id
    }

    override suspend fun edit(channelId: String, messageId: String, message: BotMessage) {
        check(posted.any { it.channelId == channelId && it.messageId == messageId }) { "le message $messageId n'est pas dans le salon $channelId" }
        edits += Posted(channelId, messageId, message)
    }

    override suspend fun direct(userId: String, message: BotMessage): Boolean {
        if (userId in closedDirects) return false
        directs += userId to message
        return true
    }

    override suspend fun giveRole(userId: String, roleId: String) {
        check(roles.any { it.id == roleId }) { "le rôle $roleId n'existe pas" }
        memberRoles.getOrPut(userId) { mutableSetOf() } += roleId
    }

    override suspend fun takeRole(userId: String, roleId: String) {
        check(roles.any { it.id == roleId }) { "le rôle $roleId n'existe pas" }
        memberRoles[userId]?.remove(roleId)
    }

    override suspend fun rename(channelId: String, name: String) {
        val index = channel(channelId)
        channels[index] = channels[index].copy(name = name)
        renames += channelId to name
    }

    override suspend fun status(text: String) {
        statuses += text
    }

    override suspend fun memberCount(): Int = members

    override suspend fun reactors(channelId: String, messageId: String, emojiName: String): Set<String> {
        refusedReactors?.let { throw DiscordRefusal(it) }
        return reactors[Triple(channelId, messageId, emojiName)]?.toSet() ?: emptySet()
    }

    override suspend fun deleteChannel(id: String) {
        if (channels.none { it.id == id }) throw DiscordRefusal("le salon " + id + " n'existe plus")
        channels.removeAll { it.id == id }
        deleted += id
    }

    override suspend fun history(channelId: String, limit: Int): List<ChannelMessage> {
        if (channels.none { it.id == channelId }) throw DiscordRefusal("le salon " + channelId + " n'existe plus")
        return histories[channelId].orEmpty().takeLast(limit)
    }

    override suspend fun moveToVoice(userId: String, channelId: String) {
        if (userId in notInVoice) throw DiscordRefusal("le membre " + userId + " n'est pas en vocal")
        if (channels.none { it.id == channelId }) throw DiscordRefusal("le vocal " + channelId + " n'existe plus")
        moves += userId to channelId
    }

    override suspend fun voiceMembers(channelId: String): Int = voiceCounts[channelId] ?: 0

    override suspend fun memberName(userId: String): String? = names[userId]

    override suspend fun rolesOf(userId: String): Set<String> = memberRoles[userId]?.toSet() ?: emptySet()

    override suspend fun pin(channelId: String, messageId: String) {
        pinned += channelId to messageId
    }

    override suspend fun react(channelId: String, messageId: String, emojiName: String): Boolean {
        if (emojiName !in emojis) return false
        reactions.getOrPut(channelId to messageId) { mutableListOf() } += emojiName
        return true
    }

    override suspend fun deleteMessage(channelId: String, messageId: String) {
        deletedMessages += channelId to messageId
        messagesById.remove(channelId to messageId)
    }

    override suspend fun message(channelId: String, messageId: String): ChannelMessage? =
        messagesById[channelId to messageId]

    override fun messageLink(channelId: String, messageId: String): String =
        "https://discord.com/channels/test/" + channelId + "/" + messageId

    override suspend fun installCommands(commands: List<CommandSpec>) {
        this.commands = commands
        commandInstalls++
    }

    override suspend fun onboarding(): OnboardingState = onboarding

    override suspend fun setOnboarding(state: OnboardingState): OnboardingState {
        refusedOnboarding?.let { throw DiscordRefusal(it) }
        onboardingWrites += state
        onboarding = state.copy(
            prompts = state.prompts.map { prompt ->
                prompt.copy(id = snowflake(prompt.id), options = prompt.options.map { it.copy(id = snowflake(it.id)) })
            },
        )
        return onboarding
    }

    override suspend fun automodRules(): List<String> {
        refusedAutomod?.let { throw DiscordRefusal(it) }
        return automodRules.toList()
    }

    override suspend fun setAutomodRule(rule: AutomodRule) {
        refusedAutomod?.let { throw DiscordRefusal(it) }
        automodWrites += rule
        if (rule.name !in automodRules) automodRules += rule.name
    }

    override suspend fun setIcon(image: ImageFile) {
        refusedIcon?.let { throw DiscordRefusal(it) }
        icons += image
        iconPresent = true
    }

    override suspend fun hasIcon(): Boolean = iconPresent

    /** Discord remplace un numéro provisoire par un vrai identifiant (17 chiffres et plus) ; un identifiant connu reste. */
    private fun snowflake(id: String): String = if (id.length >= 17) id else (nextSnowflake++).toString()
}

/** Une connexion de test : [emit] joue un événement comme Discord le ferait, depuis le fil de l'appelant. */
class FakeConnection(override val server: FakeDiscordServer = FakeDiscordServer()) : DiscordConnection {
    @Volatile
    private var handler: ((DiscordEvent) -> Unit)? = null

    @Volatile
    var closed = false
        private set

    val listening: Boolean get() = handler != null

    override fun onEvent(handler: (DiscordEvent) -> Unit) {
        this.handler = handler
    }

    fun emit(event: DiscordEvent) {
        checkNotNull(handler) { "personne n'écoute la connexion" }(event)
    }

    override fun close() {
        closed = true
    }
}

/** Une réponse de test : garde ce que le bot a répondu. */
class FakeReply : Reply {
    val sent = mutableListOf<Pair<BotMessage, Boolean>>()
    val forms = mutableListOf<FormSpec>()
    val edits = mutableListOf<BotMessage>()
    var acknowledged = 0

    override suspend fun send(message: BotMessage, onlyToUser: Boolean) {
        sent += message to onlyToUser
    }

    override suspend fun form(form: FormSpec) {
        forms += form
    }

    override suspend fun edit(message: BotMessage) {
        edits += message
    }

    override suspend fun acknowledge() {
        acknowledged++
    }
}
