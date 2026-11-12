---
title: Règles de confidentialité – Prisme
---

# Règles de confidentialité

*Dernière mise à jour : 12 novembre 2026*

Prisme est une application libre, sans serveur propre, sans publicité ni outil de mesure d'audience. Le
développeur ne reçoit aucune donnée des utilisateurs.

## Données enregistrées sur l'appareil

- Identifiants de l'abonnement IPTV saisis par l'utilisateur.
- Profils, positions de lecture, historique, « Ma liste », réglages.
- Partages réseau ajoutés dans l'onglet Fichiers (et leurs identifiants).

Ces données restent sur l'appareil. Elles sont supprimées en désinstallant l'application ou en effaçant ses
données.

## Compte Google (facultatif)

Si l'utilisateur relie un compte Google à un profil, Prisme demande uniquement :

- `openid` et `email` : afficher dans les réglages quel compte est relié ;
- `drive.file` : créer et lire **ses propres fichiers** dans le Google Drive de l'utilisateur (fichier de
  synchronisation des profils, de l'historique et des réglages). Prisme n'a pas accès aux autres fichiers du Drive.

Les adresses de lecture, qui peuvent contenir les identifiants de l'abonnement, ne sont jamais écrites dans ce
fichier. Le jeton d'accès reste sur l'appareil et n'est envoyé qu'à Google. L'utilisation des données reçues des
API Google respecte la [politique relative aux données utilisateur des services API Google](https://developers.google.com/terms/api-services-user-data-policy),
y compris les exigences d'utilisation limitée.

L'utilisateur peut délier le compte dans les réglages de Prisme, ou retirer l'accès depuis
[myaccount.google.com/permissions](https://myaccount.google.com/permissions), et supprimer le fichier de
synchronisation de son Drive.

## Services tiers contactés

- **Le fournisseur IPTV choisi par l'utilisateur** : catalogue, guide et flux vidéo.
- **TMDB** et **AlloCiné** : affiches, recommandations, classements et bandes-annonces (titres recherchés,
  sans donnée personnelle).
- **GitHub** : vérification et téléchargement des mises à jour.
- **Google** : uniquement si un compte est relié (voir plus haut).

Ces services appliquent leurs propres règles de confidentialité.

## Réseau local

Pour la saisie depuis un téléphone et le partage des réglages entre les TV d'un même foyer, Prisme échange des
informations avec les autres appareils du réseau local. Rien ne sort du réseau domestique par ce biais.

## Contact

Questions ou demandes : [github.com/NickZambrano/prisme/issues](https://github.com/NickZambrano/prisme/issues).
