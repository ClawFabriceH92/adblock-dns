# Suite de la v1.0 : vérifications restantes et propositions d'amélioration

État au 24/09/2026. Ce qui est vérifié et ce qui ne l'est pas figure dans le README
(section « Vérifications »). Ce document liste ce qui reste à faire, par ordre de priorité.

## 1. À vérifier sur le téléphone (priorité absolue)

Rien de ce qui suit n'a pu être vérifié sans l'appareil réel. APK de développement :
artefact `apk-debug` de la dernière exécution de la CI (onglet Actions du dépôt).

| # | Vérification | Comment | Attendu |
|---|---|---|---|
| 1 | Activation | Accueil › « Activer la protection », accepter la demande VPN d'Android | icône clé dans la barre d'état, « Protection active » |
| 2 | Blocage réel | naviguer sur un site d'information chargé en publicités | encarts vides, lignes dans le Journal |
| 3 | Attribution | Journal : colonne application | noms réels des applications (selon la version d'Android, sinon « Système Android » ou « Application inconnue ») |
| 4 | « Autoriser » | autoriser un domaine d'un site cassé, recharger la page | le site fonctionne, sans redémarrer la protection (déjà vérifié sur émulateur) |
| 5 | Démarrage au boot | redémarrer le téléphone | protection relancée seule |
| 6 | Applications exclues | exclure l'application bancaire | elle fonctionne ; ses requêtes n'apparaissent plus |
| 7 | Changement de réseau | passer du Wi-Fi au réseau mobile et inversement | la navigation continue |
| 8 | Résolveurs chiffrés | Réglages › Cloudflare puis Quad9 | navigation normale |
| 9 | DNS privé | Réglages Android › DNS privé : « Automatique » ou « Désactivé » | blocage effectif ; bandeau d'alerte si un nom d'hôte est imposé |
| 10 | Autonomie | Réglages Android › Batterie, après 24 h | consommation faible (aucune boucle active dans le service) |
| 11 | Mise à jour | Listes › « Mettre à jour les listes » | nombre de domaines et date mis à jour |

## 2. À faire avant la première release

- **Créer la clé de signature et les quatre secrets** (voir README). Sans eux, le job de release
  échoue volontairement avec un message explicite. Sauvegarder la clé hors du dépôt : sa perte
  empêche toute mise à jour de l'application installée.
- **Choisir une licence** pour le code. L'instantané hagezi embarqué dans l'APK est sous GPL-3.0 ;
  une licence compatible (GPL-3.0) simplifierait la redistribution. Point juridique à confirmer :
  je ne suis pas juriste.

## 3. Défauts trouvés en cours de route (corrigés)

| Défaut | Trouvé par | Correction |
|---|---|---|
| Liste embarquée absente de l'APK : l'asset `hagezi-pro.txt.gz` était introuvable, la protection démarrait avec 0 règle et ne bloquait rien | test sur émulateur (5/10) | asset renommé en `.gzip`, gzip reconnu à sa signature, contrôle de l'APK en CI, alerte « Aucune règle de blocage » à l'accueil |
| DoH Quad9 en échec : HTTP/1.1 retiré par Quad9 le 15/12/2025 | test contre les vrais services (CI) | client OkHttp (HTTP/2) |
| Plage `192.0.2.0/24` déjà utilisée par le réseau du conteneur de test : les paquets n'arrivaient pas au tunnel | test TUN Linux | plage du tunnel choisie parmi celles qui sont libres, dans l'application aussi |
| Inspection CNAME hors du bloc protégé : une erreur du filtre pouvait remonter au lieu de laisser passer la réponse (fail-open incomplet) | relecture du code, avant publication | inspection entièrement protégée |
| Règles cosmétiques adblock (`exemple.com##.pub`) lues comme des domaines | relecture du code, avant publication | rejetées |
| `versionCode` non croissant si mineure ou correctif ≥ 100 | relecture de la CI | tag refusé avec un message explicite |
| Schéma Room annoncé dans l'artefact « rapports » sans vérification ; sa présence n'y est pas garantie (tâche `copyRoomSchemas` « NO-SOURCE » pendant le lint) | journal de la CI | schéma versionné dans `app/schemas/`, la CI vérifie qu'il reste à jour |
| Maquette : saisie inversée, perte du focus, injection HTML, « ✕ » sans effet | vérification Playwright | corrigés (24/24) |

## 4. Propositions d'amélioration

Classées par rapport utilité / effort. Aucune n'est implémentée.

1. **Mise à jour automatique activée par défaut** (hebdomadaire, Wi-Fi). Les en-têtes des listes
   hagezi annoncent « Expires: 8 hours » : un instantané vieux de plusieurs mois perd en
   efficacité. Elle est désactivée par défaut pour respecter la décision de cadrage n° 2 ; c'est
   un réglage à changer si vous êtes d'accord.
2. **Liste optionnelle « DoH/VPN/TOR/Proxy Bypass » de hagezi** (16 471 entrées selon son README) :
   elle bloque les serveurs DNS chiffrés publics, ce qui oblige les applications qui en utilisent
   un à repasser par le filtre. L'application elle-même, exclue de son tunnel, n'est pas gênée pour
   ses propres résolveurs Cloudflare ou Quad9.
3. **Tuile des réglages rapides** et **pause temporaire** (15 min, 1 h) pour dépanner un site sans
   aller dans l'application.
4. **Tests de l'interface et des ViewModels** (Robolectric, tests Compose) : le module `app` n'a
   qu'une classe de tests unitaires (fenêtres des statistiques) ; le reste n'est couvert que par
   la compilation, le lint et le test sur émulateur.
5. **Interception des DNS codés en dur** (8.8.8.8, 1.1.1.1…) : router ces adresses dans le tunnel
   pour filtrer aussi les applications qui ignorent le DNS du système. Effort réel : il faut
   laisser passer ou refuser proprement le reste de leur trafic (HTTPS vers ces adresses).
6. **Export du journal** (CSV) et écran de détail par application.
7. **DNS sur TCP dans le tunnel** : aujourd'hui refusé par un RST (échec immédiat) ; utile
   seulement pour les très grosses réponses DNS, que le résolveur amont traite déjà en TCP.
8. **Résolveur DoH personnalisé** (URL libre) et DNS-over-TLS.
9. **Widget** d'écran d'accueil avec le compteur du jour.
10. **Passage à targetSdk 37** quand les changements de comportement d'Android 17 auront été
    passés en revue (compileSdk est déjà à 37).

## 5. Sources utilisées

- Formats, tailles et licence des listes : [README de hagezi/dns-blocklists](https://github.com/hagezi/dns-blocklists)
  et en-têtes des fichiers téléchargés le 24/09/2026 ; [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (licence MIT).
- Retrait du HTTP/1.1 par Quad9 : [« DOH HTTP/1.1 Retirement December 15, 2025 »](https://quad9.net/news/blog/doh-http-1-1-retirement/) ;
  confirmé par l'échec du test réel en HTTP/1.1 dans la CI.
- VPN considéré « facturé à l'usage » par défaut (API 29+) : [VpnService.Builder.setMetered](https://developer.android.com/reference/android/net/VpnService.Builder#setMetered(boolean)).
- DNS privé : [LinkProperties.getPrivateDnsServerName / isPrivateDnsActive](https://developer.android.com/reference/android/net/LinkProperties).
- Attribution des requêtes : [ConnectivityManager.getConnectionOwnerUid](https://developer.android.com/reference/android/net/ConnectivityManager#getConnectionOwnerUid(int,%20java.net.InetSocketAddress,%20java.net.InetSocketAddress)).
- `DnsResolver.getInstance()` déprécié en API 37 : [DnsResolver](https://developer.android.com/reference/android/net/DnsResolver).
- Domaine canari de Firefox : code source de Firefox, `toolkit/components/doh/DoHHeuristics.sys.mjs`
  ([mozilla-firefox/firefox](https://github.com/mozilla-firefox/firefox)).
- Type de service au premier plan d'un VPN : précédent de [NetGuard](https://github.com/M66B/NetGuard)
  (`foregroundServiceType="specialUse"`, targetSdk 36).
- Versions des outils : [android/nowinandroid](https://github.com/android/nowinandroid),
  [android/snippets](https://github.com/android/snippets), [android/compose-samples](https://github.com/android/compose-samples).
- Émulateur en CI : [ReactiveCircus/android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner).
- Assets `.gz` : code d'aapt (`frameworks/base/tools/aapt/Package.cpp`, extension retirée et contenu
  décompressé) cité dans [sweetalert2/sweetalert2#347](https://github.com/sweetalert2/sweetalert2/issues/347)
  (11/2016) ; extension retirée avec le plugin Gradle récent : [ionic-team/capacitor#5844](https://github.com/ionic-team/capacitor/issues/5844)
  (08/2022) ; constaté ici sur émulateur (`FileNotFoundException`).
