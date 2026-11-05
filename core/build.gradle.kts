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
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.snakeyaml)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Les tests lisent definitions/ du dépôt, pas une copie : les chiffres du jeu n'ont qu'un seul endroit.
    // Le chemin vient de Gradle, jamais d'un chemin relatif écrit dans un test. Déclaré en entrée pour
    // que Gradle rejoue les tests quand un chiffre du jeu change.
    val definitionsDir = layout.projectDirectory.dir("../definitions")
    inputs.dir(definitionsDir).withPropertyName("definitions")
    systemProperty("silooo.definitions.dir", definitionsDir.asFile.absolutePath)
    testLogging {
        // Un test ignoré (Docker absent) doit se voir : jamais de réussite à vide.
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
