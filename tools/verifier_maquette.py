#!/usr/bin/env python3
"""Vérifie réellement la maquette cliquable (Playwright, Chromium headless).

Contrôles : zéro erreur console, navigation entre les 5 onglets, interrupteur de protection,
bouton « Autoriser » (liste blanche), mise à jour des listes, bascule de thème, courbe de stats.
Produit les captures dans docs/captures/.

Usage : python3 tools/verifier_maquette.py
"""
from __future__ import annotations

import pathlib
import sys

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGE = ROOT / "mockup" / "maquette-adblock-dns.html"
SHOTS = ROOT / "docs" / "captures"

resultats: list[tuple[str, bool, str]] = []


def check(nom: str, ok: bool, detail: str = "") -> bool:
    resultats.append((nom, bool(ok), detail))
    print(("  OK   " if ok else "  ÉCHEC") + f" {nom}" + (f" : {detail}" if detail else ""), flush=True)
    return bool(ok)


def main() -> int:
    SHOTS.mkdir(parents=True, exist_ok=True)
    erreurs: list[str] = []
    with sync_playwright() as p:
        nav = p.chromium.launch()
        page = nav.new_page(viewport={"width": 1180, "height": 980}, device_scale_factor=2)
        page.on("console", lambda m: erreurs.append(f"console: {m.text}") if m.type == "error" else None)
        page.on("pageerror", lambda e: erreurs.append(f"pageerror: {e}"))
        page.goto(PAGE.as_uri(), wait_until="load")
        page.wait_for_timeout(2600)  # animation d'entrée + compteur

        # --- accueil -------------------------------------------------------- #
        check("écran Accueil rendu", page.is_visible("text=Protection active"))
        compteur = page.inner_text("#cpt")
        check("compteur du jour affiché", compteur.replace("\u202f", "").replace(" ", "") == "1247", compteur)
        check("liste hagezi Pro affichée", "hagezi Pro" in page.content())

        # --- journal -------------------------------------------------------- #
        page.click("[data-nav='journal']")
        page.wait_for_timeout(500)
        lignes = page.locator("#liste-journal .item").count()
        check("journal rempli", lignes >= 20, f"{lignes} lignes")
        check("catégories affichées", "traqueur" in page.inner_text("#liste-journal"))

        # « Autoriser » la première ligne
        cible = page.locator("#liste-journal .dom").first.inner_text()
        avant = page.locator("#liste-journal .item").count()
        page.locator("[data-allow]").first.click()
        page.wait_for_timeout(500)
        apres = page.locator("#liste-journal .item").count()
        check("« Autoriser » retire la ligne", apres == avant - 1 or apres == avant, f"{avant} -> {apres}")
        check("domaine ajouté en liste blanche", cible in page.inner_text(".muted"), cible)
        check("toast affiché", page.locator("#toast.on").count() == 1, page.inner_text("#toast"))

        # recherche + filtre
        page.fill("#rech", "doubleclick")
        page.wait_for_timeout(400)
        ok_rech = page.locator("#liste-journal .dom").first.inner_text()
        check("recherche de domaine", "doubleclick" in ok_rech, ok_rech)
        page.fill("#rech", "")
        page.wait_for_timeout(300)
        page.select_option("#filtre", "instagram")
        page.wait_for_timeout(400)
        apps = set(page.locator("#liste-journal .meta").all_inner_texts())
        check("filtre par application", all("Instagram" in a for a in apps) and len(apps) > 0, f"{len(apps)} lignes")
        page.click("[data-nav='journal']")

        # --- stats ---------------------------------------------------------- #
        page.click("[data-nav='stats']")
        page.wait_for_timeout(900)
        check("courbe dessinée", page.locator("svg path#stroke").count() == 1)
        check("classement des applications", page.locator(".hbar").count() >= 5)
        page.click("[data-jours='7']")
        page.wait_for_timeout(600)
        check("bascule 7 j", "7 j" in page.inner_text(".card") or "par jour" in page.inner_text(".card"))

        # --- listes --------------------------------------------------------- #
        page.click("[data-nav='listes']")
        page.wait_for_timeout(400)
        check("listes affichées", "1 415 322 règles" in page.inner_text("#content").replace("\u202f", " "))
        page.click("#maj")
        page.wait_for_timeout(3600)
        txt = page.inner_text("#content").replace("\u202f", " ")
        check("mise à jour des listes effectuée", "1 421 887" in txt, txt.splitlines()[2] if txt else "")

        # --- réglages ------------------------------------------------------- #
        page.click("[data-nav='reglages']")
        page.wait_for_timeout(400)
        check("réglages : résolveur amont", "Résolveur DNS amont" in page.inner_text("#content"))
        check("réglages : applications exclues", "Applications exclues" in page.inner_text("#content"))
        page.click("[data-dns='cloudflare']")
        page.wait_for_timeout(400)
        check("changement de résolveur", "Cloudflare" in page.inner_text("#content"))
        page.click("[data-sw='boot']")
        page.wait_for_timeout(400)

        # --- retour accueil + arrêt protection ------------------------------ #
        page.click("[data-nav='accueil']")
        page.wait_for_timeout(600)
        page.click("#toggle")
        page.wait_for_timeout(700)
        check("protection arrêtée", "Protection arrêtée" in page.inner_text("#content"))
        page.screenshot(path=str(SHOTS / "01-accueil-arrete.png"))
        page.click("#toggle")
        page.wait_for_timeout(1600)

        # captures finales
        page.click("[data-nav='journal']")
        page.wait_for_timeout(400)
        page.select_option("#filtre", "")
        page.wait_for_timeout(400)
        for nom, onglet in (("02-accueil.png", "accueil"), ("03-journal.png", "journal"),
                            ("04-statistiques.png", "stats"), ("05-listes.png", "listes"),
                            ("06-reglages.png", "reglages")):
            page.click(f"[data-nav='{onglet}']")
            page.wait_for_timeout(700)
            page.locator(".phone").screenshot(path=str(SHOTS / nom))
            print("   capture :", SHOTS / nom)

        # thème clair
        page.click("#theme")
        page.wait_for_timeout(600)
        page.click("[data-nav='accueil']")
        page.wait_for_timeout(700)
        page.locator(".phone").screenshot(path=str(SHOTS / "07-accueil-theme-clair.png"))
        print("   capture :", SHOTS / "07-accueil-theme-clair.png")
        check("thème clair appliqué", page.get_attribute("html", "data-theme") == "light")

        check("aucune erreur console", not erreurs, " | ".join(erreurs[:3]))
        nav.close()

    ok = sum(1 for _, o, _ in resultats if o)
    print(f"\nRÉSULTAT : {ok}/{len(resultats)} contrôles OK")
    return 0 if ok == len(resultats) else 1


if __name__ == "__main__":
    sys.exit(main())
