package com.silooo.core.discord

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * L'AutoMod : les listes du dépôt tiennent dans les limites de Discord, les quatre règles sont posées à chaque
 * démarrage (et remplacées, pas doublées), le salon d'alerte et les rôles exemptés viennent du plan, un refus de Discord
 * va au journal sans arrêter le bot.
 */
class AutomodTest {
    private val lists = AutomodRules.load()

    /** Un serveur, un temps et un plan à part pour chaque test. */
    private inner class Setup(withReports: Boolean = true, server: ((FakeDiscordServer) -> DiscordServer)? = null) {
        val fake = FakeDiscordServer()
        val timer = FakeTimer()
        val staff: String = runBlocking { fake.createRole(RoleSpec("staff", null, false, false)) }
        val moderation: String = runBlocking { fake.createRole(RoleSpec("moderation", null, false, false)) }
        val creator: String = runBlocking { fake.createRole(RoleSpec("createur", null, false, false)) }
        val category: String = runBlocking { fake.createCategory(CategorySpec("Staff", null)) }
        val channels: Map<String, String> = runBlocking {
            buildMap {
                if (withReports) put("signalements", fake.createChannel(category, ChannelSpec("signalements", ChannelKind.TEXTE, null, emptySet(), null, false)))
                put("journal-du-bot", fake.createChannel(category, ChannelSpec("journal-du-bot", ChannelKind.TEXTE, null, emptySet(), null, false)))
            }
        }
        val ids = ServerIds(mapOf("staff" to staff, "moderation" to moderation, "createur" to creator), channels)
        val context = BotContext(server?.invoke(fake) ?: fake, ids, timer, ServerPhase.ATTENTE)
        val feature = AutomodFeature(context, BotJournal(context), lists)

        val journal: List<String> get() = fake.posted.filter { it.channelId == channels["journal-du-bot"] }.map { it.message.text }

        fun start() = runBlocking { feature.start() }

        fun rule(name: String): AutomodRule = fake.automodWrites.last { it.name == name }
    }

    @Test
    @DisplayName("Quatre règles sont posées au démarrage, sous leurs noms")
    fun fourRulesArePosted() {
        val setup = Setup()
        setup.start()

        assertEquals(
            listOf(AutomodTexts.RULE_WORDS, AutomodTexts.RULE_CONTACT, AutomodTexts.RULE_MENTIONS, AutomodTexts.RULE_LINKS),
            setup.fake.automodRules,
        )
        assertEquals(listOf("AutoMod : 4 règles posées."), setup.journal)
        assertEquals(listOf("Oooscar : mots refusés", "Oooscar : demandes de contact", "Oooscar : spam de mentions", "Oooscar : liens"), setup.fake.automodWrites.map { it.name })
    }

    @Test
    @DisplayName("Les déclencheurs sortent des listes du fichier")
    fun triggersComeFromTheFile() {
        val setup = Setup()
        setup.start()

        assertEquals(AutomodTrigger.Keywords(lists.motsRefuses, lists.refusedWords.allowed), setup.rule(AutomodTexts.RULE_WORDS).trigger)
        assertEquals(AutomodTrigger.Patterns(lists.demandesDeContact, emptyList()), setup.rule(AutomodTexts.RULE_CONTACT).trigger)
        assertEquals(AutomodTrigger.MentionSpam(5), setup.rule(AutomodTexts.RULE_MENTIONS).trigger)
        assertEquals(
            AutomodTrigger.Keywords(listOf("http*", "www.*"), listOf("*silooo.com*", "*discord.com/channels/*", "*discord.gg/*")),
            setup.rule(AutomodTexts.RULE_LINKS).trigger,
        )
    }

    @Test
    @DisplayName("Chaque règle alerte dans #signalements")
    fun everyRuleAlertsInReports() {
        val setup = Setup()
        setup.start()

        val reports = setup.channels.getValue("signalements")
        assertEquals(listOf(reports), setup.fake.automodWrites.map { it.alertChannelId }.distinct())
    }

    @Test
    @DisplayName("Le staff et la modération sont exemptés ; les créateurs le sont des seuls liens")
    fun exemptRoles() {
        val setup = Setup()
        setup.start()

        val staff = setOf(setup.staff, setup.moderation)
        assertEquals(staff, setup.rule(AutomodTexts.RULE_WORDS).exemptRoleIds)
        assertEquals(staff, setup.rule(AutomodTexts.RULE_CONTACT).exemptRoleIds)
        assertEquals(staff, setup.rule(AutomodTexts.RULE_MENTIONS).exemptRoleIds)
        assertEquals(staff + setup.creator, setup.rule(AutomodTexts.RULE_LINKS).exemptRoleIds)
    }

    @Test
    @DisplayName("Un deuxième démarrage remplace les règles du même nom, il n'en ajoute pas")
    fun restartReplacesTheRules() {
        val setup = Setup()
        setup.start()
        setup.start()

        assertEquals(4, setup.fake.automodRules.size)
        assertEquals(8, setup.fake.automodWrites.size)
        assertEquals(listOf("AutoMod : 4 règles posées.", "AutoMod : 4 règles posées."), setup.journal)
    }

    @Test
    @DisplayName("Sans #signalements, les règles bloquent sans alerter")
    fun withoutReportsChannel() {
        val setup = Setup(withReports = false)
        setup.start()

        assertEquals(4, setup.fake.automodRules.size)
        assertEquals(listOf<String?>(null), setup.fake.automodWrites.map { it.alertChannelId }.distinct())
    }

    @Test
    @DisplayName("Sans le droit « Gérer le serveur », une ligne au journal et le bot continue")
    fun refusalGoesToTheJournal() {
        val setup = Setup()
        setup.fake.refusedAutomod = "le bot n'a pas le droit de poser la règle AutoMod : il lui faut Gérer le serveur"
        setup.start()

        assertEquals(emptyList<String>(), setup.fake.automodRules)
        assertEquals(1, setup.journal.size)
        assertTrue(setup.journal[0].startsWith("AutoMod : 0 sur 4 règles posées."), setup.journal[0])
        assertTrue(setup.journal[0].contains("Gérer le serveur"), setup.journal[0])
    }

    @Test
    @DisplayName("Une règle refusée n'empêche pas les autres, et le journal dit laquelle")
    fun onlyTheRefusedRuleIsMissing() {
        val setup = Setup(server = { fake ->
            object : DiscordServer by fake {
                override suspend fun setAutomodRule(rule: AutomodRule) {
                    if (rule.name == AutomodTexts.RULE_CONTACT) throw DiscordRefusal("Discord refuse la règle des demandes de contact")
                    fake.setAutomodRule(rule)
                }
            }
        })
        setup.start()

        assertEquals(listOf(AutomodTexts.RULE_WORDS, AutomodTexts.RULE_MENTIONS, AutomodTexts.RULE_LINKS), setup.fake.automodRules)
        assertEquals(listOf("AutoMod : 3 sur 4 règles posées.\n- Discord refuse la règle des demandes de contact"), setup.journal)
    }

    @Test
    @DisplayName("Une erreur imprévue sur une règle ne casse pas le démarrage")
    fun unexpectedErrorDoesNotBreakStart() {
        val setup = Setup(server = { fake ->
            object : DiscordServer by fake {
                override suspend fun setAutomodRule(rule: AutomodRule) {
                    if (rule.name == AutomodTexts.RULE_LINKS) throw IllegalStateException("panne")
                    fake.setAutomodRule(rule)
                }
            }
        })
        setup.start()

        assertEquals(3, setup.fake.automodRules.size)
        assertEquals(1, setup.journal.size)
        assertTrue(setup.journal[0].startsWith("AutoMod : 3 sur 4 règles posées."), setup.journal[0])
        assertTrue(setup.journal[0].contains(AutomodTexts.RULE_LINKS), setup.journal[0])
    }

    @Test
    @DisplayName("La fonction n'écoute aucun événement et ne programme rien")
    fun noEventsNoTimers() {
        val setup = Setup()
        setup.start()
        runBlocking {
            setup.feature.handle(DiscordEvent.MessagePosted("1", "2", DiscordUser("3", "Ana"), "ajoute-moi", setup.timer.now()))
        }

        assertEquals(0, setup.timer.pending)
        assertEquals(emptyList<String>(), setup.fake.messages.filter { it.first != setup.channels["journal-du-bot"] }.map { it.second })
    }

    @Test
    @DisplayName("Les listes du dépôt tiennent dans les limites de Discord")
    fun listsFitDiscordLimits() {
        val words = lists.motsRefuses
        assertTrue(words.isNotEmpty())
        assertTrue(words.size <= 1000, "mille mots au plus")
        assertEquals(words.distinct(), words, "pas de mot en double")
        for (word in words) {
            assertTrue(word.length in 1..60, word)
            assertEquals(word.lowercase(), word, "des mots en minuscules : Discord ne regarde pas la casse")
            assertTrue(Regex("\\*?[^*]+\\*?").matches(word), "un joker seulement au début ou à la fin : " + word)
        }
        val patterns = lists.demandesDeContact
        assertTrue(patterns.size in 1..10, "dix motifs au plus")
        for (pattern in patterns) {
            assertTrue(pattern.length <= 260, pattern)
            Regex(pattern)
        }
        assertTrue(lists.mentionSpam.limit in 1..50)
        assertTrue(lists.links.allowed.size <= 100)
        for (word in lists.links.allowed + lists.links.words) {
            assertTrue(Regex("\\*?[^*]+\\*?").matches(word), "un joker seulement au début ou à la fin : " + word)
        }
    }

    @Test
    @DisplayName("Les motifs de contact attrapent les demandes et laissent passer le reste")
    fun contactPatternsMatch() {
        val regexes = lists.demandesDeContact.map { Regex(it) }
        fun caught(text: String) = regexes.any { it.containsMatchIn(text) }

        for (text in listOf(
            "ajoute-moi", "Ajoute moi stp", "ajoutemoi", "viens en privé", "on se parle en MP", "mon snap est cool", "voici mon insta",
            "mon tiktok", "snap : pseudo", "appelle le 06 12 34 56 78", "0612345678", "06.12.34.56.78", "+33 6 12 34 56 78", "mon numéro 1234567890",
            "j'habite 12 rue des Lilas", "12 bis avenue Foch",
        )) {
            assertTrue(caught(text), "devrait être attrapé : " + text)
        }
        for (text in listOf(
            "bonjour tout le monde", "j'ai 12 ans", "ma première usine est au niveau 10", "on joue à 20 h 30", "il y a 3 places libres",
            "viens dans le vocal", "mon silo est plein", "rendez-vous sur le serveur",
        )) {
            assertFalse(caught(text), "devrait passer : " + text)
        }
    }

    @Test
    @DisplayName("Les mots refusés ont leur liste, les autorisés sont vides pour l'instant")
    fun wordsExposed() {
        assertEquals(lists.refusedWords.words, lists.motsRefuses)
        assertEquals(lists.contactRequests.patterns, lists.demandesDeContact)
        assertEquals(emptyList<String>(), lists.refusedWords.allowed)
    }

    @Test
    @DisplayName("Un fichier d'AutoMod abîmé est refusé avec le nom du fichier")
    fun brokenFileIsRefused() {
        val error = assertThrows(IllegalStateException::class.java) { AutomodRules.parse("{\"motsRefuses\": {}}") }
        assertTrue(error.message.orEmpty().startsWith(AutomodRules.RESOURCE), error.message)
        assertNull(runCatching { AutomodRules.parse(javaClass.classLoader.getResourceAsStream(AutomodRules.RESOURCE)!!.readBytes().decodeToString()) }.exceptionOrNull())
    }
}
