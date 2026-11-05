package com.silooo.core.site

import com.silooo.core.TestDefinitions
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Le dossier `design/` est la source du logo : la copie que Gradle pose dans le site lui ressemble octet pour octet. */
class SiteLogoTest {
    @Test
    @DisplayName("Les logos du site sont les copies exactes de design/logo/")
    fun logosAreCopiesOfTheDesignSource() {
        val design = TestDefinitions.directory.resolveSibling("design")
        for (name in listOf("silooo-logo-128.png", "silooo-logo-64.png")) {
            val source = Files.readAllBytes(design.resolve("logo").resolve(name))
            val copy = checkNotNull(SiteLogoTest::class.java.classLoader.getResourceAsStream("site/public/logo/$name")) {
                "site/public/logo/$name est introuvable"
            }.use { it.readBytes() }
            assertArrayEquals(source, copy, "$name n'est pas la copie de design/logo/$name : la copie vient de Gradle (processResources), pas d'une retouche")
        }
    }
}
