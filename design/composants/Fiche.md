# Fiche

Le détail d'une usine, d'un bâtiment, d'une récompense ou d'un article. Toujours dans le même ordre, pour que le joueur sache où regarder :

1. `sa-fiche__sorte` : la catégorie, en capitales (USINE · ÉPOQUE 1).
2. `sa-fiche__titre` : le nom, et le niveau s'il y en a un.
3. Une phrase de 10 mots au plus qui dit à quoi ça sert.
4. `sa-fiche__infos` : 3 lignes au plus, icône + mot + chiffre aligné à droite. Actuel, prochain palier, coût.
5. La dernière ligne dit quoi faire : `sa-fiche__action` (or) si c'est possible, `sa-fiche__blocage` (rouge clair) avec la raison si ce ne l'est pas, `sa-fiche__ok` (vert) si c'est déjà fait.

Fond `fonte-900` obligatoire : c'est lui qui donne le contraste aux couleurs d'état (rouge clair 5,1:1, vert clair 5:1, or 7:1).
