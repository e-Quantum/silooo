---
name: silooo-equilibrage
description: "Changer un prix, un gain, un seuil ou une formule du jeu en ne touchant qu'à definitions/, avec vérification contre le tableur d'équilibrage et trace datée. À utiliser dès qu'un chiffre de Silooo bouge."
---

# Changer un chiffre du jeu

Un chiffre du jeu vit dans `definitions/` et nulle part ailleurs. Cette recette change ce chiffre, le compare aux cibles du tableur et laisse une trace datée.

## Quand l'utiliser

- Un prix, un gain, une croissance, un palier, un plafond hors ligne, un seuil de prestige, un prix de zone, un pack de Gemmes ou un grade doit changer.
- Une usine se règle ou s'ajoute dans `definitions/usines.json` : même recette.
- Pas ici : un texte ou un écran (`silooo-ecran`), une route (`silooo-route`).
- Si la forme d'une formule change, pas seulement un paramètre, arrête-toi : c'est une décision. Elle demande un ADR dans `docs/adr/` et l'accord de Kr.

## À lire d'abord

- [`CLAUDE.md`](../../../CLAUDE.md) : les règles du code et les interdits.
- `docs/economie.md` : formules, paliers, notation, section « Historique des réglages ».
- [`docs/socle.md`](../../../docs/socle.md) : règles 5 et 10, et « Choix faits en autonomie » pour les Lingots.
- [`definitions/economie.json`](../../../definitions/economie.json), [`definitions/usines.json`](../../../definitions/usines.json) et [`definitions/boutique.json`](../../../definitions/boutique.json) : les chiffres.
- `definitions/schemas/` : ce que chaque fichier accepte.
- [`docs/references/silooo-equilibrage-v2.xlsx`](../../../docs/references/silooo-equilibrage-v2.xlsx) : le tableur et ses 13 cibles.
- [`docs/game-design.md`](../../../docs/game-design.md), section « Comment on tient ces cibles » : les réglages principaux.

## Étapes

1. **Repère la clé.** Lis la partie de `docs/economie.md` qui la décrit. Ouvre le fichier de `definitions/`, note le chemin de la clé (`niveaux.croissancePrix`, `prestige.conversionLingots.coefficient`, `ville.zonesPrixLingots`, ou `prixNiveau1` d'une usine) et sa valeur actuelle. Fichier touché : aucun.
2. **Change la valeur, rien d'autre.** Une seule clé par session si possible : sinon on ne sait plus laquelle a déplacé les cibles. La croissance du prix par niveau est le réglage le plus sensible. Fichier touché : un fichier de `definitions/`.
   - Les Lingots sont personnels, jamais ceux de l'entreprise.
   - Le bonus par Lingot détenu se calcule, pour une entreprise, sur la moyenne des Lingots de ses membres (`docs/socle.md`). Un réglage de Lingots ne se pense jamais en total d'équipe.
3. **Passe les schémas.** `tools/verifier.sh` lance `tools/verifier_definitions.mjs`, qui valide les trois fichiers contre `definitions/schemas/`. Si un schéma refuse la valeur, corrige la valeur, jamais le schéma. Fichier touché : aucun.
4. **Rejoue les tests dorés de `core/`** quand ils existent (phase 1). Ils sont tirés du tableur : un test doré qui casse dit que le chiffre sort des cibles. Ne modifie pas le test. Si l'écart est voulu, le tableur et les tests se recalent ensemble, avec Kr : garde le réglage sur ta branche `claude/*`, ne le pousse pas sur `main` et dis-le dans le compte rendu. Fichier touché : aucun.
5. **Lance le simulateur** `tools/simulateur` quand il existe (phase 4), comme l'explique son README. Il rejoue le tableur sur les `definitions/` modifiées. Lis le temps de chaque étape. Fichier touché : aucun.
6. **Compare aux 13 cibles du tableur.** Les tests dorés de `core/` (phase 1) rejouent les achats du joueur type et vérifient les 13 étapes de la feuille Synthèse : c'est eux qui font la comparaison. Avant qu'ils existent, une session ne peut pas recalculer le tableur (ses formules demandent Excel) : dis-le dans le compte rendu et laisse le réglage « à valider par Kr ». Les 13 restent dans leur cible ; sinon, annule le changement et dis pourquoi. Fichier touché : aucun.
7. **Écris l'historique** pour un changement gardé. Ajoute une ligne dans la section « Historique des réglages » de `docs/economie.md` : date, clé, ancienne valeur, nouvelle valeur, pourquoi. Une ligne par clé. C'est le seul endroit de la doc où un chiffre du jeu s'écrit, parce qu'il date le changement. Fichier touché : `docs/economie.md`.
8. **Marque le changement « à valider par Kr »** dans le compte rendu (voir « En fin de session »). Il peut être commité, jamais présenté comme définitif. Fichier touché : aucun.

## Ce qu'on ne fait pas

- Aucun chiffre du jeu dans `core/`, `addon/`, `resource-pack/`, un test ou un texte : le code lit `definitions/`.
- Aucune migration pour changer un prix : la base ne garde que des identifiants (`docs/socle.md`, règle 10).
- Aucun schéma assoupli, aucun test doré modifié ou désactivé pour faire passer un chiffre.
- Aucun réglage à l'œil : sans la comparaison aux 13 cibles, le changement n'est pas fini.
- Rien dans `definitions/boutique.json` sans le signaler en tête du compte rendu : les packs de Gemmes et les grades touchent à l'argent réel.
- Aucune monnaie échangeable entre joueurs, aucun hasard payant : un réglage ne les introduit pas non plus.
- Aucune modification du tableur de référence du dépôt sans l'accord de Kr.

## Vérifier

```
tools/verifier.sh          # Git Bash sur le PC : schémas, tests, anti-IA, liens
git diff --stat            # seuls definitions/*.json et docs/economie.md ont bougé
cd core && ./gradlew test  # tests dorés, dès la phase 1
```

- Un seul Gradle à la fois sur le VPS : construis sur le PC ou dans la CI.
- `tools/simulateur` : voir l'étape 5, dès la phase 4.

## En fin de session

Le compte rendu va dans ton message final. Tu n'écris pas dans `docs/passation.md` : la direction y copie le journal.

- **Ce qui a changé** : la clé, l'ancienne et la nouvelle valeur, le fichier, la ligne ajoutée à l'historique.
- **Ce qui reste** : tests dorés ou simulateur pas encore là, cibles à revoir, réglages liés à regarder.
- **Comment vérifier** : les commandes ci-dessus et la cible du tableur qui a bougé le plus.
- **À valider par Kr** : le réglage, la raison et l'écart aux 13 cibles. Un réglage qui touche aux euros passe en premier.
