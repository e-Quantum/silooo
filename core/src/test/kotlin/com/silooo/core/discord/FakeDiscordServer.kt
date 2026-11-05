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

    /** Les messages envoyés : identifiant du salon et texte. */
    val messages = mutableListOf<Pair<String, String>>()

    private var nextId = 1000L

    private fun newId(): String = (nextId++).toString()

    private fun refuse(name: String) {
        if (name in refused) throw IllegalStateException("Discord refuse « $name »")
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
        val index = channels.indexOfFirst { it.id == id }
        check(index >= 0) { "le salon $id n'existe pas" }
        channels[index] = channels[index].copy(topic = channel.topic)
        channelSpecs[id] = channel
    }

    override suspend fun send(channelId: String, text: String) {
        check(channels.any { it.id == channelId }) { "le salon $channelId n'existe pas" }
        check(text.length <= 2000) { "Discord refuse un message de plus de 2000 caractères" }
        messages += channelId to text
    }
}
