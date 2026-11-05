# syntax=docker/dockerfile:1

# Image de silooo-bds : l'add-on TypeScript de Silooo et le script qui lance le serveur Bedrock officiel (BDS).
# L'image ne contient PAS BDS (décision 26, docs/passation.md) : minecraft.net refuse les clients qui ne sont pas des
# navigateurs et les conditions de BDS interdisent de redistribuer le zip. Kr pose le zip dans le dossier monté sur
# /data/bds ; start.sh le contrôle (SHA-256 de bds.sha256, ou MD5 de bds.md5), le décompresse une fois dans
# /data/bds/<version>/ puis lance bedrock_server (docs/deploiement.md, « Le zip de BDS »). La CI construit donc cette
# image sans rien télécharger sur minecraft.net.
#
# Le contexte de construction est la racine du dépôt, comme pour core/Dockerfile :
#   docker build -f deploy/bds/Dockerfile -t silooo-bds .
# Ce qui n'entre pas dans le contexte est dans deploy/bds/Dockerfile.dockerignore (à côté de ce fichier).
# La CI la construit à chaque poussée et, sur main, la pousse sur ghcr.io/e-quantum/silooo-bds (job image-bds).
# Elle ne contient aucun secret : la clé X-Silooo-Key arrive par SILOOO_API_KEY, au démarrage (start.sh).

# --- Étape 1 : l'add-on, par esbuild, sur Node ------------------------------------------------------------------
# Tag précis et somme de l'index : l'image de construction ne change pas sans qu'on le décide.
FROM node:22.23.4-bookworm-slim@sha256:4b150b48b4116e2a22f2c39cc27025fbf4f153e2078fd3b14d159fdb5c7279da AS addon
WORKDIR /src
# Tout le contexte, réduit par Dockerfile.dockerignore à addon/, definitions/ et deploy/bds/. Contrat avec l'add-on :
# « npm run build » produit addon/dist/pack/manifest.json et addon/dist/pack/scripts/main.js. La disposition du dépôt
# est gardée : addon/ à côté de definitions/.
COPY . /contexte
RUN set -eu; \
    test -f /contexte/addon/package.json; \
    cp -R /contexte/addon /src/addon; \
    if [ -d /contexte/definitions ]; then cp -R /contexte/definitions /src/definitions; fi; \
    cd /src/addon; \
    npm ci --no-audit --no-fund; \
    npm run build; \
    test -f dist/pack/manifest.json; \
    test -f dist/pack/scripts/main.js

# --- Étape 2 : l'image d'exécution, sur Ubuntu (la seule distribution Linux prise en charge par BDS) ---------------
FROM ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852

LABEL org.opencontainers.image.source="https://github.com/e-Quantum/silooo" \
      org.opencontainers.image.title="silooo-bds" \
      org.opencontainers.image.description="Lanceur du serveur Bedrock (BDS) de Silooo, avec son add-on ; le zip de BDS vient de Kr"

# La version de BDS que start.sh cherche : /data/bds/bedrock-server-<version>.zip. deploy/bds/bds.sha256 et
# deploy/bds/bds.md5 portent les sommes du zip de CETTE version : changer l'une sans l'autre fait refuser le
# démarrage (docs/bedrock.md, « La procédure de mise à jour »).
ARG BDS_VERSION=1.26.62.1
# Si le zip manque, start.sh tente un téléchargement, deux fois, avec cette identité honnête : minecraft.net la
# refuse aujourd'hui (aucune réponse), le message de start.sh dit alors à Kr quoi faire. On ne la déguise pas.
ARG BDS_USER_AGENT="silooo-bds/1.0 (+https://github.com/e-Quantum/silooo)"
ENV BDS_VERSION=${BDS_VERSION} \
    BDS_USER_AGENT=${BDS_USER_AGENT}

# Jamais root, même uid et même gid que silooo-core.
RUN groupadd --system --gid 10001 silooo \
    && useradd --system --uid 10001 --gid silooo --home-dir /opt/silooo --no-create-home --shell /usr/sbin/nologin silooo

# Tout dans une seule couche.
#  - ca-certificates : BDS s'authentifie auprès de Xbox Live, et curl vérifie minecraft.net ;
#  - curl et unzip : start.sh télécharge (en dernier recours) puis décompresse le zip ;
#  - libcurl4t64 : bibliothèque dont BDS dépend ; jq : start.sh lit manifest.json.
#  - /data/worlds et /data/config sont les volumes nommés du monde et de la config ; /data/bds est le dossier de Kr
#    (le zip) et l'endroit où BDS est décompressé.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl unzip jq libcurl4t64; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p /data/worlds /data/config /data/bds /opt/silooo; \
    chown -R silooo:silooo /data /opt/silooo

# Le pack de comportement construit à l'étape 1. start.sh le copie dans behavior_packs/silooo de BDS à chaque démarrage.
COPY --from=addon --chown=10001:10001 /src/addon/dist/pack /opt/silooo/pack/
COPY --chown=10001:10001 --chmod=0755 deploy/bds/start.sh /opt/silooo/start.sh
COPY --chown=10001:10001 deploy/bds/server.properties.template deploy/bds/bds.sha256 deploy/bds/bds.md5 /opt/silooo/

USER silooo
WORKDIR /opt/silooo
# Le monde et la config de BDS vivent dans des volumes nommés (deploy/compose.yml) ; /data/bds est un dossier de
# l'hôte, monté par le compose (SILOOO_BDS_DIR), où Kr pose le zip.
VOLUME ["/data/worlds", "/data/config", "/data/bds"]
# 19142 en TCP : signalisation NetherNet (ADR 0004). La plage UDP est celle de BDS_UDP_PORTS (start.sh).
EXPOSE 19142/tcp 19143-19174/udp
ENTRYPOINT ["/opt/silooo/start.sh"]
