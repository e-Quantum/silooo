# Le Discord de Silooo

Le cadrage de Kr (5 novembre 2026), condensé. La source reste le document « Discord Silooo — Cadrage » et le plan
« Discord Silooo — Plan de construction et critique » ; les maquettes sont sur la page « Discord Silooo » du canevas
Silooo — Maquettes. L'état du chantier, ses étapes et ce qui revient à Kr sont dans
[discord-passation.md](discord-passation.md). Les corrections de la critique du plan priment sur le cadrage : elles
sont intégrées ici, et rappelées dans la section « Ce que la critique corrige ».

## En une page

Le Discord est le prolongement du jeu : on y voit le serveur vivre, on y parle avec ceux qui jouent, on y montre son
entreprise. On n'y joue pas : encaisser, monter une usine, faire un prestige restent dans Minecraft. Le bot, Oooscar,
lit tout dans silooo-core, la même source que le jeu et le site, et il écrit sans emoji, à la première personne, en
phrases courtes.

Avant l'ouverture, c'est le QG du chantier : on y suit la construction, on y décide une partie du jeu par les sondages,
et les inscrits y sont admis à la bêta. Le QG ouvre avec la page d'attente du site ; la partie reliée au jeu suit la
bêta. Le serveur s'appelle « Silooo · Minecraft Bedrock » ; ses membres sont des entrepreneurs.

Trois sessions travaillent sur le même `main` sans se parler : la direction du jeu (déploiement, contrat, add-on,
cosmétiques en jeu, preuve de liaison en jeu), la direction du site (la connexion Discord du site,
`site_registrations`, l'admission) et la session Discord (le bot, le plan du serveur, les visuels, ces deux fichiers).
Kr garde l'application Discord et son jeton, les intentions, les boosts, la propriété du serveur et les validations.

## Les décisions de Kr

| Sujet | Décision |
| --- | --- |
| Ambition | Un Discord vivant sans y jouer : salon en direct, annonces, cartes, rôles tirés du jeu, rappels privés |
| Tchat | Les événements du jeu, et le tchat relié dans les deux sens |
| Staff | Admissions en bêta et annonces depuis Discord ; le reste dans l'admin du site |
| Mise à l'écart | Pas d'Activité Discord, pas de gestion d'entreprise depuis Discord, pas de console |
| Arrivée | Oooscar mentionne le nouveau quelques secondes après son arrivée et lui envoie un tuto en privé |
| Rendez-vous | Coulisses du chantier, sondages qui décident du jeu, concours, giveaways gratuits |
| Croissance | Parrainage dans le jeu, badge SILO, Soutiens, concours des créateurs ; rien pour une invitation |
| Pseudo | « Gamertag · P3 » pour les comptes reliés, tenu par le bot ; laiton à partir du prestige 5 |
| Vocal | « Crée ton vocal », et un bureau par entreprise |
| Aide | Un forum pour les questions de jeu, des tickets privés par menu pour le reste |
| XP de tchat | Apprenti, Ouvrier, Contremaître, Ingénieur, sans récompense en jeu ; classement remis à zéro chaque semaine |
| Modération | Kr, le bot et l'AutoMod au départ ; des Aides au-delà de 500 membres |
| Promotion | Par Hubyy seulement, avec un plan à travailler ; rien sur SileneMC |
| Couleur | Le ciel, `#8fb8d6`, seule couleur de la marque sur Discord |
| Noms de salons | Un emoji devant chaque salon ; exception au design system, pour les noms seulement |
| Bannière | Une île dans le ciel, des ouvriers au travail, Silooo en grandes lettres (« ooo » en rouge), Oooscar ; jour et nuit |
| Icône et avatar | Le logo et Oooscar, chacun avec sa version GIF (le toit qui glisse, Oooscar qui se balance) |

Tranchés par Kr le 5 novembre contre la critique : le tuto part en message privé à chaque arrivant, avec le bouton
« Mon tuto » en secours ; les giveaways gardent un peu de tout, Gemmes comprises, avec un règlement ; l'XP et le
classement de la semaine sont là dès l'ouverture du QG, un message compté par minute. Le rapport du lundi suit les
messages privés qui échouent et les signalements, pour revenir sur ces choix s'il le faut.

## Ce que la critique corrige

| Point | La correction | Étape |
| --- | --- | --- |
| Les plus jeunes | Les bureaux restent visibles, jamais verrouillés, le staff peut toujours y entrer. Le relais refuse les demandes de contact (« ajoute-moi », « en privé », pseudos de réseaux, numéros, adresses). En jeu, un réglage « Voir le tchat Discord » et le signalement, demandés à la direction du jeu | D3, D4 |
| La preuve du gamertag | Les pseudos, les rôles du jeu, les lots et les votes ne suivent qu'une liaison prouvée par le code montré en jeu | D3 |
| Un serveur vide | Le QG ouvre avec une douzaine de salons ; les autres viennent avec la bêta. Seuils des spoils à environ 40 % des membres. Avant la bêta et 200 membres, un concours par mois | D2 |
| Le bot et le jeu | `SILOOO_DISCORD_ACTIF`, une file bornée pour les images, un budget mémoire mesuré, un arrêt du bot qui n'arrête pas le jeu. Au-delà du budget, le bot devient un processus à part (ADR) | D0 |
| Deux styles | La règle des deux familles d'images (section « Les rendus ») | D1 |
| Le contrôle à distance | Il n'agit sur Discord que si `CLAUDE.md` contient l'exception Discord écrite par Kr ; sinon, la marche à suivre va dans « À faire par Kr » | Toutes |
| Les boosts | La version sans boost d'abord (icône, image d'accueil, cartes du guide) ; aucune annonce ne réclame de boost | D1, D2 |
| Les récompenses | Rien ne s'annonce sur Discord avant que la direction du jeu ait livré la récompense en jeu | D5 |
| La charge de Kr | Les demandes à Kr sont groupées en deux rendez-vous de trente minutes par semaine, avec une liste prête | Toutes |
| La mesure | Chaque lundi, un rapport dans `#📒・journal-du-bot` : arrivées, accueil terminé, comptes reliés sous 7 jours, membres revenus après 7 jours, messages par jour, d'où viennent les arrivants | D2 |

## Le plan du serveur

Le plan vit dans `core/src/main/resources/discord/plan-serveur.json`, validé par son schéma : catégories, salons (nom
avec emoji, type, sujet, permissions, phase où il apparaît : `attente`, `beta` ou `ouvert`) et rôles (couleur, ordre).
Au démarrage, le bot crée ce qui manque, ne supprime jamais rien, et note les écarts dans `#📒・journal-du-bot` ; un
mode à blanc écrit l'écart sans rien changer. Les noms sont à valider par Kr.

- **Phase `attente`, le QG** : `#👋・bienvenue`, `#📢・annonces`, `#🧭・rejoindre`, `#🏗️・coulisses`, `#📊・sondages`,
  `#🎁・giveaways`, `#💬・discussion`, `#🏛️・panthéon`, `#⭐・meilleurs-moments`, `#🔢・comptage`, `#🛗・monte-charge`,
  `#❓・aide` (forum), `#🎫・tickets`, un vocal « Crée ton vocal », et les salons du staff (`#📒・journal-du-bot`,
  `#✅・admissions`, `#🚩・signalements`).
- **Phase `beta`** : la catégorie du jeu (`#🏭・le-silo`, `#🎮・tchat-du-jeu`, `#📈・records`, `#🗳️・vote`), `#📸・captures`,
  `#🤝・entreprises`, `#🏙️・villes`, `#🆕・patch-notes`, `#💡・suggestions`, `#🏆・concours`, `#🎬・créateurs`, `#💎・soutiens`.
- Les salons du bot sont en lecture seule ; les échanges se font dans les forums, un sujet par question, suggestion ou
  recherche d'associés. Les catégories sont en capitales, sans emoji.
- Trois vocaux verrouillés en tête, renommés par le bot deux fois par dix minutes au plus (limite de Discord) :
  « 🌐 play.silooo.com · 19142 », « 🎮 Console : ami HubyOnMC », « 👥 N en ligne ».
- **Les vocaux** : « Crée ton vocal » ouvre un salon au nom du membre, qui disparaît quand il se vide. `/bureau`, ou
  le bouton de la carte d'entreprise, ouvre le bureau de l'entreprise : visible de tous, jamais verrouillé, le staff y
  entre toujours ; il disparaît une fois vide.

## La direction artistique

La méthode de Deadland et de Rinaorc, avec Silooo Atelier : une seule couleur partout, un mot du thème pour les
membres, le logotype sur une scène, les infos toujours en haut, un emoji devant chaque salon, des emojis du serveur
tirés du jeu.

- **Les couleurs des rôles** : Oooscar et Entrepreneur en ciel (`#8fb8d6`) ; Staff et Modération en rouge émaillé clair
  (`#e0614f`) ; Soutien en rose, la couleur des boosts ; Aide en vert émaillé clair (`#4f9a74`) ; Créateur en bleu
  émaillé clair (`#5c84b8`) ; Pionnier en crème (`#efe3c6`) ; laiton au prestige 5. Aucune autre couleur : le
  vérificateur le contrôle.
- **Le logotype** : « Sil » en crème et « ooo » dans le rouge du toit, cernés de fonte.
- **Jour et nuit** : le bot change la bannière à 7 h et à 22 h, heure de Paris. Avant 7 boosts, il change l'image du
  message d'accueil de `#👋・bienvenue`.
- **Icône, avatar, badge** : le logo sur le ciel, Oooscar joyeux sur le même ciel ; GIF dès 2 boosts, badge SILO à
  3 boosts, bannière en haut des salons à 7, animée à 14. La version sans boost doit être belle seule.
- **Le statut d'Oooscar** alterne « Joue à play.silooo.com » et « Regarde N entrepreneurs », une minute chacun.
- **Les emojis du serveur** : `:silo:`, `:donar:`, `:lingot:`, `:gemme:`, `:usine:`, `:prestige:`, `:eclair:`, `:ville:`
  et les cinq têtes d'Oooscar (`:oooscar_joie:`, `_fier`, `_oups`, `_panique`, `_dodo`), aussi en stickers. `:silo:`
  compte les réactions des coulisses.
- **Les cartes en image** sont réservées aux grands moments : le salon en direct, `/entreprise`, les prestiges, les
  records, les résultats. Le reste est en texte. Les titres sont sans emoji ; les textes suivent « Écrire sans slop »
  de `design/README.md`.
- **Le Guide du serveur** : une carte illustrée par rubrique (rejoindre sur console, relier son compte, règlement) ;
  les événements Discord ont une couverture sur un même gabarit. Pas de soundboard.

**Les rendus.** Les maquettes donnent la composition ; les visuels se font avec la chaîne de SileneMC (skill
`silenemc-contenu`, outils dans `Documents\minecraft\contenu-reseaux\_outils\`), avec une marque `silooo` ajoutée à
`marque.md` et à `brand_overlay` (palette de `design/tokens.json`, polices Jersey 10, Pixelify Sans et Silkscreen, le
logo, le ciel comme accent). Deux familles, deux règles : les illustrations en 3D (bannières, arrière-plan
d'invitation, couvertures, Oooscar en volume) sortent de Blender avec les textures du jeu mais à la manière de Silooo
Atelier (lumière franche, pas de flou ni de lueur, couleurs de la palette ; Oooscar reste celui du design system, cinq
expressions et pas une de plus) ; les images en pixel 2D (emojis, cartes d'Oooscar, plaques) sont tirées de `design/`,
agrandies sans lissage, et les cartes qui changent à chaque message sont dessinées par silooo-core. Chaque visuel
passe par un brief, des images de contrôle regardées, `critique.py`, une relecture à froid pour l'avatar, l'icône et
la bannière, puis l'envoi à Kr ; les exports validés vont dans `design/discord/`, les sources restent sur le PC.

## L'arrivée et les rôles

- **L'accueil guidé** de Discord pose deux questions : « Tu joues sur quoi ? » (Console, Téléphone, PC : un rôle de
  plateforme) et « Tu veux être prévenu de quoi ? » (Actus, Coulisses, Concours, Giveaways, Vote party, Concours de
  villes : un rôle de mention par réponse, le bot ne mentionne que ceux-là). Puis trois tâches : lire `#🧭・rejoindre`,
  relier son compte, montrer son île dans `#📸・captures`. Le Discord s'adresse aux 13 ans et plus ; les plus jeunes
  ont le jeu et le site.
- **Le bonjour d'Oooscar** : 5 à 10 secondes après la fin de l'accueil guidé, une mention dans `#💬・discussion`,
  courte et selon la plateforme (« Bienvenue @Pseudo, tu es le 1 204e entrepreneur. Sur console, tout est dans
  #rejoindre. », à valider). Au même moment, un seul message privé : le tuto en trois étapes pour sa plateforme, avec
  un bouton vers `/rappels`. Messages privés fermés : le bouton « Mon tuto » sous la mention l'affiche, visible de lui
  seul. Beaucoup d'arrivées : un message toutes les 30 secondes qui mentionne jusqu'à 10 nouveaux, puis une ligne
  « 14 nouveaux sont arrivés ».
- **Relier son compte** : `/lier`, ou le bouton de `#👋・bienvenue`. La preuve est le code montré en jeu (Menu, Mon
  compte, « Relier Discord », écran à la direction du jeu), valable 10 minutes, saisi sur Discord. C'est la même
  liaison que sur le site (`site_registrations`) : un compte relié l'est partout. Seule une liaison prouvée donne le
  pseudo « Gamertag · P3 », les rôles du jeu, les lots et les votes ; une inscription du site sans preuve n'en donne
  aucun. `/delier` rend son pseudo. Le bot ne peut pas renommer le propriétaire du serveur.

| Rôle | Comment on l'a |
| --- | --- |
| Console, Téléphone, PC | L'accueil guidé ; modifiable dans les réglages du serveur |
| Actus, Coulisses, Concours, Giveaways, Vote party, Concours de villes | L'accueil guidé ; seuls rôles mentionnés par le bot |
| Entrepreneur | Tous les membres |
| Joueur relié | Le bot, à la liaison prouvée ; ouvre l'écriture dans `#🎮・tchat-du-jeu` |
| Pionnier | Le bot, aux inscrits d'avant l'ouverture, une fois reliés |
| PDG, Associé, Prestige N | Suivent l'entreprise et la progression ; les paliers de prestige sont à choisir par Kr |
| Apprenti, Ouvrier, Contremaître, Ingénieur | L'XP de tchat, aux niveaux 5, 10, 20 et 30 |
| Soutien | Le bot, pendant un boost |
| Créateur | Les trois gagnants du concours des créateurs, pour le mois suivant |
| Staff, Modération, Aide | Kr, à la main ; Aide sur candidature à partir de 500 membres |

Les rôles liés de Discord (progression Silooo sur le profil) restent une option, à trancher par Kr.

## Le serveur en direct, les annonces, les rendez-vous

- **`#🏭・le-silo`** : un seul message, modifié toutes les 30 secondes, monté avec les composants récents de Discord
  (Components V2). En tête, une image dessinée par silooo-core avec les textures du design system (production du
  serveur par heure, jauge de la Vote party), le même contenu en texte dessous ; l'état (ouvert ou maintenance,
  joueurs en ligne, record du jour) ; la commande du jour, le dernier prestige, les premières places du Panthéon ;
  une ligne d'Oooscar de 6 mots au plus ; trois boutons : Rejoindre, Voter, Mon entreprise. Un incident est un seul
  message modifié à chaque étape (constaté, en cours, réparé).
- **`#📈・records`** annonce ce qui compte : un prestige (groupés par heure), le départ de la Vote party (mention du rôle,
  minuterie de 15 minutes), un record du Panthéon, la commande du jour livrée, une époque atteinte pour la première
  fois. Les étapes du chantier et les Actus du site partent dans `#📢・annonces` avec le rôle Actus. Jamais `@everyone`.
- **Les coulisses** : Kr dépose les spoils à l'avance avec `/spoil` (texte, image, seuil du suivant ; un aperçu lui
  rappelle les réactions des trois derniers). Le bot publie le n°1 avec le rôle Coulisses, met à jour chaque minute
  « Spoil n°2 à 150 réactions : 87 », compte les membres qui ont réagi avec `:silo:` (une fois chacun, sans les bots),
  publie le suivant au seuil, et attend 7 h entre 22 h et 7 h. Seuils vers 40 % des membres. Si rien n'est déposé, il
  attend le dépôt.
- **Les sondages** : `/sondage`, 2 à 5 réponses, une durée, ce que chaque réponse change en jeu. Avant l'ouverture, un
  sondage de Discord ; après, le bot, une voix par joueur relié. On met aux voix ce qui ne casse pas l'équilibre ; les
  prix et les gains restent dans `definitions/`. Le résultat devient une tâche du jeu.
- **Les concours** : un par mois avant la bêta et 200 membres, puis une semaine la plus belle île ou ville (captures
  dans le forum `#🏆・concours`, vote des reliés, une voix chacun) et la suivante un défi mesuré en jeu (classé depuis
  silooo-core, une partie des défis compte la progression). Les trois premiers reçoivent un cosmétique d'événement.
- **Les giveaways** : gratuits, sans achat, invitation ni boost. On participe d'un bouton ; le lot (cosmétiques,
  Gemmes ou Lingots, au choix de Kr) arrive en jeu sur le compte relié, ou attend la liaison. Un par mois, plus un à
  chaque grande étape. Le tirage et les participants vont dans `#📒・journal-du-bot`. Règlement publié sur le site.
- **Le calendrier** : chaque rendez-vous crée un événement Discord (fin d'un concours, résultats, fin d'un giveaway,
  live de Kr).

## Les cartes et les tableaux d'Oooscar

Oooscar écrit avec les conteneurs récents de Discord : texte, une image, des séparateurs, jusqu'à trois boutons, la
barre ciel à gauche. Un tableau qui vit est modifié sur place, jamais reposté. Aucune mention hors des rôles choisis.

| Quoi | Où | Comment |
| --- | --- | --- |
| Top réactions de la semaine | `#🏛️・panthéon` | Les dix membres qui ont donné le plus de réactions dans les salons de discussion, une par message, les bots exclus ; remis à zéro le lundi |
| Meilleurs moments | `#⭐・meilleurs-moments` | Un message au seuil de `:prestige:` est recopié avec son auteur et un lien ; ses propres réactions ne comptent pas |
| Aide du mois | `#🏛️・panthéon` | Le bouton « Résolu par » dans `#❓・aide` ; le membre qui a le plus de réponses retenues reçoit le titre en jeu |
| Le chantier en direct | `#🏗️・coulisses`, épinglé | Les étapes du chantier, une lampe par étape franchie, les mêmes que la page d'attente |
| Panthéon en direct | `#🏛️・panthéon` | Dès la bêta, un tableau par salle, modifié à chaque record |
| Mises à jour | `#🆕・patch-notes` | Chaque page « Mises à jour » du site devient une carte ; une grosse mise à jour passe aussi dans `#📢・annonces` |
| Suivi des suggestions | `#💡・suggestions` | Étiquettes lue, acceptée, prévue, faite ; une carte mensuelle « Vous l'avez proposé, c'est en jeu » |
| Résultats de sondage | `#📊・sondages` | Les barres en pixel, les voix, ce qui change en jeu |

**Les petits jeux**, dans une catégorie ANIMATION, sans XP ni points : `#🔢・comptage` (chacun écrit le nombre suivant,
jamais deux fois de suite ; Oooscar valide d'un `:donar:`, dit qui a cassé la chaîne et rappelle le record) et
`#🛗・monte-charge` (un seul message modifié sur place, un bouton « +1 étage » toutes les 5 minutes par membre ; un
membre du staff qui appuie fait redescendre ; l'image du monte-charge monte d'un cran). Les records vont au Panthéon.

## Le tchat relié

Le tchat global du jeu et `#🎮・tchat-du-jeu` ne font qu'un : chaque message passe par silooo-core, qui filtre, garde
une trace et sanctionne des deux côtés. C'est ce qui rend le relais possible sur un serveur tout public.

- Seuls les comptes reliés écrivent depuis Discord. Du texte, 200 caractères au plus, sans lien, image ni mention ;
  préfixé `[Discord]` en jeu, sous le gamertag avec l'icône de Silooo sur Discord (webhook). Un message toutes les
  3 secondes par joueur.
- Le filtre, le même des deux côtés : les mots refusés, et les demandes de contact (« ajoute-moi », « en privé »,
  pseudos de réseaux, numéros, adresses). L'AutoMod de Discord est réglé avec la même liste. Un message refusé ne
  passe pas, et son auteur le sait.
- Signaler : clic droit, « Signaler à la modération » ; le message et son contexte partent dans `#🚩・signalements`.
  Le journal garde les messages reliés 30 jours, ce que la page Confidentialité du site dit.
- Le staff masque un message, coupe le relais d'un joueur (1 heure, 24 heures, jusqu'à nouvel ordre) et suspend tout le
  relais d'un bouton. En jeu, un réglage « Voir le tchat Discord » et le signalement sont demandés à la direction du
  jeu. Côté contrat : `POST /v1/chat` pour les lignes du jeu, et un type d'événement que l'add-on affiche.

## Les commandes, les rappels, le staff

Chaque commande répond au joueur seul, avec une carte (image dans le style du jeu, le même contenu en texte dessous)
et un bouton « Montrer » qui la reposte pour tous : `/entreprise [joueur]`, `/usine <nom>` (les chiffres de
`definitions/`, comme le site), `/classement [salle]`, `/vote`, `/lier`, `/delier`, `/rappels`, `/niveau`,
`/parrainage`, `/bureau`. Le bot s'installe aussi sur le compte du joueur : `/entreprise` et `/usine` marchent en privé
et sur d'autres serveurs.

**Les rappels privés** sont tous désactivés au départ ; le joueur coche avec `/rappels` ou le menu de `#👋・bienvenue`,
et chaque message porte « Arrêter ce rappel » : Silo plein, Vote party, série de votes, commande du jour livrée,
entreprise (invitation, prestige lancé), Gemmes reçues. Trois par jour au plus, aucun de 22 h à 7 h à l'heure du
joueur, sauf les Gemmes reçues. Le tuto d'arrivée est le seul message privé non demandé.

**Le staff** agit par boutons, qui vérifient le rôle de celui qui clique : `#✅・admissions` (chaque inscription de la
page d'attente en carte, lue dans `site_registrations`, avec Admettre, Refuser, Plus tard ; Admettre pose
l'admission, donne le rôle Bêta, envoie l'invitation en privé ; sans liaison prouvée, l'inscrit attend) ; `/annonce`
(titre, texte, image, « Publier aussi dans les Actus du site », « Mentionner le rôle Actus », aperçu, contrôle anti-IA
avant l'aperçu) ; `#🚩・signalements` (réservé à Modération) ; `#📒・journal-du-bot` (chaque geste du staff et du bot,
le même journal que l'admin du site, et le rapport du lundi). `#🎫・tickets` porte un menu (question de jeu, achat ou
Gemmes, compte ou liaison, contester une sanction, créateurs) ; chaque choix ouvre un salon privé, archivé dans le
journal à la fermeture ; une question de jeu est d'abord renvoyée vers `#❓・aide`. Au-delà de 500 membres, des Aides
sur candidature (`/candidature`), qui répondent, masquent et coupent le relais pour une heure.

## Faire grandir le serveur

Rien n'est donné pour une arrivée sur le Discord : la politique de Discord l'interdit. Interdits aussi : un giveaway ou
un concours qui demande d'inviter ou d'acheter, `@everyone`, la mention d'un rôle non choisi, un message privé non
demandé hors du tuto.

- **Le parrainage, dans le jeu** (Menu, Mon compte, Parrainage) : un code par joueur, saisi par le filleul pendant ses
  premières heures (durée dans `definitions/`) ; le filleul compte à la 2e époque ; paliers 1, 5 et 20 filleuls
  actifs, un titre puis des objets de déco, jamais de production ni d'argent. `/parrainage` montre sa carte.
- **Le badge SILO** à 3 boosts, porté au choix de chacun ; rien n'est donné pour ça.
- **Les Soutiens** : pendant le boost, le rôle sur Discord et le titre en jeu, la couleur du pseudo, une icône devant le
  gamertag, un objet de déco réservé, un salon `#💎・soutiens`. Rien sur la production. La partie en jeu demande un
  compte relié ; un boost d'avant la liaison est rattrapé.
- **Le concours des créateurs** : chaque mois, les vidéos Silooo dans le forum `#🎬・créateurs`, vote des reliés, une
  voix par mois ; les trois premiers reçoivent le rôle Créateur et un titre en jeu, leurs vidéos passent dans
  `#📢・annonces` et les Actus.
- **L'XP de tchat** : 15 à 25 points par message, une fois par minute au plus, dans `#💬・discussion`, `#📸・captures` et
  les forums ; rien dans les salons du bot ni dans `#🎮・tchat-du-jeu` ; un message supprimé perd ses points. Rôles aux
  niveaux 5, 10, 20 et 30 ; `/niveau` ; les dix premiers de la semaine publiés le lundi dans `#💬・discussion`, puis
  remis à zéro. Les chiffres vivent dans `definitions/`.
- **Les portes d'entrée** : la case « Rejoindre aussi le Discord » à l'inscription sur le site (portée `guilds.join`),
  le lien `silooo.com/discord` sur le site et dans le menu du jeu. La promotion passe par Hubyy seulement, avec un
  plan à travailler avec Kr.

Rien ne s'annonce sur Discord avant que la direction du jeu ait livré la récompense en jeu : titres, objets de déco,
Soutiens, parrainage, lots des concours passent par le contrat et « Pour la direction du jeu ».

## Technique

- **Où vit le bot** : dans silooo-core, le paquet `discord/`, derrière une interface Kotlin avec un faux Discord pour
  les tests ; aucun test ne parle au vrai Discord. La bibliothèque est JDA 6.7.0, choisie par l'ADR 0007 sur les
  composants récents, la cadence des versions et le changement de Discord du 16 novembre 2026 ; Kord est écarté.
- **Ne jamais faire tomber le jeu** : le bot ne démarre que si `SILOOO_DISCORD_ACTIF` vaut `oui` ou `a-blanc` (l'écart
  avec le plan, sans rien changer) ; `non` par défaut ; `SILOOO_DISCORD_PHASE` dit quelles entrées du plan existent ; une file
  bornée pour les images ; un budget mémoire mesuré avec et sans le bot, chiffres dans la passation ; une panne du bot
  ou de Discord n'arrête ni le jeu ni le site. Au-delà du budget, le bot devient un processus à part (ADR).
- **Les secrets** : `SILOOO_DISCORD_TOKEN` et l'identifiant du serveur viennent de l'environnement, jamais du dépôt :
  des variables du compte Windows de Kr tant que le bot tourne sur son PC, le fichier d'environnement du conteneur sur le
  VPS ensuite. La session lance le programme sans ouvrir de fichier d'environnement ni afficher la variable ; les journaux
  masquent tout jeton.
- **Ce que le bot demande à Discord** : les intentions de serveur, de membres et de contenu des messages (privilégiées,
  accordées sans examen sous 100 serveurs), les droits de gérer les salons, les rôles, les pseudos et les webhooks.
  Il utilise commandes, boutons, menus, fenêtres, la commande de clic droit, les composants récents, un webhook pour
  le tchat relié.
- **Les événements** : le bot lit ceux que le service publie déjà pour l'add-on (prestige, Vote party, commande du
  jour, records), avec son propre curseur. Les images sont dessinées en PNG avec les textures de `design/`.
- **Les données**, par migrations : rappels, journal du tchat relié (purgé après 30 jours), relais coupés, message de
  `#🏭・le-silo`, spoils, votes des sondages, XP (comptée en mémoire, écrite une fois par minute), tickets, lots en
  attente. La liaison réutilise `site_registrations` du site.
- **Le serveur Silooo** : un seul serveur Discord, pas de serveur de test (décision de Kr du 5 novembre 2026, voir
  « Décisions de Kr » dans [discord-passation.md](discord-passation.md)). Le bot y travaille depuis le PC de Kr pendant le
  chantier, à blanc d'abord : Kr lit le compte rendu dans `#📒・journal-du-bot` avant tout passage à `oui`. Le serveur reste
  fermé au public jusqu'à l'ouverture du QG ; d'ici là, le bot n'écrit en privé et ne mentionne de rôle qu'au staff et à la
  modération (`ClosedServer`, tant que `SILOOO_DISCORD_PUBLIC` vaut `non`). Un seul bot tourne à la fois : celui du PC
  pendant le chantier, celui du VPS après la passation, déployée par la direction du jeu. Chaque étape se rejoue sur ce
  serveur avant d'être cochée ; ce que le bot y écrit pendant les essais est listé dans la passation, pour être nettoyé
  avant l'ouverture.
