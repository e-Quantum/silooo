package com.silooo.core.discord

import java.lang.reflect.Proxy

/** Toute opération sur cette dépendance fait échouer le test, y compris une simple lecture. */
internal inline fun <reified T> forbiddenDiscordDependency(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw AssertionError("Appel interdit pendant ce test : " + T::class.java.simpleName + "." + method.name)
    } as T
