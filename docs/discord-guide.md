# Le Guide du serveur, à poser par Kr

Discord n'offre aucune API pour le Guide du serveur (les rubriques, les tâches des nouveaux, le mot d'accueil) : seule
l'application Discord le règle. Le bot Oooscar pose tout le reste de l'arrivée (l'accueil guidé, ses deux questions,
les rôles et les salons par défaut : `OnboardingApplier`, voir `core/README.md`, « Le Discord »). Ce fichier dit ce
que Kr pose à la main, dans l'ordre, avec les textes à coller. Tous ces textes sont à valider par Kr : il les change
comme il veut, du moment qu'ils restent sans emoji et sans mot-réclame (`design/README.md`, « Écrire sans slop »).

## 1. Le mode Communauté

Paramètres du serveur, Activer la communauté. Discord demande deux salons :

| Ce que Discord demande | Le salon du plan |
|---|---|
| Salon des règles ou des directives | `#👋・bienvenue` |
| Salon des mises à jour de la communauté | `#📒・journal-du-bot` |

Le bot pourrait l'activer lui-même (la fonctionnalité `COMMUNITY` de l'API), mais ce réglage engage les conditions
communautaires de Discord : il reste à Kr. Sans lui, pas de forums, pas de salon d'annonces, pas d'accueil guidé ; le
bot le dit dans son compte rendu et crée le reste.

## 2. L'accueil guidé

Le bot le pose au démarrage, une fois les rôles et les salons créés, dès que le mode Communauté est actif : deux
questions (la plateforme, obligatoire, une seule réponse ; les notifications, plusieurs réponses, facultative), chaque
réponse donnant un rôle du plan, et pour salons par défaut tous les salons publics de la phase. Kr n'a rien à poser ;
il vérifie dans Paramètres du serveur, Accueil, que les deux questions sont là, et lit la ligne « J'ai posé l'accueil
guidé » dans `#📒・journal-du-bot`.

Si Discord refuse l'accueil (la ligne « Échecs : l'accueil guidé » du journal), c'est presque toujours la règle de
Discord : au moins 7 salons par défaut, dont 5 où tout le monde écrit. Le plan de la phase `attente` en compte 5 où
tout le monde écrit en comptant le vocal « Crée ton vocal » ; si Discord ne compte pas les vocaux, il faut ouvrir
l'écriture d'un salon de plus dans le plan (décision 9 de `docs/discord-passation.md`, à trancher par Kr).

## 3. Le Guide du serveur

Paramètres du serveur, Guide du serveur. Trois parties.

### Le mot d'accueil

Signé par le compte de Kr (le guide ne se signe pas par un bot) :

> Bienvenue au QG. Le jeu se construit sous tes yeux : les coulisses, les sondages et les giveaways sont ouverts, le
> reste arrive avec la bêta.

### Les tâches des nouveaux

Trois tâches, chacune vers un salon ; Discord en accepte jusqu'à cinq.

| Tâche | Salon |
|---|---|
| Lis comment rejoindre le jeu | `#🧭・rejoindre` |
| Relie ton compte sur silooo.com | `#👋・bienvenue` |
| Dis bonjour | `#💬・discussion` |

À la bêta, une quatrième : « Montre ton île » vers `#📸・captures`.

### Les rubriques

Trois rubriques (Discord appelle ça des « pages de ressources »), chacune avec son image : la plaque émaillée de
`design/composants/Plaque.md` et une icône de `design/icones/`, rendues en pixel par D1 et déposées dans
`design/discord/guide/`. Tant que les images ne sont pas là, Kr pose les textes seuls.

| Rubrique | Titre | Texte | Salon lié | Image |
|---|---|---|---|---|
| Rejoindre | Rejoindre Silooo | Sur console, ajoute HubyOnMC en ami et rejoins sa partie. Sur téléphone et PC, l'adresse est play.silooo.com, port 19142. Tout est dans #🧭・rejoindre. | `#🧭・rejoindre` | `guide-rejoindre.png` (plaque « Rejoindre », icône `carte`) |
| Relier | Relier ton compte | Inscris-toi sur silooo.com et relie ton Discord. À la bêta, le code montré en jeu prouve ton gamertag : ton pseudo devient « Gamertag · P3 » et tes rôles suivent. | `#👋・bienvenue` | `guide-relier.png` (plaque « Relier », icône `cadenas`) |
| Règlement | Le règlement | Le Discord est pour les 13 ans et plus. On parle du jeu et du reste sans insulte, sans pub et sans lien douteux. Ce qui ne se dit pas en public passe par #🎫・tickets. | `#🎫・tickets` | `guide-reglement.png` (plaque « Règlement », icône `engrenage`) |

## 4. Les images, pour D1

Le brief des trois plaques, à suivre avec le skill `silenemc-contenu` et la marque `silooo` :

- Format : 960 × 320 PNG, sans lissage (pixel 2D tiré de `design/`, critique du plan, point 5).
- La plaque émaillée de `design/composants/Plaque.md` : fond `email-creme`, filet `email-bleu`, le titre en capitales
  dans la police d'affiche (`affiche`, Jersey 10), un ou deux mots, l'icône à gauche du titre. Pas d'ombre portée, pas
  de flou, pas de lueur (`design/README.md`).
- Les trois icônes de `design/icones/`, agrandies sans lissage : `carte` (Rejoindre), `cadenas` (Relier), `engrenage`
  (Règlement), à valider par Kr ; une icône de 128 × 128 sur fond transparent accompagne chaque plaque pour la
  vignette de la rubrique.
- Chaque image passe la boucle de D1 : brief, contrôle, `critique.py`, relecture à froid, envoi à Kr. Les exports
  validés vont dans `design/discord/guide/`, marqués « à valider par Kr » tant qu'ils ne le sont pas.
