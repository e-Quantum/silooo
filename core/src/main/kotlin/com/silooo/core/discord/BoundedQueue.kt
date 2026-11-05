package com.silooo.core.discord

import java.util.ArrayDeque

/**
 * Une file bornée : au-delà de [capacity] éléments en attente, le plus ancien est abandonné et compté. C'est la
 * file des images que le bot dessine (cartes, salon en direct) : si Discord ou le dessin ralentissent, la file
 * ne grossit pas, le service ne manque pas de mémoire, et le compte des abandons dit qu'il faut regarder
 * (critique du plan, point 4).
 */
class BoundedQueue<T>(val capacity: Int) {
    init {
        require(capacity > 0) { "La capacité d'une file doit être au moins 1" }
    }

    private val items = ArrayDeque<T>()

    var dropped: Long = 0
        private set

    val size: Int get() = synchronized(items) { items.size }

    /** Ajoute [item] ; si la file est pleine, le plus ancien part, et [dropped] avance. */
    fun offer(item: T) {
        synchronized(items) {
            if (items.size >= capacity) {
                items.removeFirst()
                dropped++
            }
            items.addLast(item)
        }
    }

    /** Le plus ancien élément, ou `null` si la file est vide. */
    fun poll(): T? = synchronized(items) { items.pollFirst() }
}
