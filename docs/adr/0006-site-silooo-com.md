# 0006 : Le site silooo.com, statique, servi par un conteneur du projet silooo

Date : 5 novembre 2026. Statut : Proposée, à valider par Kr.

## Contexte

Le game design demande un site public ([site.md](../site.md)) : le jeu en une phrase, comment rejoindre, ce qui se
vend et à quel prix avant la connexion, un wiki, des classements hebdomadaires, une fiche par entreprise, la page
de vote, les mentions. Quatre questions restaient ouvertes : où vit le site, d'où viennent les données vivantes,
quel design, comment s'écrit la page des mises à jour. Les contraintes : le VPS manque de mémoire
([plan-construction.md](../plan-construction.md)) ; silooo-core ne répond que sur le réseau Docker, jamais sur
Internet ([architecture.md](../architecture.md)) ; une seule source par donnée (`definitions/`) ; le seul point
d'entrée web du VPS est le Caddy de SileneMC, dont la configuration est dans `/var/www/minecraft`, qu'une session
Silooo ne touche jamais ; la marque est indépendante et en français seulement.

## Décision

- **Un site à part, dans ce dépôt** (`site/`), servi sur `silooo.com`, pas un espace dans le site de SileneMC :
  la marque est indépendante, et le site de SileneMC garde son seul rôle, la boutique qui écrit dans
  `shop_deliveries` ([ADR 0003](0003-base-partagee.md)).
- **Statique.** Un script Node (`site/construire.mjs`, Node 22, sans framework) rend du HTML et du CSS à partir de
  contenus Markdown, de gabarits, de `definitions/` et de `design/`. Un peu de JavaScript, sans dépendance, pour
  les seules données vivantes. La CI construit l'image `ghcr.io/e-quantum/silooo-site` (serveur de fichiers Caddy),
  service `silooo-site` du compose, quelques dizaines de Mo de mémoire, réseaux `silooo` et `silenemc`, aucun port
  publié par défaut : Kr ajoute au Caddy de SileneMC un bloc `silooo.com` qui mandate vers `silooo-site:80`.
- **Deux modes**, lus dans `SILOOO_SITE_MODE` : `attente` (défaut partout : la page d'attente et les mentions) et
  `ouvert` (le site entier). L'image contient les deux rendus ; le conteneur sert celui du mode. Le mode se pose
  dans le fichier d'environnement de Kr ; jamais `ouvert` dans le dépôt, la CI ou un déploiement par une session.
- **Les données vivantes par des routes publiques de silooo-core**, sous `/public/v1/`, sans `X-Silooo-Key`, en
  lecture seule, déclarées dans l'OpenAPI avec `security: []`, mises en cache une minute dans le service. Le Caddy
  du site ne mandate que `/api/*` vers `http://silooo-core:8080/public/`. silooo-core reste hors d'Internet ; seul ce
  petit espace en lecture est joignable, à travers le site. Elles ne montrent que ce qu'un joueur voit déjà en jeu :
  gamertag, nom d'entreprise, époque, étoiles, Lingots gagnés dans la semaine ; jamais de XUID ni de solde.
  Écartées : un compte de base en lecture seule pour le site (un second lecteur du schéma, et seul le service
  connaît les règles, par exemple la semaine du classement) ; un site dynamique (une seconde pile à faire tourner).
- **Le design est Silooo Atelier** adapté au web : les jetons de `design/tokens.css`, les textures 9-slice par
  `border-image`, les trois polices, les mots de la charte. Ce que le web ajoute (textes longs en police de corps,
  tailles lisibles sur téléphone) se décide à l'étape S1 et s'écrit dans [site.md](../site.md).
- **La page des mises à jour s'écrit à la main**, une entrée par tag déployé, dans `site/contenu/mises-a-jour/`.
  Écartée : la tirer des tags git, dont les messages ne sont pas écrits pour les joueurs.
- **Les chiffres viennent de `definitions/`** à la construction (packs et grades de `boutique.json`, usines de
  `usines.json`, associés de `monde.json`). L'adresse du serveur vit dans `site/site.json` ; le port affiché est
  comparé par un test à la valeur par défaut de `deploy/compose.yml`.

## Conséquences

- Un dossier `site/`, une étape dans `tools/verifier.sh`, `site/` dans la liste de `tools/anti_ia.py`, un job
  d'image dans la CI, un service dans le compose, `deploy.sh` qui le démarre, ses tests dans `tools/tests/`.
- L'OpenAPI gagne un espace `/public/v1/` de trois opérations ; le test de contrat apprend `security: []`.
- Kr : le DNS de `silooo.com`, le bloc Caddy (mandat et TLS), l'identité légale des mentions, l'adresse de la
  boutique, les sites de vote, puis le passage à `ouvert` à l'ouverture.
- Coût : un quatrième conteneur ; les routes publiques doivent rester peu coûteuses (cache) puisqu'elles se
  joignent sans clé ; la limitation du débit revient au Caddy.
- À vérifier au premier déploiement : que le Caddy de SileneMC atteint `silooo-site` sur le réseau `silenemc`, la
  mémoire réelle du conteneur, le rendu des polices et des textures sur téléphone.
