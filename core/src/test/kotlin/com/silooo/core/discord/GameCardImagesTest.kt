package com.silooo.core.discord

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GameCardImagesTest {
    @Test fun canonicalSnapshotCanBeAttachedAsReadablePng() {
        val file = requireNotNull(GameCardImages.render("Entreprise\nÉpoque 2 · P5\nProduction : 123,45 Donar/h"))
        val image = ImageIO.read(ByteArrayInputStream(file.bytes))
        assertTrue(image.width >= 640)
        assertTrue(image.height >= 240)
        assertTrue(file.bytes.size > 500)
    }
}
