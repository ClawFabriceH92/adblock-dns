# Bloqueur de pubs par DNS — spécification

## Objectif

Application Android personnelle de **blocage de la publicité par DNS**, fonctionnant sans
root, avec des listes de blocage locales et sans dépendance à un service tiers.

## Contraintes issues des habitudes Fabrice

- Android natif Kotlin + Jetpack Compose (comme les autres apps).
- **Icône propre** à l'application (jamais réutilisée d'une autre app).
- Dépôt GitHub dédié, livraison par **release + APK signé**, tag à chaque version.
- Nom de fichier livré avec numéro de version (`AdBlockDns_2026-09-24_v1.0.0.apk`).
- **Local d'abord** : aucune donnée d'usage envoyée à un serveur, pas de compte.
- **Zéro configuration** au premier lancement : valeurs par défaut mémorisées, champs
  techniques repliés derrière un « Modifier ».
- L'humain garde la main : toute règle ajoutée automatiquement est visible et réversible.
- Shippé > parfait : premier livrable = APK qui bloque réellement les pubs.

## Décisions de cadrage (à compléter)

| # | Question | Décision | Conséquence technique |
|---|---|---|---|
| 1 | Mécanisme de blocage | **VPN local (`VpnService`)** + listes de blocage locales, sans root | permission `BIND_VPN_SERVICE`, service au premier plan, écran d'autorisation VPN Android, options « démarrer au boot » et « applications exclues » |
| 2 | Sources des listes de blocage et format | _en attente_ | parseur, taille embarquée, fréquence de MAJ |
| 3 | Périmètre : pubs seules ou pubs + traqueurs + malwares | _à venir_ | choix de listes, mode strict |
| 4 | Journal : historique des blocages ou compteur seul | _à venir_ | stockage local, écran de statistiques |
| 5 | Règles manuelles (liste blanche / noire) | _à venir_ | écran de règles, persistance |

## Comparaison technique des mécanismes (question 1)

### A. VPN local (`VpnService`) — recommandé

- L'app crée un tunnel local : tout le trafic passe par elle **sur l'appareil**, rien ne
  sort vers un serveur distant.
- Elle répond aux requêtes DNS (port 53) : domaine dans la liste noire → réponse vide/NXDOMAIN.
- Le reste du trafic est transmis normalement (pas de proxy, pas de déchiffrement).
- Avantages : filtrage personnalisable, listes locales, statistiques par domaine, sans root.
- Inconvénients : icône « VPN » dans la barre d'état, incompatible avec un autre VPN actif,
  gestion du cycle de vie du service (démarrage au boot optionnel).

### B. DNS privé système (DoT/DoH)

- On pousse l'utilisateur à configurer « DNS privé » vers un résolveur filtrant
  (AdGuard DNS, NextDNS, etc.).
- Avantages : quasi aucune ligne de code, pas de VPN.
- Inconvénients : filtrage décidé par un tiers (donc pas « local d'abord »), pas de liste
  personnelle, pas de statistiques locales, configuration manuelle à chaque changement de
  réseau ou de téléphone. **Retenu seulement comme mode complémentaire.**

### C. Fichier `/etc/hosts` (root)

- Écarté : nécessite un appareil rooté, redémarrage à chaque mise à jour de liste.

### Variante utile : résolveur amont

Dans l'approche A, l'app peut elle-même interroger un résolveur amont (DNS classique,
DoH ou DoT) pour les domaines autorisés. Choix par défaut à trancher (question 2/3) :
DNS système actuel du téléphone (le plus simple, rien à configurer) ou DoH chiffré.

## Écrans envisagés (à valider par maquette)

1. **Accueil** : gros interrupteur Activé/Désactivé, compteur de requêtes bloquées du jour,
   état du VPN, bouton « Démarrer au boot ».
2. **Journal** : dernières requêtes bloquées (domaine, application demandeuse, heure),
   recherche, ajout en liste blanche en un tap.
3. **Listes** : listes actives, nombre de règles, mise à jour, import d'un fichier local.
4. **Règles** : liste blanche / liste noire manuelles (domaines + caractères génériques).
5. **Réglages** : résolveur amont, blocage strict, journalisation on/off, purge des données.

## Périmètre du premier livrable (v1.0)

- VPN local actif/inactif, une liste par défaut embarquée, compteur + journal simple,
  liste blanche manuelle, APK signé publié en release GitHub.
  _À confirmer par Fabrice._

## Risques et points de vigilance

- **Consommation batterie** : le service DNS doit être léger (pas de boucle de lecture
  permanente ; approche « réponse aux paquets DNS uniquement »).
- **Détection par les applications** : certaines apps détectent un VPN et refusent de
  fonctionner (banque, streaming). Prévoir un mode « applications exclues du tunnel ».
- **Faux positifs** : une liste trop large casse des sites (liens de redirection, CDN).
  D'où la liste blanche en un tap depuis le journal.
- **Play Store** : une app qui filtre le DNS via VPNService est acceptée, mais la
  distribution visée reste la release GitHub.
