package com.silooo.core.economy

import com.silooo.core.definitions.Definitions
import com.silooo.core.definitions.FactoryDefinition

/**
 * Le moteur d'économie : les formules du jeu, rien d'autre. Pas de base, pas d'horloge, pas de
 * hasard, pas d'état : mêmes entrées, mêmes sorties, sur toutes les machines. Le service décide, le
 * moteur calcule.
 *
 * Aucun chiffre du jeu n'est écrit ici : croissance des prix, paliers, plafond hors ligne, seuils de
 * prestige, bonus par Lingot, prix et production des usines viennent tous de [Definitions]
 * (definitions/). Seules restent les constantes de la nature (24 heures par jour) et la règle ×2 des
 * paliers, que le nom de la clé `niveaux.paliersX2` porte déjà.
 *
 * Les fonctions mathématiques passent par `StrictMath` (fdlibm), pas par `Math` : leurs résultats ne
 * dépendent pas du processeur ni de la version de la JVM, et les tests dorés peuvent donc exiger la
 * même précision partout.
 *
 * Donar : Double (il monte jusqu'à environ 1e308). Lingots : Long (jamais arrondis après coup).
 * Les formules sont celles du tableur d'équilibrage ; les huit points d'interprétation que son
 * README relève sont tranchés plus bas, chacun marqué « à valider par Kr ».
 */
class EconomyEngine(private val definitions: Definitions) {
    private val levelRules = definitions.economy.levels
    private val offlineRules = definitions.economy.offline
    private val prestigeRules = definitions.economy.prestige
    private val factoryList: List<FactoryDefinition> = definitions.factories.factories
    private val factoriesById: Map<String, FactoryDefinition> = factoryList.associateBy { it.id }

    private val growth: Double = levelRules.priceGrowth
    private val fixedMilestones: List<Int> = levelRules.doublingMilestones.fixed.sorted()
    private val milestoneStep: Int = levelRules.doublingMilestones.thenEvery

    /** La dernière époque d'usines.json : une fois atteinte, chaque prestige donne une étoile. */
    private val lastEra: Int = factoryList.maxOf { it.era }

    // ---------------------------------------------------------------- usines et niveaux

    /** L'usine d'identifiant `factoryId`, telle que usines.json la décrit. */
    fun factory(factoryId: String): FactoryDefinition =
        factoriesById[factoryId] ?: throw IllegalArgumentException("Usine inconnue : $factoryId")

    /** Les identifiants des usines, dans l'ordre d'usines.json. */
    fun factoryIds(): List<String> = factoryList.map { it.id }

    /**
     * Les niveaux avec lesquels chaque course commence : l'usine de départ au niveau de départ, les autres
     * absentes (donc à zéro). Clé `prestige.departDeCourse`. À valider par Kr (point 2 du README doré).
     */
    fun startingLevels(): Map<String, Int> =
        mapOf(prestigeRules.courseStart.factoryId to prestigeRules.courseStart.level)

    /**
     * Le niveau d'une usine à la fin de sa remise en état : le niveau de départ de `prestige.departDeCourse`,
     * sans jamais reculer si l'usine en a déjà un. `definitions/` ne donne pas de niveau de remise en état
     * propre ; c'est le seul niveau de départ qu'il fixe, donc le plus petit qui fasse produire une usine.
     * **À valider par Kr** (docs/economie.md, section Remise en état).
     */
    fun restoredLevel(currentLevel: Int): Int {
        require(currentLevel >= 0) { "Un niveau n'est pas négatif : $currentLevel" }
        return maxOf(currentLevel, prestigeRules.courseStart.level)
    }

    /**
     * Le niveau d'entreprise : la somme des niveaux de toutes les usines (docs/game-design.md, section Usines et
     * personnel). Il révèle les usines dont `decouverte` vaut `niveau-entreprise-N`. Une usine absente de `levels`
     * compte pour zéro.
     */
    fun companyLevel(levels: Map<String, Int>): Int {
        val unknown = levels.keys.firstOrNull { it !in factoriesById }
        require(unknown == null) { "Usine inconnue : $unknown" }
        var total = 0
        for (level in levels.values) {
            require(level >= 0) { "Un niveau n'est pas négatif : $level" }
            total += level
        }
        return total
    }

    /**
     * Vrai si l'usine est révélée sur la carte (le « ????? » se lève), d'après la clé `decouverte` de usines.json :
     *  - `depart` : toujours, l'entreprise la possède dès sa création ;
     *  - `niveau-entreprise-N` : le niveau d'entreprise ([companyLevel]) atteint N ;
     *  - `epoque-N` : l'époque de la course atteint N ;
     *  - `apres-<usine>` : l'usine nommée est remise en état (`activeFactoryIds` : les usines `ACTIVE`).
     *
     * Une usine révélée le reste : le prestige garde la ligne de l'usine (`EN_RUINE`), il ne la cache pas. C'est donc à
     * l'appelant de se souvenir de ce qu'il a déjà révélé, ce test ne regarde que l'état d'aujourd'hui. Le service ne
     * lit pas encore `decouverte` ; le simulateur (src/test, paquet simulation) est le premier à le faire.
     * **À valider par Kr** (lecture de `apres-<usine>` : usine remise en état, pas seulement révélée).
     */
    fun isDiscovered(factoryId: String, companyLevel: Int, era: Int, activeFactoryIds: Set<String>): Boolean {
        require(companyLevel >= 0) { "Un niveau d'entreprise n'est pas négatif : $companyLevel" }
        require(era >= 1) { "Une époque se numérote depuis 1 : $era" }
        return when (val rule = discoveryRules.getValue(factory(factoryId).id)) {
            DiscoveryRule.AtStart -> true
            is DiscoveryRule.CompanyLevel -> companyLevel >= rule.level
            is DiscoveryRule.Era -> era >= rule.era
            is DiscoveryRule.AfterFactory -> rule.factoryId in activeFactoryIds
        }
    }

    /** Ce que la clé `decouverte` d'une usine demande, lue une fois (paresseusement : un moteur sans découverte n'échoue pas). */
    private val discoveryRules: Map<String, DiscoveryRule> by lazy {
        factoryList.associate { it.id to parseDiscovery(it) }
    }

    private fun parseDiscovery(factory: FactoryDefinition): DiscoveryRule {
        val key = factory.discovery
        fun numberAfter(prefix: String): Int? =
            if (key.startsWith(prefix)) key.removePrefix(prefix).toIntOrNull()?.takeIf { it >= 1 } else null
        val companyLevelNeeded = numberAfter(DISCOVERY_COMPANY_LEVEL)
        val eraNeeded = numberAfter(DISCOVERY_ERA)
        return when {
            key == DISCOVERY_AT_START -> DiscoveryRule.AtStart
            companyLevelNeeded != null -> DiscoveryRule.CompanyLevel(companyLevelNeeded)
            eraNeeded != null -> DiscoveryRule.Era(eraNeeded)
            key.startsWith(DISCOVERY_AFTER) -> {
                val required = key.removePrefix(DISCOVERY_AFTER)
                require(required in factoriesById && required != factory.id) {
                    "usines.json : ${factory.id}.decouverte « $key » nomme une usine inconnue ou elle-même"
                }
                DiscoveryRule.AfterFactory(required)
            }
            else -> throw IllegalArgumentException("usines.json : ${factory.id}.decouverte « $key » n'a pas une forme connue")
        }
    }

    private sealed interface DiscoveryRule {
        data object AtStart : DiscoveryRule
        data class CompanyLevel(val level: Int) : DiscoveryRule
        data class Era(val era: Int) : DiscoveryRule
        data class AfterFactory(val factoryId: String) : DiscoveryRule
    }

    /**
     * Le prix en Donar pour monter `factoryId` de `levels` niveaux à partir du niveau `fromLevel` :
     * `prixNiveau1 × g^fromLevel × (g^levels - 1) / (g - 1)`, la somme géométrique des prix de chaque
     * niveau (le niveau `n + 1` coûte `prixNiveau1 × g^n`). Zéro pour zéro niveau.
     */
    fun levelsCost(factoryId: String, fromLevel: Int, levels: Int): Double {
        require(fromLevel >= 0) { "Un niveau n'est pas négatif : $fromLevel" }
        require(levels >= 0) { "Un nombre de niveaux n'est pas négatif : $levels" }
        if (levels == 0) return 0.0
        return levelsCost(factory(factoryId), fromLevel, levels)
    }

    private fun levelsCost(factory: FactoryDefinition, fromLevel: Int, levels: Int): Double =
        factory.level1Price * StrictMath.pow(growth, fromLevel.toDouble()) *
            (StrictMath.pow(growth, levels.toDouble()) - 1.0) / (growth - 1.0)

    /** Le prix du prochain niveau seul. */
    fun nextLevelCost(factoryId: String, level: Int): Double = levelsCost(factoryId, level, 1)

    /**
     * Combien de fois la production a doublé au niveau `level` : une fois à chaque palier fixe atteint
     * (25, 50), plus une fois par multiple du pas régulier (100, 200, 300…). Les multiples du pas
     * comptent depuis zéro, sans attendre d'avoir passé les paliers fixes : c'est la lecture du tableur
     * (point 1 du README doré), à valider par Kr.
     */
    fun doublings(level: Int): Int {
        require(level >= 0) { "Un niveau n'est pas négatif : $level" }
        return fixedMilestones.count { level >= it } + level / milestoneStep
    }

    /** Le plus petit niveau strictement au-dessus de `level` où la production double (le « prochain palier ×2 »). */
    fun nextMilestone(level: Int): Int {
        require(level >= 0) { "Un niveau n'est pas négatif : $level" }
        val nextRegular = (level / milestoneStep + 1) * milestoneStep
        val nextFixed = fixedMilestones.firstOrNull { it > level }
        return if (nextFixed != null && nextFixed < nextRegular) nextFixed else nextRegular
    }

    /**
     * La production de `factoryId` au niveau `level`, en Donar par heure de jeu, avant tout
     * multiplicateur : `productionParNiveau × niveau × 2^doublings`.
     */
    fun factoryRate(factoryId: String, level: Int): Double = factoryRate(factory(factoryId), level)

    private fun factoryRate(factory: FactoryDefinition, level: Int): Double =
        StrictMath.scalb(factory.productionPerLevel * level, doublings(level))

    /**
     * La production de toutes les usines, en Donar par heure de jeu, avant multiplicateur. `levels` donne
     * le niveau de chaque usine par identifiant ; une usine absente est à zéro. La somme se fait dans
     * l'ordre d'usines.json, donc au même résultat bit pour bit à chaque appel.
     */
    fun baseRate(levels: Map<String, Int>): Double {
        val unknown = levels.keys.firstOrNull { it !in factoriesById }
        require(unknown == null) { "Usine inconnue : $unknown" }
        var total = 0.0
        for (factory in factoryList) {
            val level = levels[factory.id] ?: 0
            if (level > 0) total += factoryRate(factory, level)
        }
        return total
    }

    /** Le revenu en Donar par heure de jeu : [baseRate] fois `multiplier` (voir [globalMultiplier]). */
    fun ratePerHour(levels: Map<String, Int>, multiplier: Double): Double = multiplier * baseRate(levels)

    /**
     * Le prix d'un achat de niveaux pour `factoryId` au niveau `level` avec `balance` Donar en poche.
     *  - `ONE` et `TEN` : un lot fixe de niveaux ;
     *  - `MILESTONE` : jusqu'au prochain palier ×2 ([nextMilestone]) ;
     *  - `MAX` : le plus grand nombre de niveaux que `balance` paie (peut être zéro).
     * Le devis dit toujours le prix ; c'est au service de refuser quand [LevelQuote.affordable] est faux.
     */
    fun quote(factoryId: String, level: Int, amount: BuyAmount, balance: Double): LevelQuote {
        require(level >= 0) { "Un niveau n'est pas négatif : $level" }
        require(balance >= 0.0) { "Un solde n'est pas négatif : $balance" }
        val factory = factory(factoryId)
        val levels = when (amount) {
            BuyAmount.MAX -> maxAffordableLevels(factory, level, balance)
            BuyAmount.MILESTONE -> nextMilestone(level) - level
            else -> checkNotNull(amount.fixedLevels)
        }
        val cost = if (levels == 0) 0.0 else levelsCost(factory, level, levels)
        return LevelQuote(levels = levels, cost = cost, newLevel = level + levels, affordable = levels > 0 && cost <= balance)
    }

    /**
     * Le plus grand `k` tel que `levelsCost(k) <= balance`. On le tire des logarithmes puis on le corrige
     * à la main : l'estimation peut se tromper d'un niveau au bord.
     */
    private fun maxAffordableLevels(factory: FactoryDefinition, level: Int, balance: Double): Int {
        val nextPrice = factory.level1Price * StrictMath.pow(growth, level.toDouble())
        val estimate = StrictMath.floor(StrictMath.log1p(balance * (growth - 1.0) / nextPrice) / StrictMath.log(growth))
        val limit = Int.MAX_VALUE - level - 1
        var k = if (estimate.isFinite()) estimate.coerceIn(0.0, limit.toDouble()).toInt() else 0
        // On juge toujours avec le prix de levelsCost : la correction rend l'estimation exacte au bit près.
        while (k > 0 && levelsCost(factory, level, k) > balance) k--
        while (k < limit && levelsCost(factory, level, k + 1) <= balance) k++
        return k
    }

    // ---------------------------------------------------------------- multiplicateurs

    /**
     * La moyenne des Lingots détenus par les membres de l'entreprise (0 sans membre). Le bonus se calcule
     * sur cette moyenne : une équipe de quatre ne produit pas quatre fois plus vite qu'un joueur seul
     * (docs/game-design.md, section Social).
     */
    fun averageHeldLingots(heldByMember: Collection<Long>): Double {
        if (heldByMember.isEmpty()) return 0.0
        var total = 0.0
        for (held in heldByMember) total += held.toDouble()
        return total / heldByMember.size
    }

    /** Le multiplicateur des Lingots : `1 + bonusParLingotDetenu × Lingots détenus` (en moyenne, pour une équipe). */
    fun lingotMultiplier(averageHeldLingots: Double): Double = 1.0 + prestigeRules.bonusPerHeldLingot * averageHeldLingots

    /** Le multiplicateur des étoiles : `productionParEtoile ^ étoiles`. */
    fun starMultiplier(stars: Int): Double {
        require(stars >= 0) { "Un nombre d'étoiles n'est pas négatif : $stars" }
        return StrictMath.pow(prestigeRules.productionPerStar, stars.toDouble())
    }

    /**
     * Le multiplicateur global de production : celui des Lingots fois celui des étoiles. Le tableur le fige
     * pour toute la course : le service le recalcule quand les Lingots détenus changent, pas à chaque achat.
     * Les Lingots entrent avec leurs fractions (le tableur en compte des fractions) ; en jeu ils sont entiers.
     */
    fun globalMultiplier(averageHeldLingots: Double, stars: Int): Double =
        lingotMultiplier(averageHeldLingots) * starMultiplier(stars)

    // ---------------------------------------------------------------- hors ligne

    /**
     * Les heures hors ligne qui comptent : le temps absent, plafonné. Le plafond est `capHours` ; sans lui, le
     * plafond de départ de `horsLigne.plafondHeures`. Le service passe celui de l'entreprise
     * (`companies.offline_cap_hours`), que les améliorations étendent : la formule reste ici, le plafond
     * vient de l'appelant.
     */
    fun offlineHours(awayHours: Double, capHours: Double = offlineRules.capHours): Double {
        require(awayHours >= 0.0) { "Une durée d'absence n'est pas négative : $awayHours" }
        require(capHours >= 0.0) { "Un plafond hors ligne n'est pas négatif : $capHours" }
        return minOf(awayHours, capHours)
    }

    /**
     * Le Donar gagné en ligne : le revenu plein sur toutes les heures écoulées, sans plafond ni efficacité. C'est ce que
     * le compteur de l'add-on a fait défiler (`ratePerHour`), et le service doit verser ce que le joueur a vu.
     */
    fun onlineEarnings(ratePerHour: Double, hours: Double): Double {
        require(hours >= 0.0) { "Une durée en ligne n'est pas négative : $hours" }
        return ratePerHour * hours
    }

    /**
     * Le Donar gagné pendant l'absence : le revenu en ligne, sur les heures comptées ([offlineHours]), à
     * l'efficacité hors ligne.
     */
    fun offlineEarnings(ratePerHour: Double, awayHours: Double, capHours: Double = offlineRules.capHours): Double =
        ratePerHour * offlineHours(awayHours, capHours) * offlineRules.efficiency

    /**
     * Le multiplicateur hors ligne moyen d'un joueur qui joue `playHoursPerDay` heures par jour et dont les
     * autres heures rapportent à l'efficacité hors ligne, plafonnées : `1 + min(plafond, 24 - h) × efficacité / h`.
     * C'est la cellule B8 du tableur (joueur type). Le service n'en a pas besoin pour payer un joueur : il
     * sert à calibrer le jeu.
     */
    fun dailyOfflineMultiplier(playHoursPerDay: Double): Double {
        require(playHoursPerDay > 0.0 && playHoursPerDay <= HOURS_PER_DAY) { "Heures de jeu par jour hors de ]0 ; 24] : $playHoursPerDay" }
        return 1.0 + minOf(offlineRules.capHours, HOURS_PER_DAY - playHoursPerDay) * offlineRules.efficiency / playHoursPerDay
    }

    // ---------------------------------------------------------------- prestige

    /** Vrai si `prestige.seuils` liste le seuil de cette course (numérotée depuis 1) ; sinon il se déduit du dernier. */
    fun hasListedThreshold(course: Int): Boolean = course in 1..prestigeRules.thresholds.size

    /**
     * Le seuil de prestige de la course (Donar gagnés dans la course). Les courses de `prestige.seuils` ont
     * leur seuil. Au-delà de la dernière, chaque seuil vaut le précédent fois `prestige.facteurSeuilSuivant`
     * (le tableur s'arrête à la 6e étoile, la clé chiffre le texte `etoilesSuivantes` : point 5 du README
     * doré, **à valider par Kr**). Un seuil qui sortirait des doubles vaut [Double.MAX_VALUE] : la course est
     * alors sans fin, mais l'état d'une entreprise reste toujours calculable.
     */
    fun prestigeThreshold(course: Int): Double {
        require(course >= 1) { "Une course se numérote depuis 1 : $course" }
        val listed = prestigeRules.thresholds
        if (course <= listed.size) return listed[course - 1].donar
        val beyondLast = (course - listed.size).toDouble()
        val threshold = listed.last().donar * StrictMath.pow(prestigeRules.nextThresholdFactor, beyondLast)
        return if (threshold.isFinite()) threshold else Double.MAX_VALUE
    }

    /** Vrai quand les Donar gagnés dans la course atteignent son seuil (le seuil lui-même suffit). */
    fun canPrestige(course: Int, donarEarned: Double): Boolean = donarEarned >= prestigeThreshold(course)

    /**
     * Les Lingots du prestige avant arrondi : `coefficient × racine cubique (Donar gagnés / (seuil /
     * diviseurReference))`. Au seuil, `coefficient × racine cubique (diviseurReference)`. La racine cubique
     * donne un rendement décroissant : attendre 8 fois le seuil ne double que les Lingots.
     */
    fun prestigeLingotsExact(course: Int, donarEarned: Double): Double {
        val conversion = prestigeRules.lingotsConversion
        val reference = prestigeThreshold(course) / conversion.referenceDivisor
        return conversion.coefficient * StrictMath.cbrt(donarEarned / reference)
    }

    /**
     * Les Lingots gagnés au prestige, entiers : la partie entière de [prestigeLingotsExact], sauf quand le
     * calcul en machine tombe à un bruit de l'entier du dessus (99,99999999999999 pour 100), que l'on
     * rend entier. Une course finie à 0 Donar ou moins donne 0. À valider par Kr : aucun texte du dépôt
     * ne fixe l'arrondi (point 4 du README doré), et la partie entière ne donne jamais plus que le calcul.
     */
    fun prestigeLingots(course: Int, donarEarned: Double): Long {
        if (!(donarEarned > 0.0)) return 0L
        return roundLingots(prestigeLingotsExact(course, donarEarned))
    }

    /** La règle d'arrondi des Lingots, seule : partie entière, avec une tolérance pour le bruit de calcul. */
    fun roundLingots(exact: Double): Long {
        val nearest = StrictMath.rint(exact)
        val noise = FLOAT_NOISE * maxOf(1.0, StrictMath.abs(nearest))
        val rounded = if (StrictMath.abs(exact - nearest) <= noise) nearest else StrictMath.floor(exact)
        return rounded.toLong()
    }

    /** Vrai si le prestige de la course [course] donne une étoile (la dernière époque est passée) plutôt qu'une époque. */
    fun prestigeGivesStar(course: Int): Boolean = starsAt(course + 1) > starsAt(course)

    /** L'époque de la course (numérotée depuis 1) : une de plus à chaque prestige, jusqu'à la dernière d'usines.json. */
    fun eraAt(course: Int): Int {
        require(course >= 1) { "Une course se numérote depuis 1 : $course" }
        return minOf(course, lastEra)
    }

    /**
     * Les étoiles déjà gagnées au début de la course : zéro jusqu'à la dernière époque, puis une par
     * prestige. Le tableur les écrit course par course (Courses!D) ; le moteur les déduit de la dernière
     * époque d'usines.json. À valider par Kr : si une époque s'ajoute, les étoiles reculent d'un cran.
     */
    fun starsAt(course: Int): Int {
        require(course >= 1) { "Une course se numérote depuis 1 : $course" }
        return maxOf(0, course - lastEra)
    }

    companion object {
        /** Une journée : constante de la nature, pas un chiffre d'équilibrage. */
        const val HOURS_PER_DAY = 24.0

        /**
         * L'écart relatif sous lequel un résultat flottant compte pour l'entier voisin. 1e-9 est loin au-dessus
         * du bruit d'une racine cubique (1e-15) et loin sous le moindre Lingot à gagner.
         */
        const val FLOAT_NOISE = 1e-9

        /**
         * Le vocabulaire de la clé `decouverte` d'une usine : le motif de definitions/schemas/usines.schema.json
         * (`depart`, `niveau-entreprise-N`, `epoque-N`, `apres-<usine>`), une grammaire et pas un réglage.
         */
        private const val DISCOVERY_AT_START = "depart"
        private const val DISCOVERY_COMPANY_LEVEL = "niveau-entreprise-"
        private const val DISCOVERY_ERA = "epoque-"
        private const val DISCOVERY_AFTER = "apres-"
    }
}
