#!/usr/bin/env python3
"""Test de bout en bout sur un émulateur Android (utilisé par la CI).

Installe l'APK de débogage, accorde le consentement VPN sans interface (appops ACTIVATE_VPN,
possible seulement par ADB), active la protection par le raccourci « Activer », puis vérifie
depuis le téléphone que :
- le tunnel VPN est monté ;
- des domaines de la liste embarquée ne se résolvent plus (NXDOMAIN) ;
- des domaines ordinaires se résolvent toujours ;
- le service a bien journalisé les blocages (trace des builds de débogage) ;
- « Autoriser » dans le Journal rétablit la résolution du domaine sans redémarrage, les autres
  domaines restant bloqués.
Des captures de chaque écran sont enregistrées dans build/captures-emulateur/.

Prérequis : un émulateur démarré et `adb` dans le PATH.
Usage : python3 tools/test_emulateur.py [chemin/vers/app-debug.apk]
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "io.github.clawfabriceh92.adblockdns.debug"
ACTIVITY = "io.github.clawfabriceh92.adblockdns.ui.MainActivity"
ACTION_ACTIVER = "io.github.clawfabriceh92.adblockdns.action.ACTIVER"
ROOT = pathlib.Path(__file__).resolve().parent.parent
CAPTURES = ROOT / "build" / "captures-emulateur"
# Présents dans la liste embarquée (hagezi Pro) ; le dernier est bloqué via son parent.
BLOQUES = ["googlesyndication.com", "app-measurement.com", "scorecardresearch.com", "pagead2.googlesyndication.com"]
AUTORISES = ["example.com", "wikipedia.org"]

resultats: list[tuple[str, bool, str]] = []


def check(nom: str, ok: bool, detail: str = "") -> bool:
    resultats.append((nom, bool(ok), detail))
    print(("  OK   " if ok else "  ÉCHEC") + f" {nom}" + (f" : {detail}" if detail else ""), flush=True)
    return bool(ok)


def adb(*args: str, timeout: int = 60, check_rc: bool = True) -> str:
    res = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check_rc and res.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} : {res.stderr.strip() or res.stdout.strip()}")
    return res.stdout + res.stderr


def shell(command: str, timeout: int = 60) -> str:
    return adb("shell", command, timeout=timeout, check_rc=False)


def resout(domaine: str) -> tuple[bool, str]:
    """Vrai si le nom se résout sur le téléphone (ping affiche alors « PING nom (adresse) »)."""
    sortie = shell(f"ping -c 1 -W 3 {domaine} 2>&1", timeout=30).strip()
    return (f"PING {domaine} (" in sortie), sortie.splitlines()[0] if sortie else ""


def capture(nom: str) -> None:
    CAPTURES.mkdir(parents=True, exist_ok=True)
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout
    (CAPTURES / f"{nom}.png").write_bytes(png)
    print(f"   capture : {nom}.png ({len(png)} octets)")


def noeuds_ecran() -> list[ET.Element]:
    """Éléments affichés, dans l'ordre de l'arbre d'accessibilité (uiautomator)."""
    shell("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    xml = shell("cat /sdcard/ui.xml")
    debut = xml.find("<?xml")
    return list(ET.fromstring(xml[debut:]).iter("node")) if debut >= 0 else []


def toucher(noeud: ET.Element) -> None:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", noeud.get("bounds", "")))
    shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")


def toucher_texte(texte: str) -> bool:
    """Touche le premier élément affichant exactement ce texte."""
    for noeud in noeuds_ecran():
        if noeud.get("text") == texte:
            toucher(noeud)
            return True
    return False


def ligne_du_journal() -> tuple[str, ET.Element] | None:
    """(domaine, bouton « Autoriser ») de la première ligne du Journal portant un domaine testé."""
    domaine = None
    for noeud in noeuds_ecran():
        texte = noeud.get("text")
        if texte in BLOQUES:
            domaine = texte
        elif texte == "Autoriser":
            if domaine:
                return domaine, noeud
            domaine = None
    return None


def verifier_autoriser() -> None:
    """« Autoriser » depuis le Journal : le domaine se résout aussitôt, les autres restent bloqués."""
    ligne = ligne_du_journal()
    if not check("Journal : ligne d'un domaine bloqué avec « Autoriser »", ligne is not None, ligne[0] if ligne else "aucune"):
        return
    domaine, bouton = ligne
    toucher(bouton)
    resolu = attendre(lambda: resout(domaine)[0], 12)
    check(f"« Autoriser » : {domaine} se résout à nouveau, sans redémarrage", bool(resolu))
    autre = next(d for d in ("scorecardresearch.com", "app-measurement.com") if d != domaine)
    ok, sortie = resout(autre)
    check(f"les autres domaines restent bloqués : {autre}", not ok, sortie)
    capture("02b-journal-apres-autoriser")


def interface_tun() -> tuple[str, str] | None:
    """(nom, adresse IPv4) de la première interface tun, d'après « ip addr »."""
    for bloc in re.split(r"\n(?=\d+: )", shell("ip addr")):
        entete = re.match(r"\d+: (tun\d+)", bloc.strip())
        adresse = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", bloc)
        if entete and adresse:
            return entete.group(1), adresse.group(1)
    return None


def attendre(condition, delai: float, pas: float = 1.0):
    fin = time.time() + delai
    while time.time() < fin:
        valeur = condition()
        if valeur:
            return valeur
        time.sleep(pas)
    return None


def main() -> int:
    apk = sys.argv[1] if len(sys.argv) > 1 else str(ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    print(f"Android {shell('getprop ro.build.version.release').strip()} (API {shell('getprop ro.build.version.sdk').strip()})")
    adb("install", "-r", "-g", apk, timeout=180)
    shell(f"appops set {PKG} ACTIVATE_VPN allow")
    shell(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    shell("logcat -c")

    avant = {d: resout(d)[0] for d in BLOQUES[:1] + AUTORISES[:1]}
    check("réseau de l'émulateur fonctionnel avant activation", all(avant.values()), str(avant))

    shell(f"am start -W -n {PKG}/{ACTIVITY} -a {ACTION_ACTIVER}")
    tun = attendre(interface_tun, 90)
    check("tunnel VPN monté", tun is not None, " ".join(tun) if tun else "aucune interface tun")
    time.sleep(3)
    capture("01-accueil")

    for domaine in BLOQUES:
        ok, ligne = resout(domaine)
        check(f"bloqué : {domaine}", not ok, ligne)
    for domaine in AUTORISES:
        ok, ligne = resout(domaine)
        check(f"autorisé : {domaine}", ok, ligne)

    journal = attendre(lambda: [l for l in shell("logcat -d -s VpnService:D").splitlines() if "Bloqué" in l], 10)
    check("blocages journalisés par le service", bool(journal), journal[0].split("Bloqué", 1)[1].strip() if journal else "aucune trace")

    time.sleep(2)  # écriture du journal par lots (au plus une seconde)
    for nom, onglet in (("02-journal", "Journal"), ("03-statistiques", "Stats"), ("04-listes", "Listes"), ("05-reglages", "Réglages")):
        if toucher_texte(onglet):
            time.sleep(2)
            capture(nom)
            if onglet == "Journal":
                verifier_autoriser()
        else:
            check(f"onglet « {onglet} » présent", False, "introuvable dans l'arbre d'accessibilité")
    toucher_texte("Accueil")

    plantages = shell("logcat -d -b crash")
    check("aucun plantage", PKG.split(".debug")[0] not in plantages and "FATAL EXCEPTION" not in plantages,
          plantages.strip().splitlines()[0] if plantages.strip() else "")

    ok = sum(1 for _, o, _ in resultats if o)
    print(f"\nRÉSULTAT : {ok}/{len(resultats)} contrôles OK")
    if ok != len(resultats):
        print(shell("logcat -d -s VpnService:* Tunnel:* Filtre:* Listes:* AndroidRuntime:E")[-4000:])
    return 0 if ok == len(resultats) else 1


if __name__ == "__main__":
    sys.exit(main())
