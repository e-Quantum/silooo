// Teste deploy/compose.yml, deploy/exemple.env.txt, le Dockerfile et start.sh les uns contre les autres, sans Docker.
// Lancé par tools/tests/run.sh ; utilisable seul : node tools/tests/deploy-compose.mjs
// Dépendance : le paquet « yaml » (devDependency de la racine, installé par npm ci).
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse } from 'yaml';

const racine = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const lire = (chemin) => readFileSync(join(racine, chemin), 'utf8');

let reussis = 0;
let echecs = 0;
const ok = (texte) => { reussis++; console.log(`  ok     ${texte}`); };
const ko = (texte, detail) => {
  echecs++;
  console.log(`  ECHEC  ${texte}`);
  if (detail !== undefined) console.log(String(detail).split('\n').map((l) => `           ${l}`).join('\n'));
};
const verifier = (condition, texte, detail) => (condition ? ok(texte) : ko(texte, detail));
const egal = (a, b) => JSON.stringify(a) === JSON.stringify(b);

// Une liste ou une table de clés (la forme courte ou longue de networks, depends_on, env_file) : les noms.
const noms = (valeur) => {
  if (valeur === undefined || valeur === null) return [];
  if (typeof valeur === 'string') return [valeur];
  if (Array.isArray(valeur)) return valeur.map((v) => (typeof v === 'string' ? v : v.path ?? JSON.stringify(v)));
  return Object.keys(valeur);
};

const texteCompose = lire('deploy/compose.yml');
let compose;
try {
  compose = parse(texteCompose);
} catch (e) {
  ko('deploy/compose.yml est du YAML valide', e.message);
  process.exit(1);
}

console.log('compose.yml : la forme');
verifier(compose.name === 'silooo', 'le projet s\'appelle silooo', compose.name);
const services = compose.services ?? {};
verifier(egal(Object.keys(services).sort(), ['silooo-bds', 'silooo-core', 'silooo-migrate']),
  'trois services : silooo-migrate, silooo-core, silooo-bds', Object.keys(services));
const { 'silooo-migrate': migrate = {}, 'silooo-core': core = {}, 'silooo-bds': bds = {} } = services;

// silooo-migrate
verifier(String(migrate.image).startsWith('ghcr.io/e-quantum/silooo-core:${SILOOO_TAG:?'), 'silooo-migrate : image de silooo-core, tag obligatoire', migrate.image);
verifier(egal(migrate.command, ['migrate']), 'silooo-migrate : commande migrate', migrate.command);
verifier(migrate.mem_limit === '256m', 'silooo-migrate : 256 Mo', migrate.mem_limit);
verifier(migrate.restart === 'no', 'silooo-migrate : ne redémarre pas (une fois, puis il sort)', migrate.restart);
verifier(egal(noms(migrate.env_file), ['.env']), 'silooo-migrate : lit le fichier d\'environnement', noms(migrate.env_file));
verifier(egal(noms(migrate.networks).sort(), ['silenemc', 'silooo']), 'silooo-migrate : réseaux silooo et silenemc', noms(migrate.networks));

// silooo-core
verifier(String(core.image).startsWith('ghcr.io/e-quantum/silooo-core:${SILOOO_TAG:?'), 'silooo-core : image de silooo-core, tag obligatoire', core.image);
verifier(core.ports === undefined, 'silooo-core : aucun port publié', core.ports);
verifier(core.mem_limit === '512m', 'silooo-core : 512 Mo', core.mem_limit);
verifier(core.restart === 'unless-stopped', 'silooo-core : unless-stopped', core.restart);
verifier(core.depends_on?.['silooo-migrate']?.condition === 'service_completed_successfully',
  'silooo-core attend la fin réussie de silooo-migrate', core.depends_on);
verifier(egal(noms(core.env_file), ['.env']), 'silooo-core : lit le fichier d\'environnement', noms(core.env_file));
verifier(egal(noms(core.networks).sort(), ['silenemc', 'silooo']), 'silooo-core : réseaux silooo et silenemc', noms(core.networks));

// silooo-bds
verifier(String(bds.image).startsWith('ghcr.io/e-quantum/silooo-bds:${SILOOO_TAG:?'), 'silooo-bds : image de silooo-bds, tag obligatoire', bds.image);
verifier(noms(bds.depends_on).includes('silooo-core'), 'silooo-bds dépend de silooo-core', bds.depends_on);
verifier(bds.mem_limit === '1536m', 'silooo-bds : 1,5 Gio', bds.mem_limit);
verifier(bds.restart === 'on-failure:5', 'silooo-bds : on-failure:5 (sans zip valide start.sh sort en erreur : pas de boucle de téléchargements)', bds.restart);
verifier(egal(noms(bds.networks), ['silooo']), 'silooo-bds : seulement le réseau silooo, pas celui de SileneMC', noms(bds.networks));
verifier(bds.env_file === undefined, 'silooo-bds : pas d\'env_file (ni mot de passe de la base, ni autre secret que la clé listée)', bds.env_file);
const portsBds = (bds.ports ?? []).map(String);
verifier(portsBds.length === 2, 'silooo-bds : deux publications de ports, TCP et UDP', portsBds);
verifier(portsBds.some((p) => p.endsWith('/tcp') && p.includes('${BDS_PORT:-19142}:${BDS_PORT:-19142}')), 'silooo-bds : le port TCP de signalisation (BDS_PORT) est publié', portsBds);
verifier(portsBds.some((p) => p.endsWith('/udp') && p.includes('${BDS_UDP_PORTS_PUBLIES:-') && p.includes(':${BDS_UDP_PORTS:-')),
  'silooo-bds : la plage UDP publiée:locale vient de BDS_UDP_PORTS_PUBLIES et BDS_UDP_PORTS', portsBds);
const volumesBds = bds.volumes ?? [];
verifier(egal(volumesBds.filter((v) => typeof v === 'string').sort(), ['bds-config:/data/config', 'bds-worlds:/data/worlds']), 'silooo-bds : volumes du monde et de la config', volumesBds);
const montageZip = volumesBds.find((v) => typeof v === 'object' && v?.target === '/data/bds');
verifier(volumesBds.length === 3 && montageZip?.type === 'bind' && montageZip.source === '${SILOOO_BDS_DIR:-/var/www/silooo/bds}' && montageZip.bind?.create_host_path === false,
  'silooo-bds : /data/bds est un bind mount de SILOOO_BDS_DIR (défaut /var/www/silooo/bds), jamais créé par Docker (décision 26)', montageZip);
verifier(String(bds.environment?.SILOOO_API_KEY).includes('${SILOOO_API_KEY:?'), 'silooo-bds : SILOOO_API_KEY obligatoire', bds.environment?.SILOOO_API_KEY);

// réseaux et volumes
verifier(compose.networks?.silenemc?.external === true && compose.networks.silenemc.name === '${SILENEMC_NETWORK:-silenemc_default}',
  'le réseau de SileneMC est externe, son nom vient de SILENEMC_NETWORK', compose.networks?.silenemc);
verifier(compose.networks?.silooo?.name === 'silooo', 'le réseau du projet s\'appelle silooo (deploy.sh y lance le test de fumée)', compose.networks?.silooo);
verifier(compose.volumes?.['bds-worlds']?.name === 'silooo-bds-worlds', 'le volume du monde s\'appelle silooo-bds-worlds (sauvegarde.sh l\'archive)', compose.volumes?.['bds-worlds']);

// ------------------------------------------------------------------ les variables
console.log('Variables : compose, start.sh, fichier d\'exemple');

// Toutes les chaînes du compose (les commentaires n'y sont pas) : « ${NOM}», « ${NOM:-défaut} », « ${NOM:?message} ».
const chaines = [];
(function parcourir(valeur) {
  if (typeof valeur === 'string') chaines.push(valeur);
  else if (Array.isArray(valeur)) valeur.forEach(parcourir);
  else if (valeur && typeof valeur === 'object') Object.entries(valeur).forEach(([cle, v]) => { chaines.push(cle); parcourir(v); });
})(compose);
const variablesCompose = new Map(); // nom -> { defauts: Set, obligatoire: bool }
for (const chaine of chaines) {
  for (const m of chaine.matchAll(/\$\{([A-Z][A-Z0-9_]*)(?::([-?])([^}]*))?\}/g)) {
    const entree = variablesCompose.get(m[1]) ?? { defauts: new Set(), obligatoire: false, sansDefaut: false };
    if (m[2] === '-') entree.defauts.add(m[3]);
    else if (m[2] === '?') entree.obligatoire = true;
    else entree.sansDefaut = true;
    variablesCompose.set(m[1], entree);
  }
}
const sansRegle = [...variablesCompose].filter(([, v]) => v.sansDefaut).map(([nom]) => nom);
verifier(sansRegle.length === 0, 'chaque ${VARIABLE} du compose a un défaut (:-) ou est obligatoire (:?)', sansRegle);
verifier(variablesCompose.get('SILOOO_TAG')?.obligatoire === true, 'SILOOO_TAG est obligatoire (deploy.sh le pose)', variablesCompose.get('SILOOO_TAG'));

// Le fichier d'exemple : lignes NOM=valeur actives ou en commentaire.
const exemple = lire('deploy/exemple.env.txt').split('\n');
const actives = new Map(); // nom -> valeur
const commentees = new Map();
exemple.forEach((ligne, i) => {
  const active = /^([A-Z][A-Z0-9_]*)=(.*)$/.exec(ligne);
  // Une affectation mise en commentaire tient en une ligne « # NOM=valeur » sans espace : le reste est de la prose.
  const commentee = /^#\s?([A-Z][A-Z0-9_]*)=(\S*)$/.exec(ligne);
  const precedente = exemple[i - 1] ?? '';
  if (active) {
    actives.set(active[1], active[2]);
    if (!precedente.startsWith('#')) ko(`exemple.env.txt ligne ${i + 1} : ${active[1]} doit suivre une ligne de commentaire qui l'explique`);
    if (/["'\s]/.test(active[2])) ko(`exemple.env.txt ligne ${i + 1} : ${active[1]} ne doit avoir ni guillemet ni espace dans sa valeur`, active[2]);
    if (/#/.test(active[2])) ko(`exemple.env.txt ligne ${i + 1} : ${active[1]} ne doit pas contenir #`, active[2]);
  } else if (commentee) {
    commentees.set(commentee[1], commentee[2]);
  }
});
const connues = new Set([...actives.keys(), ...commentees.keys()]);
const malFormees = exemple.filter((l) => /^[A-Za-z_][A-Za-z0-9_]*\s*=/.test(l) && !/^[A-Z][A-Z0-9_]*=/.test(l));
verifier(malFormees.length === 0, 'exemple.env.txt : NOM=valeur en majuscules, sans espace avant le signe égal', malFormees);

for (const nom of ['SILOOO_DB_URL', 'SILOOO_DB_USER', 'SILOOO_DB_PASSWORD', 'SILOOO_API_KEY']) {
  verifier(actives.has(nom), `exemple.env.txt pose ${nom} (obligatoire pour silooo-core)`);
}
verifier(!connues.has('SILOOO_TAG'), 'exemple.env.txt ne pose pas SILOOO_TAG (deploy.sh le pose à chaque déploiement)');
const manquantes = [...variablesCompose.keys()].filter((nom) => nom !== 'SILOOO_TAG' && !connues.has(nom));
verifier(manquantes.length === 0, 'chaque variable du compose est dans exemple.env.txt (active ou en commentaire)', manquantes);

// La configuration de silooo-core : toute variable que Config.kt lit doit être dans l'exemple, sauf deux que l'image règle.
const configKotlin = lire('core/src/main/kotlin/com/silooo/core/Config.kt');
const variablesCore = [...new Set([...configKotlin.matchAll(/"(SILOOO_[A-Z0-9_]+)"/g)].map((m) => m[1]))];
const reglees = new Set(['SILOOO_PORT', 'SILOOO_DEFINITIONS_DIR']); // port 8080 du contrat, et définitions embarquées dans l'image
// Le site public (docs/site-passation.md) : des variables facultatives, avec un défaut sûr (attente, 8081). deploy/ est à la
// direction du jeu : elle les ajoute à exemple.env.txt (docs/site-passation.md, « Pour la direction du jeu »), puis retire
// chaque nom de cette liste. Une variable du site qui n'est ni ici ni dans exemple.env.txt fait échouer le test.
for (const nom of ['SILOOO_SITE_PORT', 'SILOOO_SITE_MODE', 'SILOOO_SITE_URL', 'SILOOO_SITE_URL_DISCORD', 'SILOOO_SITE_ADRESSE_JEU', 'SILOOO_SITE_PORT_JEU', 'SILOOO_SITE_URL_BOUTIQUE', 'SILOOO_SITE_SITES_DE_VOTE', 'SILOOO_SITE_ADMISSION', 'SILOOO_DISCORD_CLIENT_ID', 'SILOOO_DISCORD_CLIENT_SECRET', 'SILOOO_SITE_TRUSTED_PROXY', 'SILOOO_PAYNOW_WEBHOOK_SECRET']) reglees.add(nom);
const oubliees = variablesCore.filter((nom) => !reglees.has(nom) && !connues.has(nom));
verifier(variablesCore.length >= 5 && oubliees.length === 0, 'chaque variable lue par Config.kt est dans exemple.env.txt (sauf SILOOO_PORT, SILOOO_DEFINITIONS_DIR et les variables du site, en attente dans deploy/)', oubliees);

// start.sh : toute variable BDS_* qu'il lit est passée par le compose, et les défauts concordent partout.
const startSh = lire('deploy/bds/start.sh');
// Réglages pour les essais, ou posés par le Dockerfile (BDS_VERSION, BDS_USER_AGENT) : pas pour le compose.
const internes = new Set(['BDS_DIR', 'BDS_GABARIT', 'BDS_RACINE', 'BDS_DONNEES', 'BDS_SOMMES_SHA256', 'BDS_SOMME_MD5', 'BDS_URL', 'BDS_VERSION', 'BDS_USER_AGENT']);
const lues = [...new Set([...startSh.matchAll(/\$\{(BDS_[A-Z_]+|SILOOO_CORE_URL|SILOOO_API_KEY)[:}]/g)].map((m) => m[1]))].filter((n) => !internes.has(n));
const passees = new Set(Object.keys(bds.environment ?? {}));
const nonPassees = lues.filter((nom) => !passees.has(nom));
verifier(lues.length >= 8 && nonPassees.length === 0, 'chaque variable lue par start.sh est passée par le compose à silooo-bds', nonPassees);

const defautStart = (nom) => new RegExp(`\\$\\{${nom}:-([^}]*)\\}`).exec(startSh)?.[1];
const defautCompose = (nom) => [...(variablesCompose.get(nom)?.defauts ?? [])][0];
for (const nom of ['BDS_PORT', 'BDS_UDP_PORTS']) {
  const valeurs = { start: defautStart(nom), compose: defautCompose(nom), exemple: actives.get(nom) };
  verifier(valeurs.start !== undefined && valeurs.start === valeurs.compose && valeurs.compose === valeurs.exemple,
    `${nom} : même valeur dans start.sh, compose.yml et exemple.env.txt`, JSON.stringify(valeurs));
}
verifier(defautCompose('BDS_UDP_PORTS_PUBLIES') === defautCompose('BDS_UDP_PORTS') && actives.get('BDS_UDP_PORTS_PUBLIES') === actives.get('BDS_UDP_PORTS'),
  'BDS_UDP_PORTS_PUBLIES : même plage que BDS_UDP_PORTS par défaut et dans l\'exemple', JSON.stringify([defautCompose('BDS_UDP_PORTS_PUBLIES'), actives.get('BDS_UDP_PORTS_PUBLIES')]));
for (const [nom, attendu] of [['BDS_MAX_PLAYERS', defautStart('BDS_MAX_PLAYERS')], ['BDS_LEVEL_NAME', defautStart('BDS_LEVEL_NAME')], ['BDS_ALLOW_LIST', defautStart('BDS_ALLOW_LIST')], ['BDS_SERVER_NAME', defautStart('BDS_SERVER_NAME')]]) {
  verifier(actives.get(nom) === attendu, `${nom} : l'exemple reprend le défaut de start.sh`, JSON.stringify([actives.get(nom), attendu]));
}

// Le Dockerfile expose les ports par défaut, et la plage d'ADR 0004.
const dockerfile = lire('deploy/bds/Dockerfile');
const expose = /^EXPOSE\s+(\S+)\s+(\S+)/m.exec(dockerfile);
verifier(expose && expose[1] === `${actives.get('BDS_PORT')}/tcp` && expose[2] === `${actives.get('BDS_UDP_PORTS')}/udp`,
  'le Dockerfile expose BDS_PORT en TCP et BDS_UDP_PORTS en UDP', expose?.slice(1).join(' '));
verifier(/^FROM node:[0-9.]+-[a-z0-9-]+@sha256:[0-9a-f]{64} AS addon$/m.test(dockerfile), 'étape addon : tag de Node et somme de l\'image épinglés');
verifier(/^FROM ubuntu:24\.04@sha256:[0-9a-f]{64}$/m.test(dockerfile), 'étape finale : ubuntu:24.04 épinglée par sa somme');
verifier(/^USER silooo$/m.test(dockerfile) && /--uid 10001/.test(dockerfile), 'le conteneur ne tourne pas en root (uid 10001)');
verifier(/^ENTRYPOINT \["\/opt\/silooo\/start\.sh"\]$/m.test(dockerfile), 'l\'entrée est /opt/silooo/start.sh');
// Décision 26 : le serveur Bedrock n'est pas dans l'image. Kr pose le zip, start.sh le contrôle au démarrage.
const instructions = dockerfile.split('\n').filter((l) => !l.trim().startsWith('#')).join('\n');
verifier(!/minecraft\.net|bedrockdedicatedserver|bedrock-server-.*\.zip/.test(instructions), 'le Dockerfile ne télécharge pas BDS (décision 26)');
verifier(!/^ENV[^\n]*LD_LIBRARY_PATH/m.test(instructions), 'pas de LD_LIBRARY_PATH dans l\'image : start.sh le pose dans le dossier de BDS');
verifier(/apt-get install[^\n]*\bunzip\b/.test(instructions.replace(/\\\n/g, ' ')) && /\bjq\b/.test(instructions) && /libcurl4t64/.test(instructions), 'l\'image porte unzip, jq et libcurl4t64 (le décompresseur de start.sh, son jq, et la bibliothèque dont BDS a besoin)');
const volumesDockerfile = /^VOLUME\s+\[(.*)\]\s*$/m.exec(dockerfile)?.[1].split(',').map((v) => v.trim().replace(/^"|"$/g, ''));
verifier(egal(volumesDockerfile, ['/data/worlds', '/data/config', '/data/bds']), 'le Dockerfile déclare les volumes /data/worlds, /data/config et /data/bds', volumesDockerfile);
verifier(/COPY --from=addon [^\n]*\/src\/addon\/dist\/pack \/opt\/silooo\/pack\/$/m.test(dockerfile), 'le pack construit dans l\'étape addon est copié dans /opt/silooo/pack');
verifier(dockerfile.split('\n').some((l) => l.startsWith('COPY ') && l.endsWith(' resource-pack /opt/silooo/resource-pack/')), 'le pack de ressources du dépôt est copié dans /opt/silooo/resource-pack');
verifier(startSh.includes('$DIR_SCRIPT/resource-pack}') && existsSync(join(racine, 'resource-pack/manifest.json')), 'start.sh cherche le pack de ressources dans /opt/silooo/resource-pack, à côté de lui, et le dépôt a un resource-pack/manifest.json');
verifier(/^COPY [^\n]*deploy\/bds\/bds\.sha256 deploy\/bds\/bds\.md5 \/opt\/silooo\/$/m.test(dockerfile), 'bds.sha256 et bds.md5 sont copiés à côté de start.sh');
const uaDockerfile = /^ARG BDS_USER_AGENT="([^"]+)"$/m.exec(dockerfile)?.[1];
verifier(uaDockerfile && uaDockerfile.includes('silooo') && !/mozilla|chrome|safari/i.test(uaDockerfile), 'le User-Agent de téléchargement dit qui il est (pas celui d\'un navigateur)', uaDockerfile);
verifier(uaDockerfile === /\$\{BDS_USER_AGENT:-([^}]*)\}/.exec(startSh)?.[1], 'start.sh a le même User-Agent par défaut que le Dockerfile', JSON.stringify([uaDockerfile, /\$\{BDS_USER_AGENT:-([^}]*)\}/.exec(startSh)?.[1]]));
verifier(/--max-time/.test(startSh) && /--user-agent/.test(startSh), 'start.sh télécharge avec un délai et un User-Agent explicite');
const instructionsSurUneLigne = instructions.replace(/\s*\\\n\s*/g, ' ');
verifier(/^ENV BDS_VERSION=\$\{BDS_VERSION\} BDS_USER_AGENT=\$\{BDS_USER_AGENT\}$/m.test(instructionsSurUneLigne), 'le Dockerfile passe BDS_VERSION et BDS_USER_AGENT à start.sh');

// bds.sha256 : le nom du fichier suit ARG BDS_VERSION, la somme est du bon format.
const version = /^ARG BDS_VERSION=(\S+)$/m.exec(dockerfile)?.[1];
const ligneSomme = lire('deploy/bds/bds.sha256').split('\n').filter((l) => l.trim() && !l.trim().startsWith('#'));
const somme = /^([0-9a-f]{64})  (\S+)$/.exec(ligneSomme[0] ?? '');
verifier(ligneSomme.length === 1 && somme !== null, 'bds.sha256 : une seule ligne « somme  fichier » hors commentaires', ligneSomme);
verifier(somme && somme[2] === `bedrock-server-${version}.zip`, 'bds.sha256 : le nom du zip suit ARG BDS_VERSION du Dockerfile', JSON.stringify([somme?.[2], version]));
// bds.md5 : le Content-MD5 de Mojang, repli de start.sh tant que la somme SHA-256 n'est pas épinglée.
const ligneMd5 = lire('deploy/bds/bds.md5').split('\n').filter((l) => l.trim() && !l.trim().startsWith('#'));
const md5 = /^([0-9a-f]{32})  (\S+)$/.exec(ligneMd5[0] ?? '');
verifier(ligneMd5.length === 1 && md5 !== null && md5[1] === '4a23794a882b008d3f0a5fde8b338f55', 'bds.md5 : une ligne « somme  fichier » avec le Content-MD5 annoncé par minecraft.net', ligneMd5);
verifier(md5 && md5[2] === `bedrock-server-${version}.zip`, 'bds.md5 : le nom du zip suit ARG BDS_VERSION du Dockerfile', JSON.stringify([md5?.[2], version]));
verifier(lire('deploy/bds/bds.sha256').includes('Kr : télécharge le zip dans un navigateur, `sha256sum`, écris la somme ici'), 'bds.sha256 dit à Kr de télécharger le zip dans un navigateur et d\'écrire la somme');
verifier(/BDS_RACINE="\$\{BDS_RACINE:-\/data\/bds\}"/.test(startSh), 'start.sh cherche le zip dans /data/bds, le point de montage du compose');
verifier(/SILOOO_BDS_DIR="\$\{SILOOO_BDS_DIR:-\/var\/www\/silooo\/bds\}"/.test(lire('deploy/scripts/commun.sh')), 'commun.sh (deploy.sh) a le même dossier par défaut que le compose pour SILOOO_BDS_DIR');
verifier(commentees.get('SILOOO_BDS_DIR') === '/var/www/silooo/bds', 'exemple.env.txt : SILOOO_BDS_DIR a le même défaut que le compose', commentees.get('SILOOO_BDS_DIR'));

// Le .gitignore garde le fichier d'environnement, le tag courant et les sauvegardes hors du dépôt.
const ignore = lire('.gitignore').split('\n').map((l) => l.trim());
for (const motif of ['.env', 'deploy/.tag-courant', 'sauvegardes/']) {
  verifier(ignore.includes(motif), `.gitignore contient ${motif}`);
}
verifier(existsSync(join(racine, 'deploy/bds/Dockerfile.dockerignore')), 'Dockerfile.dockerignore est à côté du Dockerfile');
const ignoreDocker = lire('deploy/bds/Dockerfile.dockerignore').split('\n').map((l) => l.trim());
verifier(ignoreDocker.includes('**/.env') && ignoreDocker.includes('**/*.key') && ignoreDocker.includes('**/*.pem'), 'Dockerfile.dockerignore écarte les fichiers de secrets du contexte');
verifier(ignoreDocker.includes('!resource-pack') && ignoreDocker.indexOf('*') < ignoreDocker.indexOf('!resource-pack'), 'Dockerfile.dockerignore laisse passer resource-pack/ après avoir tout exclu');

console.log();
console.log(`compose : ${reussis} réussi(s), ${echecs} en échec.`);
process.exit(echecs > 0 ? 1 : 0);
