# syntax=docker/dockerfile:1

# Image de silooo-core. Le contexte de construction est la racine du dépôt, parce que l'image embarque
# definitions/ (les chiffres du jeu) à côté du code :
#   docker build -f core/Dockerfile -t silooo-core .
# Ce qui n'entre pas dans le contexte est dans core/Dockerfile.dockerignore (à côté de ce fichier).
# La CI la construit à chaque poussée et, sur main, la pousse sur ghcr.io/e-quantum/silooo-core.

# --- Étape 1 : construction, par le wrapper Gradle, sur le JDK 17 -----------------------------------
FROM eclipse-temurin:17-jdk AS build
# Même disposition que dans le dépôt, core/ à côté de definitions/ : le build de Gradle y retrouve ses chemins.
WORKDIR /src/core

# D'abord le wrapper et les fichiers de build, seuls : Gradle se télécharge dans une couche que Docker
# garde tant que ces fichiers ne changent pas. « sh » plutôt que « ./ » : le bit exécutable ne
# survit pas toujours à une copie depuis Windows.
COPY core/gradlew core/settings.gradle.kts core/build.gradle.kts core/gradle.properties ./
COPY core/gradle gradle
RUN sh ./gradlew --no-daemon --console=plain --version

COPY core/src src
# Les tests tournent ailleurs (job « verifier » de la CI) ; l'image ne contient que le code. definitions/
# n'est pas copié ici : le code ne le lit qu'au démarrage, et un chiffre du jeu qui change ne doit pas
# refaire la compilation.
RUN sh ./gradlew --no-daemon --console=plain installDist

# --- Étape 2 : exécution, sur le JRE 17 -----------------------------------------------------------
FROM eclipse-temurin:17-jre

# Jamais root : le service n'a besoin d'écrire nulle part.
RUN groupadd --system --gid 10001 silooo \
    && useradd --system --uid 10001 --gid silooo --no-create-home --shell /usr/sbin/nologin silooo

WORKDIR /app
COPY --from=build /src/core/build/install/silooo-core/lib /app/lib
# Les chiffres du jeu (economie.json, usines.json, boutique.json) : le service les lit au démarrage et
# refuse de démarrer s'il en manque un. Changer un chiffre, c'est reconstruire l'image, pas le code.
COPY definitions /app/definitions
ENV SILOOO_DEFINITIONS_DIR=/app/definitions
USER silooo

# Le service n'écoute que sur le réseau Docker. Le compose ne publie jamais ce port.
EXPOSE 8080

# Aucun secret dans l'image : la base et la clé arrivent par les variables SILOOO_* du conteneur.
#   docker run <image>           démarrage normal (definitions/, Flyway validate, puis le service)
#   docker run <image> migrate   applique les migrations Flyway et sort (service silooo-migrate)
# MaxRAMPercentage : le conteneur est limité en mémoire (512 Mo dans le plan), la JVM suit la limite.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Dfile.encoding=UTF-8", "-cp", "/app/lib/*", "com.silooo.core.MainKt"]
