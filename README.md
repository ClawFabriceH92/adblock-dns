# Bloqueur de pubs par DNS (Android)

Application Android qui bloque la publicité **au niveau DNS**, sans root : les requêtes
vers les domaines publicitaires et de traçage n'aboutissent jamais, donc les pubs ne se
chargent ni dans le navigateur, ni dans les applications.

> Statut (24/09/2026) : **v1.0 écrite, compilée et testée en intégration continue** (moteur,
> test de bout en bout sur interface TUN Linux, émulateur Android). **Pas encore essayée sur le
> téléphone réel** : voir [Vérifications](#vérifications) et la liste de contrôle de
> [docs/AMELIORATIONS.md](docs/AMELIORATIONS.md). Décisions de cadrage : [docs/SPEC.md](docs/SPEC.md).

## Ce que fait l'application

- **Accueil** : interrupteur de protection, requêtes bloquées du jour, applications concernées,
  règles chargées, démarrage au boot, alertes (DNS privé Android en mode strict, erreur).
- **Journal** : requêtes bloquées (heure, domaine, application, type, catégorie), recherche,
  filtre par application, bouton **« Autoriser »** (liste blanche immédiate, sans redémarrage)
  avec **« Annuler »**.
- **Stats** : blocages par heure (24 h) ou par jour (7 j / 30 j), applications les plus bloquées,
  répartition par catégorie.
- **Listes** : hagezi Multi PRO embarquée (active d'office), hagezi Threat Intelligence et
  StevenBlack en option, import d'un fichier (domaines, hosts ou adblock), mise à jour à la
  demande ou hebdomadaire en Wi-Fi (option).
- **Règles** : listes blanche et noire manuelles, jokers (`*.exemple.com`), URL collée acceptée.
- **Réglages** : résolveur amont (DNS du téléphone, Cloudflare ou Quad9 chiffrés), démarrage au
  boot, anti-camouflage CNAME, applications exclues du tunnel, purge du journal, accès au VPN
  permanent d'Android.
- Raccourci « Activer la protection » (appui long sur l'icône), thème clair et sombre, icône
  dédiée (adaptative et monochrome).

Local d'abord : aucun compte, aucune statistique d'usage, sauvegarde cloud désactivée. Le seul
trafic émis par l'application est le téléchargement des listes et la résolution des domaines
autorisés (par le résolveur choisi).

## Comment ça marche

```
 application ──DNS──▶ serveur DNS virtuel (198.51.100.2, dans le tunnel)
                          │  lecture poll(2), sans attente active
                          ▼
                 DnsPacketProcessor (module core)
         domaine bloqué ? ──oui──▶ réponse NXDOMAIN immédiate + journal
                          │ non
                          ▼
       résolveur amont (résolveur d'Android ou DNS-over-HTTPS) ──▶ réponse relayée
```

- Le VPN local ne route **que** l'adresse du serveur DNS virtuel : tout le reste du trafic passe
  normalement, hors tunnel. L'application s'exclut elle-même du tunnel.
- La plage d'adresses du tunnel est choisie parmi des plages de documentation (RFC 5737) non
  utilisées sur l'appareil.
- **Fail-open** : message DNS inconnu ou erreur interne → la requête est transmise telle quelle ;
  panne du résolveur → réponse SERVFAIL immédiate plutôt qu'un silence.
- Filtrage : empreintes 64 bits triées (8 octets par domaine, recherche par dichotomie), un
  domaine bloque ses sous-domaines, la liste blanche est toujours prioritaire, puis le domaine
  canari de Firefox, la liste noire manuelle et les listes (malveillants d'abord).
- **Anti-camouflage CNAME** : un domaine autorisé dont l'alias mène à un traqueur connu est bloqué.
- DNS sur TCP et DNS-over-TLS vers le serveur virtuel sont refusés par un RST (échec immédiat).

Structure :

| Module | Contenu | Testé où |
|---|---|---|
| `core/` | Kotlin pur : paquets IPv4/IPv6/UDP/TCP, messages DNS, moteur de filtrage, parseurs de listes, téléchargement conditionnel, résolveurs UDP et DoH | JVM (79 tests) + TUN Linux |
| `app/` | Android : `VpnService`, Room (journal, règles, listes), DataStore (réglages), WorkManager, Compose Material 3 | CI (compilation, lint) + émulateur |
| `mockup/` | Maquette cliquable du cadrage | `tools/verifier_maquette.py` (Playwright) |
| `tools/` | Test TUN Linux, test sur émulateur, mise à jour de la liste embarquée, vérification de la maquette | — |

## Développement

Prérequis : JDK 17 ou plus, SDK Android (Android Studio). Versions : `gradle/libs.versions.toml`.

```bash
./gradlew :core:test                       # moteur (JVM, sans SDK Android)
./gradlew :core:test -PnetworkTests=true   # + vraies listes publiques et vrais résolveurs DoH
./gradlew :app:assembleDebug               # APK de développement
./gradlew :app:lintDebug                   # lint Android

# Test de bout en bout sur une interface TUN (Linux, root) :
./gradlew :core:writeTestClasspath && sudo python3 tools/test_tun_linux.py

# Test sur émulateur ou appareil connecté (adb) :
python3 tools/test_emulateur.py app/build/outputs/apk/debug/app-debug.apk

# Rafraîchir l'instantané de la liste embarquée dans l'APK :
tools/maj_liste_embarquee.sh
```

## Release signée

Un tag `vX.Y.Z` déclenche la CI : APK release signé, vérifié par `apksigner`, publié en release
GitHub sous le nom `AdBlockDns_<date>_vX.Y.Z.apk` avec son empreinte SHA-256. Le numéro de version
de l'APK est tiré du tag.

Secrets à créer une fois dans le dépôt (Settings › Secrets and variables › Actions) :

| Secret | Contenu |
|---|---|
| `ADBLOCK_KEYSTORE_BASE64` | le fichier `.jks` encodé en base64 (`base64 -w0 release.jks`) |
| `ADBLOCK_KEYSTORE_PASSWORD` | mot de passe du keystore |
| `ADBLOCK_KEY_ALIAS` | alias de la clé |
| `ADBLOCK_KEY_PASSWORD` | mot de passe de la clé |

Création d'une clé (à conserver précieusement : sans elle, impossible de publier une mise à jour
installable par-dessus l'ancienne version) :

```bash
keytool -genkeypair -v -keystore release.jks -alias adblockdns -keyalg RSA -keysize 4096 -validity 10000
```

En local, un fichier `keystore.properties` (non versionné) avec `storeFile`, `storePassword`,
`keyAlias`, `keyPassword` permet `./gradlew :app:assembleRelease`.

## Vérifications

| Vérification | Résultat | Où |
|---|---|---|
| Tests unitaires du moteur | 79 tests, 0 échec | local et CI |
| Vraies listes : hagezi Pro, TIF medium, StevenBlack | nombre de domaines compilés = nombre annoncé dans l'en-tête ; mise à jour conditionnelle (HTTP 304) confirmée | local et CI |
| Moteur branché sur une vraie interface TUN, client DNS indépendant (dnspython) | 15/15 contrôles, IPv4 et IPv6, sommes de contrôle acceptées par le noyau, latence ~0,3 ms | CI (13/13 en local, sans IPv6) |
| Compilation Android, lint | réussis | CI |
| Émulateur Android : tunnel monté, domaines listés bloqués, autres domaines résolus | voir le dernier passage de la CI | CI |
| Maquette | 24/24 contrôles | local |
| **Téléphone réel** (boot, applications exclues, attribution par application, autonomie) | **non vérifié** | — |

## Limites connues

- **DNS privé d'Android en mode strict** : les applications peuvent alors envoyer leurs requêtes
  chiffrées directement au serveur choisi, sans passer par le filtre. L'accueil le signale.
- Une application qui utilise son propre DNS chiffré (DoH/DoT codé en dur) échappe au filtre ;
  Firefox est prévenu par le domaine canari `use-application-dns.net`.
- Les applications exclues du tunnel ne sont ni filtrées ni journalisées.
- Une seule application VPN peut être active à la fois sur Android.
- Ne pas activer « Bloquer les connexions sans VPN » : seul le DNS passe par ce tunnel.
- Attribution des requêtes aux applications : dépend de la version d'Android (à vérifier sur le
  téléphone) ; à défaut, « Application inconnue » ou « Système Android ».

## Licences

- Code de l'application : usage personnel de Fabrice Heuvrard, licence à définir.
- Listes : [hagezi/dns-blocklists](https://github.com/hagezi/dns-blocklists) (GPL-3.0),
  [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (MIT). L'instantané hagezi embarqué
  dans l'APK reste sous GPL-3.0.
- Bibliothèques : AndroidX (Apache-2.0), OkHttp (Apache-2.0), Kotlin (Apache-2.0).
