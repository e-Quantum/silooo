# syntax=docker/dockerfile:1

# Image de silooo-core. Le contexte de construction est le dossier core/ :
#   docker build -t silooo-core core
# La CI la construit à chaque poussée et, sur main, la pousse sur ghcr.io/e-quantum/silooo-core.

# --- Étape 1 : construction, par le wrapper Gradle, sur le JDK 17 -----------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# D'abord le wrapper et les fichiers de build, seuls : Gradle se télécharge dans une couche que Docker
# garde tant que ces fichiers ne changent pas. « sh » plutôt que « ./ » : le bit exécutable ne
# survit pas toujours à une copie depuis Windows.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN sh ./gradlew --no-daemon --console=plain --version

COPY src src
# Les tests tournent ailleurs (job « verifier » de la CI) ; l'image ne contient que le code.
RUN sh ./gradlew --no-daemon --console=plain installDist

# --- Étape 2 : exécution, sur le JRE 17 -----------------------------------------------------------
FROM eclipse-temurin:17-jre

# Jamais root : le service n'a besoin d'écrire nulle part.
RUN groupadd --system --gid 10001 silooo \
    && useradd --system --uid 10001 --gid silooo --no-create-home --shell /usr/sbin/nologin silooo

WORKDIR /app
COPY --from=build /src/build/install/silooo-core/lib /app/lib
USER silooo

# Le service n'écoute que sur le réseau Docker. Le compose ne publie jamais ce port.
EXPOSE 8080

# Aucun secret dans l'image : la base et la clé arrivent par les variables SILOOO_* du conteneur.
#   docker run <image>           démarrage normal (Flyway validate, puis le service)
#   docker run <image> migrate   applique les migrations Flyway et sort (service silooo-migrate)
# MaxRAMPercentage : le conteneur est limité en mémoire (512 Mo dans le plan), la JVM suit la limite.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Dfile.encoding=UTF-8", "-cp", "/app/lib/*", "com.silooo.core.MainKt"]
