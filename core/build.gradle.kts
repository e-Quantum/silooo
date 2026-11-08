// silooo-core : le service Kotlin qui décide de tout (ADR 0002). Versions dans gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.silooo"
version = "0.1.0"

kotlin {
    // JDK 17 partout : PC de Kr, CI (Temurin 17) et image Docker (eclipse-temurin:17).
    jvmToolchain(17)
}

application {
    mainClass.set("com.silooo.core.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.commonmark)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.hikari)
    implementation(libs.mariadb.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)

    implementation(libs.logback.classic)

    // Le bot Discord (ADR 0007). Sans le module audio : Oooscar ne parle pas dans les vocaux.
    implementation(libs.jda) { exclude(group = "club.minnced", module = "opus-java") }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.snakeyaml)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Silooo Atelier sur le web (ADR 0006, docs/site.md « Style ») : design/ est la source, et Gradle pose ses copies dans les
// ressources du site à la construction. Rien de design/ n'est recopié à la main dans core/ ; SiteAtelierTest compare les
// copies à la source. L'image Docker copie design/ à côté de core/ pour la même raison (core/Dockerfile).
val design = layout.projectDirectory.dir("../design")

// Les textures que design/composants/bundle.css cite par une adresse « /_blob/<id> » : composants.css est bundle.css où
// chaque adresse devient le fichier copié dans atelier/textures/ (« textures/<fichier> », relatif à composants.css, qui
// vit dans atelier/). La même table est dans docs/site.md, « Style ».
val atelierBlobs = mapOf(
    "a4d05c169326a8b4aa0fb334c391b77e" to "panneau-fonte.png",
    "94ebf96cdf9eef88349cd743b9315141" to "panneau-laiton.png",
    "d7af43cc05748282c79d26d333ba06c6" to "plaque-email-bleue.png",
    "dccdac589ecaceba237e63e35e10a765" to "plaque-email-rouge.png",
    "c839f61c22c8c591861ea4016a3e10e4" to "bouton-rouge.png",
    "840602f48323529d32204a8a0ae10bdd" to "bouton-rouge-appuye.png",
    "c53886f5da15f3253b9d1be6925a5535" to "bouton-laiton.png",
    "74c34630b76f29c8c49a48ae6e489fbf" to "bouton-fonte.png",
    "a6b99c94530b5a3d162af3d96cdd8360" to "bouton-fonte-appuye.png",
    "01d554229628700e4bdccbb93ffff2b1" to "ticket-papier.png",
    "fba9cfbc87b336b0c34bd8edd9505149" to "jauge-allumee.png",
    "9f115281b9b2daa72f487394640a2207" to "jauge-eteinte.png",
    "f352a5dcf299bca6ab1fc72e6cae52f2" to "puce-fonte.png",
    "7b948b9cc6ff6cd16ce77099f62bc677" to "puce-laiton.png",
    "7a5fdd7669e8dcf5d609cc07ba1a9700" to "piste.png",
    "6ac1bc4b9e5d3236ea51ebd88d85ee08" to "plein.png",
)

tasks.processResources {
    // Le filtre de composants.css lit et écrit du texte : en UTF-8, quel que soit le poste (Windows compris).
    filteringCharset = "UTF-8"
    doFirst {
        check(design.asFile.isDirectory) { "design/ manque à côté de core/ : le site en tire ses jetons, ses textures, ses icônes, Oooscar et le logo." }
    }
    from(design.file("tokens.css")) { into("site/public/atelier") }
    from(design.dir("textures")) { into("site/public/atelier/textures") }
    from(design.dir("icones")) { into("site/public/atelier/icones") }
    from(design.dir("mascotte")) { into("site/public/atelier/oooscar") }
    // Le décor (terre, herbe, pierre, planches) : le sol de la scène d'Oooscar, sous chaque page.
    from(design.dir("decor")) { into("site/public/atelier/decor") }
    from(design.dir("logo")) { into("site/public/logo") }
    from(design.file("composants/bundle.css")) {
        into("site/public/atelier")
        rename { "composants.css" }
        filter { line ->
            Regex("/_blob/([0-9a-f]+)").replace(line) { match ->
                val id = match.groupValues[1]
                val file = atelierBlobs[id] ?: throw GradleException("design/composants/bundle.css cite /_blob/$id, que la table atelierBlobs de core/build.gradle.kts ne connaît pas")
                "textures/$file"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // Les tests lisent definitions/ du dépôt, pas une copie : les chiffres du jeu n'ont qu'un seul endroit.
    // Le chemin vient de Gradle, jamais d'un chemin relatif écrit dans un test. Déclaré en entrée pour
    // que Gradle rejoue les tests quand un chiffre du jeu change.
    val definitionsDir = layout.projectDirectory.dir("../definitions")
    inputs.dir(definitionsDir).withPropertyName("definitions")
    systemProperty("silooo.definitions.dir", definitionsDir.asFile.absolutePath)
    // Les pages du site rendues à chaque passage (SiteRenderToDisk) : tools/verifier_site.mjs les ouvre dans Chromium et
    // les captures de relecture en partent. Le dossier est une sortie des tests, Gradle le rejoue s'il disparaît.
    val siteRenderDir = layout.buildDirectory.dir("site-rendu")
    outputs.dir(siteRenderDir).withPropertyName("siteRenderDir")
    environment("SILOOO_SITE_RENDER_DIR", siteRenderDir.get().asFile.absolutePath)
    testLogging {
        // Un test ignoré (Docker absent) doit se voir : jamais de réussite à vide.
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
