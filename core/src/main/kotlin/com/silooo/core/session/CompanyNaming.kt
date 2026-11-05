package com.silooo.core.session

/**
 * Le nom d'une entreprise à sa création.
 *
 * Le game design veut que le joueur nomme son entreprise (docs/game-design.md, « Interface et onboarding
 * console », étape 1), mais le contrat n'a ni route pour la nommer ni champ dans `join`. En attendant, le
 * service donne un nom PROVISOIRE dérivé du gamertag. Le texte est vu par les joueurs (enseignes,
 * classement) : à valider par Kr.
 */
object CompanyNaming {
    /** `companies.name` : VARCHAR(32). */
    const val MAX_LENGTH = 32

    /** À valider par Kr : le texte du nom provisoire. */
    private const val PROVISIONAL_PREFIX = "Entreprise de "

    /** Le nom provisoire pour [gamertag], coupé à [MAX_LENGTH] caractères. */
    fun provisional(gamertag: String): String = truncate(PROVISIONAL_PREFIX + gamertag.trim(), MAX_LENGTH)

    /**
     * Le [attempt]-ième essai de nom pour [base] : le nom lui-même au premier, puis « nom 2 », « nom 3 »…
     * quand le nom est pris (`companies.name` est unique, sans tenir compte de la casse ni des accents).
     * Le tout reste dans [MAX_LENGTH] caractères : c'est la base qui est coupée, pas le suffixe.
     */
    fun candidate(base: String, attempt: Int): String {
        require(attempt >= 1) { "attempt : 1 ou plus" }
        if (attempt == 1) return truncate(base, MAX_LENGTH)
        val suffix = " $attempt"
        return truncate(base, MAX_LENGTH - suffix.length) + suffix
    }

    /** Coupe à [max] caractères Unicode (pas au milieu d'une paire de substitution), sans espace final. */
    private fun truncate(text: String, max: Int): String {
        val length = text.codePointCount(0, text.length)
        if (length <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max)).trimEnd()
    }
}
