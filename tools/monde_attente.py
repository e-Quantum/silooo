#!/usr/bin/env python3
"""Fabrique le level.dat de la salle d'attente : un monde plat sans aucune couche (le vide), « Beta APIs » actif.

Le point de départ est un level.dat de Bedrock 1.26.62 (celui que BDS a créé pour le monde de test
de Kr). On n'en change que ce que la salle d'attente demande : « Beta APIs », générateur plat sans couche, nom, mode
aventure, jour figé, pas de météo, pas de monstres, pas de dégâts de chute, pas de cycle jour-nuit. Le reste (version
du format, réglages du client) est gardé : c'est ce qui fait que BDS l'accepte et charge les modules bêta.

    python3 tools/monde_attente.py <level.dat source> <level.dat produit>
    python3 tools/monde_attente.py --montrer <level.dat>     # affiche les clés, pour vérifier

Format : en-tête de 8 octets (version du stockage, longueur), puis un NBT petit-boutiste non compressé.
"""

import struct
import sys

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Lecteur:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def prendre(self, fmt):
        taille = struct.calcsize(fmt)
        valeur = struct.unpack_from("<" + fmt, self.data, self.pos)
        self.pos += taille
        return valeur[0]

    def chaine(self):
        longueur = self.prendre("H")
        texte = self.data[self.pos:self.pos + longueur].decode("utf-8")
        self.pos += longueur
        return texte

    def charge(self, type_):
        if type_ == BYTE: return self.prendre("b")
        if type_ == SHORT: return self.prendre("h")
        if type_ == INT: return self.prendre("i")
        if type_ == LONG: return self.prendre("q")
        if type_ == FLOAT: return self.prendre("f")
        if type_ == DOUBLE: return self.prendre("d")
        if type_ == STRING: return self.chaine()
        if type_ == BYTE_ARRAY:
            n = self.prendre("i"); v = self.data[self.pos:self.pos + n]; self.pos += n; return v
        if type_ == INT_ARRAY:
            return [self.prendre("i") for _ in range(self.prendre("i"))]
        if type_ == LONG_ARRAY:
            return [self.prendre("q") for _ in range(self.prendre("i"))]
        if type_ == LIST:
            sous = self.prendre("b"); n = self.prendre("i")
            return (sous, [self.charge(sous) for _ in range(n)])
        if type_ == COMPOUND:
            champs = []
            while True:
                t = self.prendre("b")
                if t == END: return champs
                champs.append([t, self.chaine(), self.charge(t)])
        raise ValueError(f"type NBT inconnu : {type_}")


def ecrire(type_, valeur, out):
    def pack(fmt, v): out.extend(struct.pack("<" + fmt, v))
    def chaine(s):
        b = s.encode("utf-8"); pack("H", len(b)); out.extend(b)
    if type_ == BYTE: pack("b", valeur)
    elif type_ == SHORT: pack("h", valeur)
    elif type_ == INT: pack("i", valeur)
    elif type_ == LONG: pack("q", valeur)
    elif type_ == FLOAT: pack("f", valeur)
    elif type_ == DOUBLE: pack("d", valeur)
    elif type_ == STRING: chaine(valeur)
    elif type_ == BYTE_ARRAY: pack("i", len(valeur)); out.extend(valeur)
    elif type_ == INT_ARRAY: pack("i", len(valeur)); [pack("i", v) for v in valeur]
    elif type_ == LONG_ARRAY: pack("i", len(valeur)); [pack("q", v) for v in valeur]
    elif type_ == LIST:
        sous, items = valeur; pack("b", sous); pack("i", len(items)); [ecrire(sous, v, out) for v in items]
    elif type_ == COMPOUND:
        for t, nom, v in valeur:
            pack("b", t); chaine(nom); ecrire(t, v, out)
        pack("b", END)
    else:
        raise ValueError(f"type NBT inconnu : {type_}")


def lire_level(chemin):
    data = open(chemin, "rb").read()
    version, longueur = struct.unpack_from("<ii", data, 0)
    lecteur = Lecteur(data[8:8 + longueur])
    type_ = lecteur.prendre("b")
    if type_ != COMPOUND: raise ValueError("level.dat : la racine n'est pas un compound")
    nom = lecteur.chaine()
    return version, nom, lecteur.charge(COMPOUND)


def ecrire_level(chemin, version, nom, racine):
    corps = bytearray()
    corps.extend(struct.pack("<b", COMPOUND))
    b = nom.encode("utf-8"); corps.extend(struct.pack("<H", len(b))); corps.extend(b)
    ecrire(COMPOUND, racine, corps)
    with open(chemin, "wb") as f:
        f.write(struct.pack("<ii", version, len(corps)))
        f.write(corps)


def poser(champs, nom, type_, valeur):
    for champ in champs:
        if champ[1] == nom:
            champ[0], champ[2] = type_, valeur
            return
    champs.append([type_, nom, valeur])


def champ(champs, nom):
    return next((c[2] for c in champs if c[1] == nom), None)


# Plat, sans aucune couche : chaque bloc du monde est de l'air, la salle d'attente pose les siens.
COUCHES_VIDES = '{"biome_id":1,"block_layers":[],"encoding_version":6,"structure_options":null,"world_version":"version.post_1_18"}\n'


def transformer(racine):
    poser(racine, "Generator", INT, 2)  # 2 : plat
    poser(racine, "FlatWorldLayers", STRING, COUCHES_VIDES)
    poser(racine, "LevelName", STRING, "Silooo, salle d'attente")
    poser(racine, "GameType", INT, 2)  # aventure : on ne casse ni ne pose rien
    poser(racine, "ForceGameType", BYTE, 1)
    poser(racine, "Difficulty", INT, 0)  # paisible
    poser(racine, "SpawnX", INT, 0)
    poser(racine, "SpawnY", INT, 101)
    poser(racine, "SpawnZ", INT, 0)
    poser(racine, "spawnMobs", BYTE, 0)
    for regle in ("dodaylightcycle", "doweathercycle", "domobspawning", "falldamage", "firedamage", "drowningdamage",
                  "doimmediaterespawn", "domobloot", "dotiledrops", "doentitydrops", "mobgriefing", "pvp", "tntexplodes",
                  "showcoordinates", "showdeathmessages", "commandblockoutput", "sendcommandfeedback", "dofiretick"):
        valeur = 1 if regle in ("doimmediaterespawn",) else 0
        poser(racine, regle, BYTE, valeur)
    poser(racine, "Time", LONG, 6000)  # midi
    poser(racine, "rainLevel", FLOAT, 0.0)
    poser(racine, "lightningLevel", FLOAT, 0.0)
    # « Beta APIs » : l'expérience gametest, et les deux marques que le jeu pose quand une expérience a servi.
    experiences = champ(racine, "experiments")
    if not isinstance(experiences, list):
        experiences = []
        poser(racine, "experiments", COMPOUND, experiences)
    for nom in ("experiments_ever_used", "saved_with_toggled_experiments", "gametest"):
        poser(experiences, nom, BYTE, 1)


def main(args):
    if len(args) == 2 and args[0] == "--montrer":
        version, _, racine = lire_level(args[1])
        print(f"version du stockage : {version}")
        for t, nom, v in racine:
            print(f"  {nom} = {v!r}"[:160])
        return
    if len(args) != 2:
        raise SystemExit(__doc__)
    version, nom, racine = lire_level(args[0])
    transformer(racine)
    ecrire_level(args[1], version, nom, racine)
    # Relecture : le fichier produit doit se relire à l'identique.
    if lire_level(args[1])[2] != racine:
        raise SystemExit("le level.dat produit ne se relit pas à l'identique")
    print(f"{args[1]} : monde plat sans couche, Beta APIs actif")


if __name__ == "__main__":
    main(sys.argv[1:])
