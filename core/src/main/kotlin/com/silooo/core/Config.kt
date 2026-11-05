package com.silooo.core

import com.silooo.core.definitions.DefinitionsLoader
import com.silooo.core.discord.BotConfig
import com.silooo.core.discord.BotMode
import com.silooo.core.discord.BotToken
import com.silooo.core.discord.ServerPhase
import com.silooo.core.registration.DiscordConfig
import com.silooo.core.registration.SiteAdmission
import com.silooo.core.site.SiteMode
import com.silooo.core.site.SiteSettings
import com.silooo.core.site.VoteSite
import com.silooo.core.vote.VotifierSettings
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration

/** Une variable d'environnement absente ou invalide. Le message les liste toutes d'un coup. */
class ConfigException(message: String) : Exception(message)

/** Connexion à la base `silooo`. Le mot de passe ne s'affiche jamais. */
data class DatabaseConfig(val url: String, val user: String, val password: String) {
    override fun toString() = "DatabaseConfig(url=$url, user=$user, password=***)"
}

/**
 * Tout ce qu'il faut pour démarrer le service. La clé ne s'affiche jamais.
 * [definitionsDir] est le dossier qui contient economie.json, usines.json, boutique.json, monde.json et missions.json.
 *
 * [apiRequestRetention] : combien de temps `api_requests` garde une réponse pour la rejouer ; au-delà,
 * la ligne est purgée (voir [Config.API_REQUESTS_RETENTION_HOURS]).
 *
 * [deliveryPoll] : l'intervalle du relevé des commandes de Gemmes du site (`shop_deliveries`), voir [Config.DELIVERY_POLL_SECONDS].
 */
data class ServiceConfig(
    val port: Int,
    val apiKey: String,
    val database: DatabaseConfig,
    val definitionsDir: Path,
    val apiRequestRetention: Duration = Duration.ofHours(Config.DEFAULT_API_REQUESTS_RETENTION_HOURS),
    val deliveryPoll: Duration = Duration.ofSeconds(Config.DEFAULT_DELIVERY_POLL_SECONDS),
    /** Le port du site public (`SILOOO_SITE_PORT`), distinct de [port]. */
    val sitePort: Int = Config.DEFAULT_SITE_PORT,
    /** Le mode du site public (`SILOOO_SITE_MODE`). */
    val siteMode: SiteMode = SiteMode.ATTENTE,
    /** L'adresse publique du site (`SILOOO_SITE_URL`). */
    val siteUrl: String = Config.DEFAULT_SITE_URL,
    /** Le lien Discord du site (`SILOOO_SITE_URL_DISCORD`) ; `null` tant qu'il n'est pas posé. */
    val discordUrl: String? = null,
    /** L'adresse et le port du jeu, la boutique et les sites de vote que les pages affichent (`SILOOO_SITE_ADRESSE_JEU` et suivantes). */
    val siteSettings: SiteSettings = SiteSettings(),
    /** La règle d'admission des inscrits (`SILOOO_SITE_ADMISSION`). */
    val siteAdmission: SiteAdmission = SiteAdmission.DISCORD,
    /** L'application Discord de « Relier Discord » ; `null` tant que Kr ne l'a pas créée. */
    val discord: DiscordConfig? = null,
    /** `SILOOO_SITE_TRUSTED_PROXY` : l'adresse du visiteur se lit dans `X-Forwarded-For`. */
    val siteTrustedProxy: Boolean = false,
    /** La réception des votes de Hubyy (`SILOOO_VOTIFIER_TOKEN` et `SILOOO_VOTIFIER_PORT`) ; `null` tant que le jeton n'est pas posé : rien n'écoute. */
    val votifier: VotifierSettings? = null,
    /** `SILOOO_PAYNOW_WEBHOOK_SECRET` : la clé de la signature du webhook de PayNow ; `null` : le webhook répond 503. */
    val payNowWebhookSecret: String? = null,
    /** Le bot Discord Oooscar (`SILOOO_DISCORD_ACTIF` et suivantes, ADR 0007) ; `null` tant que le bot n'est pas demandé. */
    val bot: BotConfig? = null,
) {
    override fun toString() =
        "ServiceConfig(port=$port, apiKey=***, database=$database, definitionsDir=$definitionsDir, apiRequestRetention=$apiRequestRetention, " +
            "deliveryPoll=$deliveryPoll, sitePort=$sitePort, siteMode=${siteMode.word}, siteUrl=$siteUrl, discordUrl=$discordUrl, siteSettings=$siteSettings, " +
            "siteAdmission=${siteAdmission.word}, discord=$discord, siteTrustedProxy=$siteTrustedProxy, payNowWebhookSecret=${if (payNowWebhookSecret == null) "absente" else "***"}, votifier=$votifier, " +
            "bot=${bot ?: "non"})"
}

/**
 * La configuration vient des variables d'environnement, et d'elles seules : pas de fichier lu par
 * le code, rien d'écrit dans le dépôt (règle 9 du socle). Les fonctions prennent la table des
 * variables en paramètre, pour qu'un test n'ait pas à toucher à l'environnement du processus.
 */
object Config {
    const val PORT = "SILOOO_PORT"
    const val DB_URL = "SILOOO_DB_URL"
    const val DB_USER = "SILOOO_DB_USER"
    const val DB_PASSWORD = "SILOOO_DB_PASSWORD"
    const val API_KEY = "SILOOO_API_KEY"
    const val DEFINITIONS_DIR = "SILOOO_DEFINITIONS_DIR"
    const val SITE_PORT = "SILOOO_SITE_PORT"
    const val SITE_MODE = "SILOOO_SITE_MODE"
    const val SITE_URL = "SILOOO_SITE_URL"
    const val SITE_URL_DISCORD = "SILOOO_SITE_URL_DISCORD"
    const val SITE_GAME_ADDRESS = "SILOOO_SITE_ADRESSE_JEU"
    const val SITE_GAME_PORT = "SILOOO_SITE_PORT_JEU"
    const val SITE_SHOP_URL = "SILOOO_SITE_URL_BOUTIQUE"
    const val SITE_VOTE_SITES = "SILOOO_SITE_SITES_DE_VOTE"
    const val SITE_URL_SILENEMC = "SILOOO_SITE_URL_SILENEMC"
    const val SITE_URL_HUBYY = "SILOOO_SITE_URL_HUBYY"
    const val SITE_ADMISSION = "SILOOO_SITE_ADMISSION"
    const val DISCORD_CLIENT_ID = "SILOOO_DISCORD_CLIENT_ID"
    const val DISCORD_CLIENT_SECRET = "SILOOO_DISCORD_CLIENT_SECRET"
    const val SITE_TRUSTED_PROXY = "SILOOO_SITE_TRUSTED_PROXY"
    const val PAYNOW_WEBHOOK_SECRET = "SILOOO_PAYNOW_WEBHOOK_SECRET"

    /** Le jeton que Hubyy signe ses votes avec (Votifier 2). Un secret : posé seulement dans l'environnement du conteneur, jamais dans le dépôt. Absent : la réception des votes est éteinte. */
    const val VOTIFIER_TOKEN = "SILOOO_VOTIFIER_TOKEN"

    /** Le port TCP où le service reçoit les votes. */
    const val VOTIFIER_PORT = "SILOOO_VOTIFIER_PORT"

    /** 8192 quand `SILOOO_VOTIFIER_PORT` n'est pas posé : le port habituel de Votifier. Le compose publie le même (`${SILOOO_VOTIFIER_PORT:-8192}`). */
    const val DEFAULT_VOTIFIER_PORT = 8192
    /** Le bot Discord Oooscar (ADR 0007) : `non` par défaut, `a-blanc` pour lire l'écart avec le plan, `oui` pour l'appliquer. */
    const val DISCORD_ACTIF = "SILOOO_DISCORD_ACTIF"

    /** Le jeton du bot, obligatoire dès que le bot est demandé ; il ne s'affiche jamais. */
    const val DISCORD_TOKEN = "SILOOO_DISCORD_TOKEN"

    /** L'identifiant numérique du serveur Discord que le bot règle : le serveur de Silooo, un seul (pas de serveur de test). */
    const val DISCORD_SERVEUR = "SILOOO_DISCORD_SERVEUR"

    /** La phase du serveur : `attente` par défaut, `beta`, `ouvert`. Chaque entrée du plan porte la phase où elle apparaît. */
    const val DISCORD_PHASE = "SILOOO_DISCORD_PHASE"

    /** Le QG est ouvert au public : `non` par défaut (messages privés au staff seul, mentions de rôle sur les rôles du staff seuls), `oui` à l'ouverture. */
    const val DISCORD_PUBLIC = "SILOOO_DISCORD_PUBLIC"

    /**
     * Combien d'heures `api_requests` garde la réponse d'une requête pour la rejouer. Un réglage technique, pas
     * un chiffre du jeu : un rejeu arrive dans les secondes ou les minutes qui suivent une coupure, jamais
     * des jours plus tard.
     */
    const val API_REQUESTS_RETENTION_HOURS = "SILOOO_API_REQUESTS_RETENTION_HOURS"

    /**
     * 24 heures quand la variable n'est pas posée : très au-delà de n'importe quel nouvel essai de l'add-on,
     * et assez court pour que la table ne grossisse pas dans la base partagée avec SileneMC (une ligne par
     * action, avec l'état de l'entreprise en réponse).
     */
    const val DEFAULT_API_REQUESTS_RETENTION_HOURS = 24L

    /** Garde-fou : une faute de frappe ne doit pas garder les réponses un siècle (une année au plus). */
    const val MAX_API_REQUESTS_RETENTION_HOURS = 8760L

    /**
     * Toutes les combien de secondes le service relève `shop_deliveries` : les commandes de Gemmes que le site a écrites et qu'il
     * verse aux joueurs qu'il connaît. Un réglage technique, pas un chiffre du jeu : plus court, le joueur voit ses Gemmes plus
     * vite ; plus long, la base est moins sollicitée. Un joueur qui entre en jeu reçoit les siennes à `join`, sans attendre ce passage.
     */
    const val DELIVERY_POLL_SECONDS = "SILOOO_DELIVERY_POLL_SECONDS"

    /** 30 secondes quand la variable n'est pas posée : le signal de vie du serveur bat toutes les 10 secondes, et un paiement se voit vite. */
    const val DEFAULT_DELIVERY_POLL_SECONDS = 30L

    /** Garde-fou : une faute de frappe ne doit pas laisser les paiements une journée sans livraison (une heure au plus). */
    const val MAX_DELIVERY_POLL_SECONDS = 3600L

    /** Port d'écoute quand `SILOOO_PORT` n'est pas posé : celui de l'OpenAPI (`http://silooo-core:8080`). */
    const val DEFAULT_PORT = 8080

    /** Port du site public quand `SILOOO_SITE_PORT` n'est pas posé : le Caddy de SileneMC y joint `silooo-core`. */
    const val DEFAULT_SITE_PORT = 8081

    /** Adresse publique du site quand `SILOOO_SITE_URL` n'est pas posée. */
    const val DEFAULT_SITE_URL = "https://silooo.com"

    /** L'adresse du serveur de jeu que le site affiche quand `SILOOO_SITE_ADRESSE_JEU` n'est pas posée. */
    const val DEFAULT_GAME_ADDRESS_SHOWN = "play.silooo.com"

    /**
     * Le port du serveur de jeu que le site affiche quand `SILOOO_SITE_PORT_JEU` n'est pas posée. C'est le défaut de
     * `BDS_PORT` dans `deploy/compose.yml` (`${BDS_PORT:-19142}`, ADR 0004 : le 19132 est pris par le Geyser de SileneMC).
     * Un test compare les deux : si Kr change le port du jeu dans le compose, il le dit.
     */
    const val DEFAULT_GAME_PORT_SHOWN = 19142

    /**
     * Seulement la base : c'est tout ce que le mode `migrate` demande. Le service `silooo-migrate`
     * du compose n'a donc pas besoin de la clé du serveur ni des définitions.
     */
    fun database(env: Map<String, String>): DatabaseConfig {
        val reader = Reader(env)
        val database = reader.database()
        reader.finish()
        return checkNotNull(database)
    }

    /**
     * Démarrage normal : le service refuse de partir sans la base, la clé ni les définitions.
     * [workingDir] ne sert qu'à retrouver `definitions/` en développement, quand `SILOOO_DEFINITIONS_DIR`
     * n'est pas posée : un test le donne explicitement, le service prend le dossier courant.
     */
    fun service(env: Map<String, String>, workingDir: Path = Path.of("").toAbsolutePath()): ServiceConfig {
        val reader = Reader(env)
        val database = reader.database()
        val apiKey = reader.required(API_KEY)
        val port = reader.port()
        val definitionsDir = reader.definitionsDir(workingDir)
        val retention = reader.apiRequestRetention()
        val deliveryPoll = reader.deliveryPoll()
        val sitePort = reader.sitePort(port)
        val siteMode = reader.siteMode()
        val siteUrl = reader.siteUrl(SITE_URL, DEFAULT_SITE_URL)
        val discordUrl = reader.siteUrl(SITE_URL_DISCORD, null)
        val siteSettings = reader.siteSettings()
        val siteAdmission = reader.siteAdmission()
        val discord = reader.discord()
        val trustedProxy = reader.trustedProxy()
        val votifier = reader.votifier(port, sitePort)
        val payNowSecret = env[PAYNOW_WEBHOOK_SECRET]?.trim()?.takeIf { it.isNotEmpty() }
        val bot = reader.bot()
        reader.finish()
        return ServiceConfig(
            checkNotNull(port), checkNotNull(apiKey), checkNotNull(database), checkNotNull(definitionsDir), checkNotNull(retention),
            deliveryPoll = checkNotNull(deliveryPoll),
            sitePort = checkNotNull(sitePort), siteMode = checkNotNull(siteMode), siteUrl = checkNotNull(siteUrl), discordUrl = discordUrl,
            siteSettings = checkNotNull(siteSettings),
            siteAdmission = checkNotNull(siteAdmission), discord = discord, siteTrustedProxy = checkNotNull(trustedProxy),
            votifier = votifier,
            payNowWebhookSecret = payNowSecret,
            bot = bot,
        )
    }

    /**
     * En développement, `definitions/` du dépôt se trouve depuis le dossier courant : `../definitions`
     * quand on lance depuis `core/`, `definitions` quand on lance depuis la racine. Rien d'autre n'est
     * deviné ; dans l'image Docker la variable est posée.
     */
    internal fun developmentDefinitionsDir(workingDir: Path): Path? =
        listOf(workingDir.resolve("../definitions").normalize(), workingDir.resolve("definitions").normalize())
            .firstOrNull { Files.isRegularFile(it.resolve(DefinitionsLoader.ECONOMY_FILE)) }

    /** Lit les variables en gardant chaque problème, pour tout signaler en une fois. */
    private class Reader(private val env: Map<String, String>) {
        private val problems = mutableListOf<String>()

        fun required(name: String): String? {
            val value = env[name]
            if (value.isNullOrBlank()) {
                problems += "$name est absente ou vide"
                return null
            }
            return value
        }

        fun database(): DatabaseConfig? {
            val url = required(DB_URL)
            val user = required(DB_USER)
            val password = required(DB_PASSWORD)
            if (url != null && !url.startsWith("jdbc:")) {
                problems += "$DB_URL doit être une URL JDBC (jdbc:mariadb://hôte:3306/silooo)"
                return null
            }
            if (url == null || user == null || password == null) return null
            return DatabaseConfig(url, user, password)
        }

        fun definitionsDir(workingDir: Path): Path? {
            val raw = env[DEFINITIONS_DIR]
            if (raw.isNullOrBlank()) {
                val found = developmentDefinitionsDir(workingDir)
                if (found == null) {
                    problems += "$DEFINITIONS_DIR est absente et le dossier definitions/ du dépôt est introuvable depuis $workingDir"
                }
                return found
            }
            val directory = try {
                Path.of(raw.trim())
            } catch (e: InvalidPathException) {
                problems += "$DEFINITIONS_DIR n'est pas un chemin valable"
                return null
            }
            if (!Files.isDirectory(directory)) {
                problems += "$DEFINITIONS_DIR ne désigne pas un dossier : $directory"
                return null
            }
            return directory
        }

        fun port(): Int? {
            val raw = env[PORT]
            if (raw.isNullOrBlank()) return DEFAULT_PORT
            val port = raw.trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                problems += "$PORT doit être un numéro de port entre 1 et 65535"
                return null
            }
            return port
        }

        fun apiRequestRetention(): Duration? {
            val raw = env[API_REQUESTS_RETENTION_HOURS]
            if (raw.isNullOrBlank()) return Duration.ofHours(DEFAULT_API_REQUESTS_RETENTION_HOURS)
            val hours = raw.trim().toLongOrNull()
            if (hours == null || hours !in 1..MAX_API_REQUESTS_RETENTION_HOURS) {
                problems += "$API_REQUESTS_RETENTION_HOURS doit être un nombre entier d'heures entre 1 et $MAX_API_REQUESTS_RETENTION_HOURS"
                return null
            }
            return Duration.ofHours(hours)
        }

        fun deliveryPoll(): Duration? {
            val raw = env[DELIVERY_POLL_SECONDS]
            if (raw.isNullOrBlank()) return Duration.ofSeconds(DEFAULT_DELIVERY_POLL_SECONDS)
            val seconds = raw.trim().toLongOrNull()
            if (seconds == null || seconds !in 1..MAX_DELIVERY_POLL_SECONDS) {
                problems += "$DELIVERY_POLL_SECONDS doit être un nombre entier de secondes entre 1 et $MAX_DELIVERY_POLL_SECONDS"
                return null
            }
            return Duration.ofSeconds(seconds)
        }

        /**
         * La réception des votes : `null` sans jeton (éteinte, ce n'est pas une erreur). Avec un jeton, le port doit être valide et
         * différent de ceux de l'API et du site : trois serveurs ne partagent pas un port.
         */
        fun votifier(apiPort: Int?, sitePort: Int?): VotifierSettings? {
            val token = env[VOTIFIER_TOKEN]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val raw = env[VOTIFIER_PORT]
            val port = if (raw.isNullOrBlank()) DEFAULT_VOTIFIER_PORT else raw.trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                problems += "$VOTIFIER_PORT doit être un numéro de port entre 1 et 65535"
                return null
            }
            if (port == apiPort || port == sitePort) {
                problems += "$VOTIFIER_PORT ($port) doit être différent de $PORT et de $SITE_PORT : trois serveurs, trois ports"
                return null
            }
            return VotifierSettings(port, token)
        }

        /** Le port du site : un port valide, et jamais celui de l'API (deux serveurs ne partagent pas un port). */
        fun sitePort(apiPort: Int?): Int? {
            val raw = env[SITE_PORT]
            val port = if (raw.isNullOrBlank()) DEFAULT_SITE_PORT else raw.trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                problems += "$SITE_PORT doit être un numéro de port entre 1 et 65535"
                return null
            }
            if (port == apiPort) {
                problems += "$SITE_PORT ($port) doit être différent de $PORT : le site et l'API écoutent sur deux ports"
                return null
            }
            return port
        }

        /** Le mode du site : `attente` quand la variable n'est pas posée, jamais `ouvert` par défaut. */
        fun siteMode(): SiteMode? {
            val raw = env[SITE_MODE]
            if (raw.isNullOrBlank()) return SiteMode.ATTENTE
            val mode = SiteMode.parse(raw)
            if (mode == null) {
                problems += "$SITE_MODE doit valoir ${SiteMode.entries.joinToString(" ou ") { it.word }}"
            }
            return mode
        }

        /** Une adresse http(s) facultative : [default] quand la variable n'est pas posée (peut être `null`). */
        fun siteUrl(name: String, default: String?): String? {
            val raw = env[name]
            if (raw.isNullOrBlank()) return default
            val url = raw.trim()
            if (!url.startsWith("http")) {
                problems += "$name doit commencer par http"
                return default
            }
            return url.trimEnd('/')
        }

        /** Ce que les pages du site affichent du jeu : adresse, port, boutique, sites de vote. Chaque défaut est celui du jeu, jamais une erreur. */
        fun siteSettings(): SiteSettings? {
            val before = problems.size
            val address = env[SITE_GAME_ADDRESS]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GAME_ADDRESS_SHOWN
            if (address.any { it.isWhitespace() || it == '/' || it == ':' }) {
                problems += "$SITE_GAME_ADDRESS doit être une adresse sans espace, sans « / » ni port (le port a sa variable, $SITE_GAME_PORT)"
            }
            val rawPort = env[SITE_GAME_PORT]
            val port = if (rawPort.isNullOrBlank()) DEFAULT_GAME_PORT_SHOWN else rawPort.trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                problems += "$SITE_GAME_PORT doit être un numéro de port entre 1 et 65535"
            }
            val shopUrl = siteUrl(SITE_SHOP_URL, null)
            val voteSites = voteSites()
            // Les sites frères du pied de page : des adresses facultatives, lues comme la boutique.
            val sileneMcUrl = siteUrl(SITE_URL_SILENEMC, null)
            val hubyyUrl = siteUrl(SITE_URL_HUBYY, null)
            if (problems.size > before) return null
            return SiteSettings(address, checkNotNull(port), shopUrl, voteSites, sileneMcUrl, hubyyUrl)
        }

        /** `nom|url;nom|url` : une entrée sans nom, sans url ou sans « | » refuse tout le démarrage. */
        private fun voteSites(): List<VoteSite> {
            val raw = env[SITE_VOTE_SITES]
            if (raw.isNullOrBlank()) return emptyList()
            val sites = mutableListOf<VoteSite>()
            for (entry in raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
                val bar = entry.indexOf('|')
                val name = if (bar > 0) entry.substring(0, bar).trim() else ""
                val url = if (bar > 0) entry.substring(bar + 1).trim() else ""
                if (name.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    problems += "$SITE_VOTE_SITES : « $entry » doit s'écrire nom|url, avec une adresse qui commence par http (plusieurs sites séparés par des points-virgules)"
                } else {
                    sites += VoteSite(name, url)
                }
            }
            return sites
        }

        /** `discord` quand la variable n'est pas posée ; toute valeur inconnue arrête le démarrage. */
        fun siteAdmission(): SiteAdmission? {
            val raw = env[SITE_ADMISSION]
            if (raw.isNullOrBlank()) return SiteAdmission.DISCORD
            val rule = SiteAdmission.parse(raw)
            if (rule == null) {
                problems += "$SITE_ADMISSION doit valoir ${SiteAdmission.entries.joinToString(" ou ") { it.word }}"
            }
            return rule
        }

        /** Les deux variables de l'application Discord, ou aucune : une seule des deux est une erreur. */
        fun discord(): DiscordConfig? {
            val id = env[DISCORD_CLIENT_ID]?.trim()?.takeIf { it.isNotEmpty() }
            val secret = env[DISCORD_CLIENT_SECRET]?.trim()?.takeIf { it.isNotEmpty() }
            if (id == null && secret == null) return null
            if (id == null || secret == null) {
                problems += "$DISCORD_CLIENT_ID et $DISCORD_CLIENT_SECRET vont ensemble : pose les deux, ou aucune"
                return null
            }
            return DiscordConfig(id, secret)
        }

        /** `false` quand la variable n'est pas posée ; seuls `true` et `false` sont acceptés. */
        fun trustedProxy(): Boolean? {
            val raw = env[SITE_TRUSTED_PROXY]?.trim()?.lowercase()
            return when (raw) {
                null, "", "false" -> false
                "true" -> true
                else -> {
                    problems += "$SITE_TRUSTED_PROXY doit valoir true ou false"
                    null
                }
            }
        }

        /**
         * Le bot Discord : `non` (ou rien) le laisse éteint. `a-blanc` et `oui` exigent le jeton et l'identifiant numérique du
         * serveur ; la phase vaut `attente` et le QG reste fermé (`SILOOO_DISCORD_PUBLIC`) quand elles ne sont pas posées.
         * Toute valeur inconnue arrête le démarrage.
         */
        fun bot(): BotConfig? {
            val rawMode = env[DISCORD_ACTIF]
            val mode = if (rawMode.isNullOrBlank()) BotMode.NON else BotMode.parse(rawMode)
            if (mode == null) {
                problems += "$DISCORD_ACTIF doit valoir ${BotMode.entries.joinToString(", ") { it.word }}"
                return null
            }
            if (mode == BotMode.NON) return null
            val token = required(DISCORD_TOKEN)?.trim()
            val guildId = required(DISCORD_SERVEUR)?.trim()
            if (guildId != null && !guildId.all { it.isDigit() }) {
                problems += "$DISCORD_SERVEUR doit être l'identifiant numérique du serveur Discord"
                return null
            }
            val rawPhase = env[DISCORD_PHASE]
            val phase = if (rawPhase.isNullOrBlank()) ServerPhase.ATTENTE else ServerPhase.parse(rawPhase)
            if (phase == null) {
                problems += "$DISCORD_PHASE doit valoir ${ServerPhase.entries.joinToString(", ") { it.word }}"
                return null
            }
            val rawPublic = env[DISCORD_PUBLIC]?.trim()?.lowercase()
            val public = when (rawPublic) {
                null, "", "non" -> false
                "oui" -> true
                else -> {
                    problems += "$DISCORD_PUBLIC doit valoir non ou oui"
                    return null
                }
            }
            if (token == null || guildId == null) return null
            return BotConfig(mode, BotToken(token), guildId, phase, public)
        }

        fun finish() {
            if (problems.isNotEmpty()) {
                throw ConfigException("Configuration invalide : " + problems.joinToString(" ; ") + ".")
            }
        }
    }
}
