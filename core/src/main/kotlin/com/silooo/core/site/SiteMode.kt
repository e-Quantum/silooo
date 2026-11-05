package com.silooo.core.site

/**
 * Les deux états du site public. Le défaut est [ATTENTE] : tant que Kr n'a pas décidé d'ouvrir, le site ne
 * montre qu'une page d'attente et les mentions. [OUVERT] n'est posé que par `SILOOO_SITE_MODE=ouvert`,
 * jamais par un fichier du dépôt ni par un défaut du code.
 */
enum class SiteMode(val word: String) {
    ATTENTE("attente"),
    OUVERT("ouvert"),
    ;

    companion object {
        /** Le mode que désigne ce mot (sans tenir compte des espaces autour ni de la casse), ou `null` si le mot est inconnu. */
        fun parse(raw: String): SiteMode? = entries.firstOrNull { it.word == raw.trim().lowercase() }
    }
}
