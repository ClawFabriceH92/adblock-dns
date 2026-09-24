# Bloqueur de pubs par DNS (Android)

Application Android qui bloque la publicité **au niveau DNS**, sans root : les requêtes
vers les domaines publicitaires et de traçage n'aboutissent jamais, donc les pubs ne se
chargent ni dans le navigateur, ni dans les applications.

> Statut : **cadrage en cours** (24/09/2026). Aucun code applicatif avant validation du
> périmètre et de l'interface. Décisions prises : `docs/SPEC.md`.

## Objectif

- Bloquer les pubs, bannières, interstitiels et traqueurs sur tout l'appareil (pas
  seulement dans un navigateur).
- Fonctionner **sans root** et **sans compte** : aucune donnée ne quitte le téléphone.
- Listes de blocage locales, mises à jour à la demande ou automatiquement.
- Zéro configuration au premier lancement : une liste par défaut raisonnable, un bouton
  d'activation, et c'est tout.

## Principe (à valider)

Le téléphone utilise un résolveur DNS qui, avant de répondre, compare le domaine demandé
à une liste de domaines publicitaires. Si le domaine est dans la liste, la réponse est
« domaine inexistant » : la pub ne peut pas se charger.

Deux familles de mise en œuvre, détaillées dans `docs/SPEC.md` :

| Approche | Principe | Root | Limite |
|---|---|---|---|
| **VPN local** | L'app crée un VPN local (Android `VpnService`) et traite le DNS elle-même | non | icône VPN affichée, une seule app VPN à la fois |
| **DNS privé** | On configure le DNS-over-TLS du système vers un résolveur filtrant | non | filtrage non personnalisable, dépend d'un service tiers |
| **/etc/hosts** | Fichier hosts modifié + redémarrage | oui | à exclure : appareils non rootés |

## Livraison

- Kotlin + Jetpack Compose, une **icône dédiée** à l'application.
- APK signé publié en **release GitHub** à chaque tag (même chaîne que les autres apps).
- Dépôt public par défaut (peut être passé en privé sur demande).

## Développement

À venir : structure Gradle, module `app`, tests unitaires sur le moteur de filtrage.

```bash
# (à compléter)
```

## Licence

Usage personnel de Fabrice Heuvrard. Licence à définir.
