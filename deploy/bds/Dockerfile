# syntax=docker/dockerfile:1

# Image de silooo-bds : le serveur Bedrock officiel (BDS) avec l'add-on TypeScript de Silooo. Le contexte de
# construction est la racine du dépôt, comme pour core/Dockerfile :
#   docker build -f deploy/bds/Dockerfile -t silooo-bds .
# Ce qui n'entre pas dans le contexte est dans deploy/bds/Dockerfile.dockerignore (à côté de ce fichier).
# La CI la construit à chaque poussée et, sur main, la pousse sur ghcr.io/e-quantum/silooo-bds (job image-bds).
# Elle ne contient aucun secret : la clé X-Silooo-Key arrive par SILOOO_API_KEY, au démarrage (start.sh).

# --- Étape 1 : l'add-on, par esbuild, sur Node ------------------------------------------------------------------
# Tag précis et somme de l'index : l'image de construction ne change pas sans qu'on le décide.
FROM node:22.23.4-bookworm-slim@sha256:4b150b48b4116e2a22f2c39cc27025fbf4f153e2078fd3b14d159fdb5c7279da AS addon
WORKDIR /src
# Tout le contexte, réduit par Dockerfile.dockerignore à addon/, definitions/ et deploy/bds/ : un « COPY addon/ »
# direct échouerait tant que le dossier n'existe pas (phase 2, avant l'add-on).
COPY . /contexte
# Contrat avec la session de l'add-on : « npm run build » produit addon/dist/pack/manifest.json et
# addon/dist/pack/scripts/main.js. Sans addon/package.json, l'étape produit un pack vide et l'image démarre sans
# pack (start.sh le dit). La disposition du dépôt est gardée : addon/ à côté de definitions/.
RUN set -eu; \
    if [ -f /contexte/addon/package.json ]; then \
      echo "addon/ présent : npm ci, puis npm run build"; \
      cp -R /contexte/addon /src/addon; \
      if [ -d /contexte/definitions ]; then cp -R /contexte/definitions /src/definitions; fi; \
      cd /src/addon; \
      npm ci --no-audit --no-fund; \
      npm run build; \
      test -f dist/pack/manifest.json; \
      test -f dist/pack/scripts/main.js; \
    else \
      echo "addon/ absent : pack vide, l'image démarrera sans pack"; \
    fi; \
    mkdir -p /src/addon/dist/pack

# --- Étape 2 : le serveur, sur Ubuntu (la seule distribution Linux prise en charge par BDS) -------------------
FROM ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852

LABEL org.opencontainers.image.source="https://github.com/e-Quantum/silooo" \
      org.opencontainers.image.title="silooo-bds" \
      org.opencontainers.image.description="Serveur Bedrock (BDS) de Silooo avec son add-on"

# La version de BDS. deploy/bds/bds.sha256 porte la somme du zip de CETTE version : changer l'une sans l'autre
# fait échouer la construction (docs/bedrock.md, « La procédure de mise à jour »).
ARG BDS_VERSION=1.26.62.1
# minecraft.net ne répond pas au User-Agent par défaut de curl (docs/bedrock.md, fait 4) : le nôtre dit qui télécharge.
ARG BDS_USER_AGENT="silooo-bds-build/1.0 (+https://github.com/e-Quantum/silooo)"

# Jamais root, même uid et même gid que silooo-core.
RUN groupadd --system --gid 10001 silooo \
    && useradd --system --uid 10001 --gid silooo --home-dir /opt/bds --no-create-home --shell /usr/sbin/nologin silooo

COPY deploy/bds/bds.sha256 /tmp/bds.sha256

# Tout dans une seule couche, chown compris : un chown dans une couche suivante doublerait la taille de BDS.
#  - ca-certificates : BDS s'authentifie auprès de Xbox Live ; libcurl4t64 : bibliothèque dont BDS dépend ;
#    jq : start.sh lit manifest.json ; curl reste pour diagnostiquer le lien avec silooo-core depuis le conteneur.
#  - la somme SHA-256 est écrite dans le journal, puis contrôlée contre bds.sha256.
#  - worlds/ et config/ sont des liens vers les volumes /data/worlds et /data/config ; allowlist.json et
#    permissions.json (les opérateurs) sont des liens vers le volume de config/, pour survivre aux redéploiements.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl unzip jq libcurl4t64; \
    curl --fail --silent --show-error --location --retry 3 --retry-all-errors --retry-delay 5 \
      --connect-timeout 30 --max-time 300 --user-agent "${BDS_USER_AGENT}" \
      --output "/tmp/bedrock-server-${BDS_VERSION}.zip" \
      "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-${BDS_VERSION}.zip"; \
    ls -l "/tmp/bedrock-server-${BDS_VERSION}.zip"; \
    md5sum "/tmp/bedrock-server-${BDS_VERSION}.zip"; \
    sha256sum "/tmp/bedrock-server-${BDS_VERSION}.zip"; \
    grep -v '^[[:space:]]*#' /tmp/bds.sha256 | (cd /tmp && sha256sum --check --strict -); \
    mkdir -p /opt/bds; \
    unzip -q "/tmp/bedrock-server-${BDS_VERSION}.zip" -d /opt/bds; \
    rm -f /tmp/bedrock-server-*.zip /tmp/bds.sha256; \
    chmod 0755 /opt/bds/bedrock_server; \
    rm -rf /opt/bds/worlds /opt/bds/config /opt/bds/server.properties /opt/bds/allowlist.json /opt/bds/permissions.json; \
    mkdir -p /data/worlds /data/config /opt/bds/behavior_packs/silooo /opt/silooo; \
    ln -s /data/worlds /opt/bds/worlds; \
    ln -s /data/config /opt/bds/config; \
    ln -s /data/config/allowlist.json /opt/bds/allowlist.json; \
    ln -s /data/config/operateurs.json /opt/bds/permissions.json; \
    chown -R silooo:silooo /opt/bds /data; \
    apt-get purge -y --auto-remove unzip; \
    rm -rf /var/lib/apt/lists/*

# Le pack de comportement construit à l'étape 1 (vide tant que l'add-on n'existe pas).
COPY --from=addon --chown=10001:10001 /src/addon/dist/pack /opt/bds/behavior_packs/silooo/
COPY --chown=10001:10001 --chmod=0755 deploy/bds/start.sh /opt/silooo/start.sh
COPY --chown=10001:10001 deploy/bds/server.properties.template /opt/silooo/server.properties.template

USER silooo
WORKDIR /opt/bds
# Le monde et la config de BDS vivent dans des volumes nommés (deploy/compose.yml).
VOLUME ["/data/worlds", "/data/config"]
# 19142 en TCP : signalisation NetherNet (ADR 0004). La plage UDP est celle de BDS_UDP_PORTS (start.sh).
EXPOSE 19142/tcp 19143-19174/udp
ENV LD_LIBRARY_PATH=.
ENTRYPOINT ["/opt/silooo/start.sh"]
