#!/usr/bin/env python3
"""Vérifie qu'un APK contient la liste de blocage embarquée, lisible et complète.

Usage : python3 tools/verifier_apk.py app/build/outputs/apk/debug/app-debug.apk

La compilation Android traite à part les assets en « .gz » (aapt les décompresse et retire
l'extension) : l'application ne trouvait plus sa liste dans l'APK. Ce contrôle détecte ce
genre de surprise dès la construction, sans émulateur.
"""
import gzip
import sys
import zipfile

# Doit correspondre à BlocklistCatalog.HAGEZI_PRO.embeddedAsset (préfixe « assets/ » en plus).
ASSET = "assets/blocklists/hagezi-pro.txt.gzip"
MIN_DOMAINES = 50_000  # même seuil que tools/maj_liste_embarquee.sh


def verifier(apk: str) -> str | None:
    """Renvoie un message d'erreur, ou None si l'APK est correct."""
    with zipfile.ZipFile(apk) as archive:
        noms = archive.namelist()
        if ASSET not in noms:
            assets = [n for n in noms if n.startswith("assets/")]
            return f"{ASSET} absent de l'APK (assets présents : {assets or 'aucun'})"
        contenu = archive.read(ASSET)
    try:
        texte = gzip.decompress(contenu).decode("utf-8")
    except (OSError, EOFError, UnicodeDecodeError) as e:
        return f"{ASSET} n'est pas un fichier gzip de texte valide : {e}"
    domaines = sum(1 for ligne in texte.splitlines() if ligne.strip() and not ligne.lstrip().startswith("#"))
    if domaines < MIN_DOMAINES:
        return f"{ASSET} ne contient que {domaines} domaines (minimum {MIN_DOMAINES})"
    print(f"OK : {ASSET} présent, {len(contenu)} octets, {domaines} domaines")
    return None


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    erreur = verifier(sys.argv[1])
    if erreur:
        print(f"ÉCHEC : {erreur}")
        sys.exit(1)
