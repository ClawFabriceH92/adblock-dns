#!/usr/bin/env python3
"""Test de bout en bout du moteur DNS sur une vraie interface TUN Linux.

Le moteur (module :core) reçoit les paquets exactement comme sur Android : par le descripteur
d'une interface TUN. Le noyau Linux vérifie les sommes de contrôle IP/UDP/TCP des réponses
injectées ; un client DNS indépendant (dnspython) interroge le serveur DNS virtuel et compare
chaque réponse à ce qu'attend une correspondance par suffixe calculée ici, en Python, sur le
texte brut de la liste.

Prérequis : Linux, root (ou CAP_NET_ADMIN), /dev/net/tun, `pip install dnspython`, et le
classpath de test : ./gradlew :core:writeTestClasspath

Usage : sudo python3 tools/test_tun_linux.py [--liste FICHIER] [--classpath FICHIER]
"""
from __future__ import annotations

import argparse
import fcntl
import os
import pathlib
import random
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request

import dns.flags
import dns.message
import dns.query
import dns.rcode
import dns.rdatatype

ROOT = pathlib.Path(__file__).resolve().parent.parent
LISTE_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt"
IFACE = b"adbtest0"
# Plages de documentation (RFC 5737) : on prend la première absente de la table de routage,
# comme l'application le fait pour son serveur DNS virtuel.
PLAGES_V4 = ["198.51.100", "203.0.113", "192.0.2"]
TUN_V6, DNS_V6 = "fd00:adb::1", "fd00:adb::2"      # adresses locales uniques
BLOQUE, AUTORISE = "NXDOMAIN", "NOERROR"

TUNSETIFF, IFF_TUN, IFF_NO_PI = 0x400454CA, 0x0001, 0x1000
SIOCSIFADDR, SIOCSIFNETMASK, SIOCGIFFLAGS, SIOCSIFFLAGS = 0x8916, 0x891C, 0x8913, 0x8914
IFF_UP, IFF_RUNNING = 0x1, 0x40

resultats: list[tuple[str, bool, str]] = []


def check(nom: str, ok: bool, detail: str = "") -> bool:
    resultats.append((nom, bool(ok), detail))
    print(("  OK   " if ok else "  ÉCHEC") + f" {nom}" + (f" : {detail}" if detail else ""), flush=True)
    return bool(ok)


def plage_libre() -> str:
    utilisees = set()
    for ligne in open("/proc/net/route").read().splitlines()[1:]:
        champs = ligne.split()
        reseau = socket.inet_ntoa(struct.pack("<I", int(champs[1], 16)))
        utilisees.add(reseau.rsplit(".", 1)[0])
    return next(p for p in PLAGES_V4 if p not in utilisees)


PLAGE = plage_libre()
TUN_V4, DNS_V4 = f"{PLAGE}.1", f"{PLAGE}.2"


def creer_tun() -> int:
    fd = os.open("/dev/net/tun", os.O_RDWR)
    fcntl.ioctl(fd, TUNSETIFF, struct.pack("16sH", IFACE, IFF_TUN | IFF_NO_PI))
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    def ifreq_v4(adresse: str) -> bytes:
        return struct.pack("16sH2s4s8s", IFACE, socket.AF_INET, b"\0" * 2, socket.inet_aton(adresse), b"\0" * 8)

    fcntl.ioctl(s, SIOCSIFADDR, ifreq_v4(TUN_V4))
    fcntl.ioctl(s, SIOCSIFNETMASK, ifreq_v4("255.255.255.0"))
    drapeaux = struct.unpack("16sH", fcntl.ioctl(s, SIOCGIFFLAGS, struct.pack("16sH", IFACE, 0)))[1]
    fcntl.ioctl(s, SIOCSIFFLAGS, struct.pack("16sH", IFACE, drapeaux | IFF_UP | IFF_RUNNING))
    s.close()
    try:  # IPv6 : facultatif selon le noyau / conteneur
        s6 = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
        index = socket.if_nametoindex(IFACE.decode())
        fcntl.ioctl(s6, SIOCSIFADDR, struct.pack("16sIi", socket.inet_pton(socket.AF_INET6, TUN_V6), 64, index))
        s6.close()
    except OSError as e:
        print(f"   (IPv6 indisponible sur cette machine : {e})")
    return fd


def correspond(domaine: str, liste: set[str]) -> bool:
    """Correspondance par suffixe de labels, écrite indépendamment du moteur Kotlin."""
    labels = domaine.split(".")
    return any(".".join(labels[i:]) in liste for i in range(len(labels) - 1))


def interroger(nom: str, serveur: str = DNS_V4, type_: str = "A", edns: bool = False) -> tuple[str, list[str], float]:
    requete = dns.message.make_query(nom, type_, use_edns=0 if edns else None)
    debut = time.perf_counter()
    reponse = dns.query.udp(requete, serveur, timeout=2)  # vérifie identifiant et question
    duree = (time.perf_counter() - debut) * 1000
    adresses = [r.to_text() for rrset in reponse.answer for r in rrset if rrset.rdtype == dns.rdatatype.A]
    return dns.rcode.to_text(reponse.rcode()), adresses, duree


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--liste", help="liste au format domaines (défaut : hagezi Pro téléchargée)")
    parser.add_argument("--classpath", default=str(ROOT / "core/build/test-classpath.txt"))
    args = parser.parse_args()

    if os.geteuid() != 0 or not os.path.exists("/dev/net/tun"):
        print("Il faut être root et disposer de /dev/net/tun.")
        return 2
    classpath = pathlib.Path(args.classpath).read_text().strip()
    with tempfile.TemporaryDirectory() as tmp:
        liste_path = args.liste
        if not liste_path:
            liste_path = os.path.join(tmp, "pro.txt")
            print(f"Téléchargement de {LISTE_URL}")
            with urllib.request.urlopen(LISTE_URL, timeout=60) as r, open(liste_path, "wb") as f:
                shutil.copyfileobj(r, f)
        liste = {l.strip() for l in open(liste_path, encoding="utf-8") if l.strip() and not l.startswith("#")}
        print(f"Liste : {len(liste)} domaines")

        hasard = random.Random(20260924)
        echantillon = hasard.sample(sorted(liste), 300)
        # Parent non listé d'une entrée listée : doit rester autorisé (pas de blocage « vers le haut »).
        parents = sorted({e.split(".", 1)[1] for e in echantillon if e.count(".") >= 2 and not correspond(e.split(".", 1)[1], liste)})[:100]
        blanche = echantillon[0]
        noire = "pub-manuelle.example"
        populaires = ["wikipedia.org", "github.com", "lemonde.fr", "impots.gouv.fr", "service-public.fr",
                      "openstreetmap.org", "debian.org", "kernel.org", "python.org", "mozilla.org"]
        populaires = [d for d in populaires if not correspond(d, liste)]
        cible_cname = next(e for e in echantillon[1:] if e.count(".") == 1) if any(e.count(".") == 1 for e in echantillon[1:]) else echantillon[1]

        tun = creer_tun()
        harness = subprocess.Popen(
            ["java", "-cp", classpath, "io.github.clawfabriceh92.adblockdns.core.TunHarnessKt",
             liste_path, blanche, noire, cible_cname],
            stdin=tun, stdout=tun, stderr=subprocess.PIPE, text=True,
        )
        try:
            # La JVM peut d'abord écrire ses propres messages (« Picked up JAVA_TOOL_OPTIONS… »).
            pret = ""
            while not pret.startswith("PRET"):
                ligne = harness.stderr.readline()
                if not ligne:
                    break
                pret = ligne.strip()
            print(f"Moteur : {pret}")
            check("moteur démarré sur la TUN", pret.startswith("PRET"), pret)

            duree_totale, n = 0.0, 0
            erreurs = []
            for d in echantillon[1:]:
                rcode, _, ms = interroger(d)
                duree_totale += ms; n += 1
                if rcode != BLOQUE: erreurs.append(d)
            check("domaines de la liste bloqués (NXDOMAIN)", not erreurs, f"{len(echantillon) - 1} requêtes, erreurs : {erreurs[:3]}")

            erreurs = [d for d in echantillon[1:101] if interroger(f"sous.{d}")[0] != BLOQUE]
            check("sous-domaines des entrées bloqués", not erreurs, f"100 requêtes, erreurs : {erreurs[:3]}")

            erreurs = [d for d in parents if interroger(d)[0] != AUTORISE]
            check("parents non listés autorisés", not erreurs, f"{len(parents)} requêtes, erreurs : {erreurs[:3]}")

            ok = all(interroger(d)[:2] == (AUTORISE, ["203.0.113.53"]) for d in populaires)
            check("domaines courants relayés à l'amont", ok, ", ".join(populaires[:4]) + "…")

            check("liste blanche prioritaire", interroger(blanche)[0] == AUTORISE, blanche)
            check("liste blanche : sous-domaine aussi", interroger("x." + blanche)[0] == AUTORISE)
            check("liste noire manuelle", interroger("video." + noire)[0] == BLOQUE)
            check("CNAME vers un domaine listé bloqué", interroger("cloaked.exemple.org")[0] == BLOQUE, f"cible x.{cible_cname}")
            check("domaine canari Firefox bloqué", interroger("use-application-dns.net")[0] == BLOQUE)
            check("requête EDNS", interroger(echantillon[1], edns=True)[0] == BLOQUE)
            check("requête AAAA bloquée", interroger(echantillon[2], type_="AAAA")[0] == BLOQUE)

            debut = time.perf_counter()
            try:
                socket.create_connection((DNS_V4, 53), timeout=3).close()
                refus = "connexion acceptée"
            except ConnectionRefusedError:
                refus = "refusée"
            except OSError as e:
                refus = f"{type(e).__name__}"
            ms = (time.perf_counter() - debut) * 1000
            check("DNS sur TCP refusé immédiatement (RST)", refus == "refusée" and ms < 1000, f"{refus} en {ms:.0f} ms")

            try:
                rcode6, adr6, _ = interroger("wikipedia.org", serveur=DNS_V6)
                check("IPv6 : réponse relayée", rcode6 == AUTORISE and adr6 == ["203.0.113.53"])
                check("IPv6 : domaine bloqué", interroger(echantillon[3], serveur=DNS_V6)[0] == BLOQUE)
            except OSError as e:
                print(f"   (IPv6 non testé : {e})")

            print(f"   latence moyenne (domaines bloqués) : {duree_totale / max(n, 1):.2f} ms")
        finally:
            harness.terminate()
            try:
                harness.wait(timeout=5)
            except subprocess.TimeoutExpired:
                harness.kill()
            os.close(tun)

    ok = sum(1 for _, o, _ in resultats if o)
    print(f"\nRÉSULTAT : {ok}/{len(resultats)} contrôles OK")
    return 0 if ok == len(resultats) else 1


if __name__ == "__main__":
    sys.exit(main())
