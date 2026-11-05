-- La ville : une parcelle (le coin x, z, en blocs depuis le coin de l'île) n'a qu'un bâtiment.
-- Le service vérifie déjà l'emplacement avant d'écrire (refus EMPLACEMENT_OCCUPE) ; cette clé est le garde-fou si deux actions
-- passaient en même temps. Elle ne remplace rien : la table city_buildings est vide partout tant que la ville n'existe pas.
ALTER TABLE city_buildings
    ADD UNIQUE KEY ux_buildings_plot (company_id, x, z);
