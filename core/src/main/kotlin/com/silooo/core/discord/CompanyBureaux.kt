package com.silooo.core.discord

/** Crée uniquement les salons dynamiques dont le bot gère les droits. Les autres salons et les locks de Kr restent
 * hors de cette opération. Un bureau est public en lecture, ses membres actuels et le staff écrivent. */
internal suspend fun ensureCompanyBureau(
    context: BotContext, companyId: Long, name: String, currentChannelId: String?, writers: Set<String>,
): String {
    if (context.phase == ServerPhase.ATTENTE) throw DiscordRefusal("Les bureaux ouvrent avec la bêta.")
    val staff = listOfNotNull(context.ids.role("staff"), context.ids.role("moderation")).toSet()
    if (staff.size != 2) throw DiscordRefusal("Les rôles du staff doivent être configurés avant de créer un bureau.")
    val snapshot = context.server.snapshot()
    val existing = currentChannelId?.takeIf { id -> snapshot.channels.any { it.id == id && it.kind == ChannelKind.TEXTE } }
    val channel = existing ?: run {
        val category = snapshot.categories.firstOrNull { it.name == "BUREAUX" }?.id
            ?: context.server.createCategory(CategorySpec("BUREAUX", null))
        val slug = name.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').take(65).ifBlank { "entreprise" }
        context.server.createChannel(category, ChannelSpec("bureau-$slug-$companyId", ChannelKind.TEXTE,
            "Bureau d'entreprise. Visible par tous ; les membres de l'entreprise et le staff peuvent écrire.",
            staff, null, false))
    }
    context.server.syncPublicBureauWriters(channel, writers, staff)
    return channel
}
