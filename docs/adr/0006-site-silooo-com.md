# 0006 : Le site silooo.com vit dans silooo-core, sur son propre port, en deux modes

Date : 5 novembre 2026. Statut : Proposée, à valider par Kr.

## Contexte

Le game design demande un site public ([site.md](../site.md)) : le jeu en une phrase, comment rejoindre, ce qui se
vend et à quel prix avant la connexion, un wiki, des classements hebdomadaires, une fiche par entreprise, la page
de vote, les mentions. La décision 37 de Kr ([passation.md](../passation.md)) fixe le cadre : le code du site vit
dans `core/.../site/` et `core/src/main/resources/site/` ; le site écrit dans `shop_deliveries` les commandes du
webhook PayNow, que la phase 5 du jeu crédite ; le site dit quelle table ou quel champ rend un joueur « admis »,
et le jeu refuse les autres dans `join` ; la direction du jeu déploie silooo-core, site compris, quand la
passation du site dit « Prêt à mettre en ligne ». Les contraintes : le VPS manque de mémoire ; `/v1` ne doit
jamais être joignable d'Internet ([architecture.md](../architecture.md)) ; une seule source par donnée
(`definitions/`) ; le seul point d'entrée web du VPS est le Caddy de SileneMC, dans `/var/www/minecraft`, qu'une
session Silooo ne touche jamais ; la marque est indépendante et en français seulement.

## Décision

- **Le site est un module de silooo-core**, paquet `com.silooo.core.site`, ressources dans
  `core/src/main/resources/site/`. Une seule JVM, pas de conteneur de plus. Écartés : un site statique à part dans
  un conteneur (un service de plus sur un VPS serré, et un second lecteur du schéma pour les classements) ; un
  espace dans le site de SileneMC (la marque est à part, et ce site garde son seul rôle, la boutique qui écrit
  dans `shop_deliveries`, [ADR 0003](0003-base-partagee.md)).
- **Son propre port.** Le serveur Ktor ouvre un second connecteur, `SILOOO_SITE_PORT` (8081 par défaut), et les
  routes du site n'écoutent que lui (sélecteur de port du routage) ; `/v1` reste sur `SILOOO_PORT`. Le Caddy de
  SileneMC mandate `silooo.com` vers `silooo-core:8081` sur le réseau `silenemc` que silooo-core rejoint déjà ;
  rien n'est publié sur l'hôte, et l'API ne se voit pas d'Internet.
- **Deux modes**, lus dans `SILOOO_SITE_MODE` : `attente` (défaut : la page d'attente, les mentions, et
  l'inscription des pionniers quand elle existe) et `ouvert` (le site entier). Une autre valeur arrête le service
  au démarrage, comme une variable manquante. Jamais `ouvert` dans le dépôt, la CI ou un déploiement par une
  session : c'est Kr qui le pose dans le fichier d'environnement.
- **Rendu côté serveur en Kotlin**, par le DSL HTML de Ktor (`ktor-server-html-builder`, version épinglée dans
  `libs.versions.toml`) ; les textes longs (wiki, mises à jour, mentions) en Markdown dans les ressources, rendus
  par commonmark-java au démarrage. Pas de moteur de gabarits, pas de JavaScript obligatoire : les pages se
  lisent sans script. Les données vivantes (joueurs en ligne, classements, fiches) se lisent dans la base par
  les accès du service, avec un cache d'une minute. Aucune route JSON publique.
- **Ce que le site écrit** : les lignes de `shop_deliveries` du webhook PayNow (signé par
  `SILOOO_PAYNOW_WEBHOOK_SECRET`), et la table des inscriptions des pionniers et de l'admission, par une migration
  prise au moment de la fusion. Jamais un solde ni un état du jeu : le jeu décide ([ADR 0002](0002-service-autoritaire.md)).
- **Le design est Silooo Atelier** adapté au web : les jetons de `design/tokens.css`, les textures 9-slice par
  `border-image`, les trois polices, les mots de la charte. Textures et icônes sont copiées de `design/` dans les
  ressources et un test les compare octet pour octet ; les polices (licence OFL, paquets Fontsource) sont
  commises avec leur licence. Rien n'est chargé depuis un autre hôte, pas de cookie, pas de traceur.
- **Les chiffres viennent de `definitions/`** par les définitions déjà chargées par le service ; la page des mises
  à jour s'écrit à la main, une entrée par tag déployé.

## Conséquences

- `core/` gagne un paquet `site/`, deux dépendances (DSL HTML, commonmark), un connecteur, trois variables
  (`SILOOO_SITE_PORT`, `SILOOO_SITE_MODE`, plus tard `SILOOO_PAYNOW_WEBHOOK_SECRET`), des tests Ktor sans Docker et
  des tests SQL ; `core/README.md` décrit le site. Le compose ne change pas : `env_file` passe déjà tout
  l'environnement à silooo-core.
- `tools/anti_ia.py` lit les textes du site (ressources Markdown, littéraux du paquet `site/`).
- Kr : le DNS de `silooo.com`, le bloc Caddy (mandat vers `silooo-core:8081`, TLS), l'identité légale des
  mentions, l'adresse de la boutique, les sites de vote, l'application Discord, le secret du webhook PayNow, puis
  le passage à `ouvert` à l'ouverture.
- Coût : silooo-core sert aussi des pages ; le cache borne le poids des classements, et la limitation du débit
  revient au Caddy. Remplace la première version de cet ADR (site statique à part), écrite le même jour avant la
  lecture de la décision 37.
