# Bloqueur de pubs par DNS — spécification

## Objectif

Application Android personnelle de **blocage de la publicité, du traçage et des domaines
malveillants au niveau DNS**, sans root, avec des listes locales et aucune dépendance à un
service tiers.

## Décisions de cadrage (validées le 24/09/2026)

| # | Question | Décision | Conséquence technique |
|---|---|---|---|
| 1 | Mécanisme de blocage | **VPN local (`VpnService`)** + listes locales, sans root | permission `BIND_VPN_SERVICE`, service au premier plan, écran d'autorisation VPN Android, démarrage au boot, applications exclues |
| 2 | Sources des listes | **Liste de base embarquée** + **mise à jour à la demande** depuis des listes publiques (hagezi, StevenBlack) | parseur « format hosts » et « domaines bruts », liste en stockage interne, bouton « Mettre à jour », nb de règles + date affichés |
| 3 | Périmètre du filtrage | **Pubs + traqueurs + malwares** (liste complète type hagezi Pro) | une liste par défaut ; structure prévue pour en ajouter d'autres |
| 4 | Journal et statistiques | **Journal détaillé + statistiques par application** (classement, courbes) | base locale Room alimentée par le service, écrans Journal + Statistiques, purge manuelle |
| 5 | Utilisateurs et distribution | **Fabrice seul, son téléphone**, réglages techniques à l'écran ; **release GitHub** (APK signé) | pas d'« onboarding », pas de Play Store, APK versionné |
| 6 | Périmètre du premier livrable | **v1.0 complète d'un coup** : journal, statistiques par application avec courbes, liste noire, applications exclues, démarrage au boot | un seul lot livrable, APK plus tardif mais complet |

## Contraintes issues des habitudes Fabrice

- Kotlin + Jetpack Compose, Material 3 (thème clair **et** sombre).
- **Icône propre** à l'application, jamais reprise d'une autre app.
- Dépôt GitHub dédié, **release + APK signé** à chaque tag ; nom de fichier versionné
  (`AdBlockDns_2026-09-24_v1.0.0.apk`).
- **Local d'abord** : aucune donnée d'usage vers un serveur, aucun compte, aucune analytique.
- **Zéro configuration** au premier lancement : liste embarquée déjà active, un seul bouton
  à toucher pour activer la protection.
- L'humain garde la main : toute règle est visible, ajoutable et réversible en un tap.

## Architecture prévue

```
app/
├── ui/                    Compose, Material 3
│   ├── home/              interrupteur, compteurs du jour, état du tunnel
│   ├── journal/           requêtes bloquées, recherche, filtre par app, « autoriser »
│   ├── stats/             courbes (24 h / 7 j / 30 j), top applications, catégories
│   ├── lists/             listes actives, mise à jour, import de fichier local
│   ├── rules/             liste blanche / liste noire manuelles
│   └── settings/          DNS amont, boot, notifications, apps exclues, purge
├── vpn/                   VpnService, boucle de lecture des paquets DNS (UDP 53)
├── dns/                   parseur de requêtes/réponses DNS, cache, résolveur amont
├── filter/                moteur de filtrage : table de hachage des domaines,
│                          correspondance sous-domaine, liste blanche prioritaire
├── data/                  Room (journal, stats), DataStore (préférences),
│                          stockage des listes (fichier interne)
└── listupdater/           téléchargement + parsing des listes publiques
```

Points d'implémentation à ne pas rater :

- **Un seul domaine bloqué doit répondre vite** : table de hachage en mémoire (pas de
  requête SQL par paquet), chargement de la liste dans un `Set<String>` (plusieurs millions
  d'entrées : passer par un `HashSet` + tableau de suffixes pour les sous-domaines).
- **Sous-domaines** : bloquer `doubleclick.net` doit bloquer
  `ads.g.doubleclick.net` (correspondance par suffixe de labels).
- **Ne pas casser le reste** : seul le port 53 est intercepté ; le reste du trafic est
  relayé tel quel. Toute erreur inattendue doit laisser passer la requête (fail-open),
  jamais couper le réseau.
- **Batterie** : pas de boucle active ; lecture bloquante du descripteur du tunnel, taille
  de paquet maximale, `setBlocking(true)`.
- **Applications exclues** : `addDisallowedApplication(packageName)` pour les apps qui
  refusent de fonctionner derrière un VPN (banque, streaming, certains jeux).
- **Démarrage au boot** : `BOOT_COMPLETED` + relance du service si l'utilisateur l'a activé
  (l'autorisation VPN, elle, est déjà consentie par Android).
- **Anti-faux-positif** : la liste blanche manuelle est toujours prioritaire sur la liste
  noire, y compris pour un sous-domaine.

## Écrans et parcours

1. **Accueil** : interrupteur « Protection active », requêtes bloquées du jour, applications
   concernées, état du tunnel, liste utilisée (nom, nombre de règles, date de mise à jour),
   raccourcis : démarrer au boot, mettre à jour les listes.
2. **Journal** : requêtes bloquées les plus récentes (heure, domaine, application demandeuse,
   catégorie), recherche, filtre par application, bouton **« Autoriser »** qui ajoute le
   domaine en liste blanche et retire l'entrée du journal.
3. **Statistiques** : blocages par heure (24 h), par jour (7 j / 30 j), classement des
   applications, répartition pubs / traqueurs / malwares.
4. **Listes** : liste active, nombre de règles, dernière mise à jour, bouton « Mettre à jour »,
   choix de listes complémentaires, import d'un fichier local.
5. **Règles** : listes blanche et noire manuelles (domaines et jokers), réversibles.
6. **Réglages** : résolveur amont (système ou DoH), démarrage au boot, notifications
   persistantes, applications exclues du tunnel, purge des données, version.

## Comparaison des mécanismes (décision n°1)

| Approche | Retenue | Pourquoi |
|---|---|---|
| **VPN local (`VpnService`)** | **oui** | filtrage personnalisable, listes locales, statistiques, sans root |
| DNS privé système (DoT/DoH) | non | filtrage décidé par un tiers, aucune statistique locale |
| `/etc/hosts` | non | nécessite un appareil rooté |

## Définition du « fini » pour la v1.0

- L'APK s'installe, la protection s'active en un tap, la liste embarquée bloque
  effectivement les domaines publicitaires connus (vérifié sur des cas réels).
- Journal et statistiques se remplissent réellement ; « Autoriser » répare un site cassé
  sans redémarrer.
- Démarrage au boot et applications exclues fonctionnent.
- Aucune fuite : l'app n'envoie rien sur le réseau en dehors du téléchargement des listes.
- APK signé publié en release GitHub, nom de fichier versionné, icône dédiée.
