// Lecture et écriture des PNG RGBA de 8 bits, sans dépendance : ce que les scripts de tools/ lisent dans design/ et
// écrivent dans resource-pack/. Repris de tools/generer_oooscar.mjs, qui garde sa propre copie.
import { deflateSync, inflateSync } from "node:zlib";

const TABLE_CRC = new Uint32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(tampon) {
  let c = 0xffffffff;
  for (const octet of tampon) c = TABLE_CRC[(c ^ octet) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function bloc(type, donnees) {
  const tete = Buffer.alloc(8);
  tete.writeUInt32BE(donnees.length, 0);
  tete.write(type, 4, "ascii");
  const pied = Buffer.alloc(4);
  pied.writeUInt32BE(crc32(Buffer.concat([tete.subarray(4), donnees])), 0);
  return Buffer.concat([tete, donnees, pied]);
}

/** Un PNG RGBA de 8 bits, sans entrelacement, à partir de pixels rangés ligne par ligne. */
export function encoderPng(pixels, largeur, hauteur) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(largeur, 0);
  ihdr.writeUInt32BE(hauteur, 4);
  ihdr.set([8, 6, 0, 0, 0], 8);
  const lignes = Buffer.alloc((largeur * 4 + 1) * hauteur);
  for (let y = 0; y < hauteur; y++) {
    lignes[y * (largeur * 4 + 1)] = 0;
    Buffer.from(pixels.buffer, pixels.byteOffset + y * largeur * 4, largeur * 4).copy(lignes, y * (largeur * 4 + 1) + 1);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    bloc("IHDR", ihdr),
    bloc("IDAT", deflateSync(lignes, { level: 9 })),
    bloc("IEND", Buffer.alloc(0)),
  ]);
}

/** Lit un PNG RGBA de 8 bits sans entrelacement : sa taille et ses pixels. Lève pour tout autre format. */
export function lirePng(tampon) {
  let p = 8;
  let largeur = 0;
  let hauteur = 0;
  const donnees = [];
  while (p < tampon.length) {
    const longueur = tampon.readUInt32BE(p);
    const type = tampon.toString("ascii", p + 4, p + 8);
    const corps = tampon.subarray(p + 8, p + 8 + longueur);
    if (type === "IHDR") {
      largeur = corps.readUInt32BE(0);
      hauteur = corps.readUInt32BE(4);
      if (corps[8] !== 8 || corps[9] !== 6 || corps[12] !== 0) throw new Error("PNG attendu en RGBA de 8 bits, sans entrelacement");
    }
    if (type === "IDAT") donnees.push(corps);
    p += 12 + longueur;
  }
  const brut = inflateSync(Buffer.concat(donnees));
  const pixels = new Uint8Array(largeur * hauteur * 4);
  const pas = largeur * 4;
  for (let y = 0; y < hauteur; y++) {
    const filtre = brut[y * (pas + 1)];
    for (let x = 0; x < pas; x++) {
      const a = x >= 4 ? pixels[y * pas + x - 4] : 0;
      const b = y ? pixels[(y - 1) * pas + x] : 0;
      const c = x >= 4 && y ? pixels[(y - 1) * pas + x - 4] : 0;
      let v = brut[y * (pas + 1) + 1 + x];
      if (filtre === 1) v += a;
      else if (filtre === 2) v += b;
      else if (filtre === 3) v += (a + b) >> 1;
      else if (filtre === 4) {
        const estimation = a + b - c;
        const pa = Math.abs(estimation - a);
        const pb = Math.abs(estimation - b);
        const pc = Math.abs(estimation - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      pixels[y * pas + x] = v & 255;
    }
  }
  return { largeur, hauteur, pixels };
}
