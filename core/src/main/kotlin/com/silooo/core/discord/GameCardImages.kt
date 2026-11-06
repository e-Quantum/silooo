package com.silooo.core.discord

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Carte D3 dessinée dans le core, à partir de la texture Atelier déjà livrée. Le texte du message reste complet.
 * Présentation provisoire à valider par Kr ; aucun chiffre ni état économique n'est calculé ici. */
object GameCardImages {
    fun render(text: String): ImageFile? = try {
        val image = BufferedImage(960, 360, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            val texture = javaClass.getResourceAsStream("/site/public/atelier/textures/panneau-fonte.png")
                ?.use(ImageIO::read)
            graphics.color = Color(0x202C35)
            graphics.fillRect(0, 0, image.width, image.height)
            if (texture != null) graphics.drawImage(texture, 0, 0, image.width, image.height, null)
            graphics.color = Color(BotMessage.SKY)
            graphics.fillRect(0, 0, 8, image.height)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 22)
            graphics.color = Color.WHITE
            val lines = text.replace("**", "").lines().flatMap { line -> line.chunked(64).ifEmpty { listOf("") } }
            lines.take(10).forEachIndexed { index, line -> graphics.drawString(line, 30, 40 + index * 30) }
        } finally { graphics.dispose() }
        val bytes = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", bytes))
        ImageFile("carte-jeu.png", bytes.toByteArray())
    } catch (e: Exception) {
        // La carte textuelle conserve les données même si le moteur de dessin est absent sur une plateforme.
        null
    }
}
