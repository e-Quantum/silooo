package com.silooo.core.discord

/**
 * Un Discord en mémoire pour les tests : ce que le bot crée s'y retrouve, avec la demande telle qu'il l'a faite,
 * et rien ne s'y efface. [community] faux : les forums et les salons d'annonces sont refusés, comme sur un serveur
 * qui n'est pas en mode Communauté. [refused] : les noms que le serveur refuse de créer, pour isoler un échec.
 */
class FakeDiscordServer(
    private val community: Boolean = true,
    private val refused: Set<String> = emptySet(),
) : DiscordServer {
    val roles = mutableListOf<ExistingRole>()
    val categories = mutableListOf<ExistingCategory>()
    val channels = mutableListOf<ExistingChannel>()

    /** Les demandes reçues, par identifiant créé. */
    val roleSpecs = mutableMapOf<String, RoleSpec>()
    val categorySpecs = mutableMapOf<String, CategorySpec>()
    val channelSpecs = mutableMapOf<String, ChannelSpec>()

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

    /** L'accueil guidé tel que le faux Discord le garde ; [onboardingWrites] : chaque envoi, dans l'ordre. */
    var onboarding: OnboardingState = OnboardingState.NONE
    val onboardingWrites = mutableListOf<OnboardingState>()

    /** Le refus que le faux Discord oppose à l'accueil guidé, ou rien. */
    var refusedOnboarding: String? = null
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
        return id
    }

    override suspend fun updateChannel(id: String, channel: ChannelSpec) {
        val index = channel(id)
        channels[index] = channels[index].copy(topic = channel.topic)
        channelSpecs[id] = channel
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
