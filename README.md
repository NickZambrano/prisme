# Prisme

Lecteur IPTV pour Android TV (et téléphone / tablette), pensé pour les contenus français : accueil façon Netflix,
Top 10 France, tendances et recommandations, TNT avec guide et direct en pause / retour en arrière.

Aucun abonnement n'est fourni : Prisme se connecte au compte IPTV (Xtream Codes ou playlist M3U) de chacun.

## Installer

1. Télécharger **Prisme.apk** depuis la [dernière version](../../releases/latest).
2. L'installer sur la TV (par exemple avec l'application « Downloader », ou en l'envoyant depuis un téléphone).
3. Au premier lancement, saisir son abonnement directement sur la TV ou depuis son téléphone (QR code).

Les versions suivantes s'installent ensuite toutes seules (vérification au démarrage puis toutes les 6 heures,
jamais pendant une lecture).

## Fonctions

- **Accueil** : Continuer à regarder, Pour toi (recommandations TMDB d'après l'historique du profil), Top 10 films et
  séries en France, tendances, Ma liste, matchs du jour, TNT et chaînes, avec aperçu vidéo en fond.
- **Films / Séries** : rangées par genre et catégorie, grilles, sagas, bandes-annonces (VF ou VOSTFR).
- **TV en direct** : TNT et guide des programmes, pause, retour en arrière, « Direct », replay quand le fournisseur
  le permet.
- **Lecteur** : choix de l'audio, des sous-titres et de la qualité, vignettes pendant l'avance rapide, reprise.
- **Profils** (avec code parental), synchronisation de l'historique par Google Drive, import de l'historique Netflix
  et Prime Video.
- **Fichiers** (option) : vidéos de la TV, clés et disques USB, partages réseau Windows / NAS (SMB).
- Filtre des contenus en français, en HD / FHD / 4K.

## Compiler

Prérequis : JDK 17 et SDK Android (le script `build-apk.sh` les cherche dans `~/.androidtv-build`).

```bash
cp secrets.properties.example secrets.properties   # puis remplir les clés
./build-apk.sh                                      # produit Prisme.apk (version publique)
./gradlew testDebugUnitTest                         # tests
```

Publication d'une version (GitHub CLI connecté) :

```bash
node tools/publish-update.mjs Prisme.apk <versionCode> <versionName> "notes"
```

## Structure

- `app/src/main/java/com/fluxfr/tv/data/` : catalogue Xtream / M3U, filtres, recommandations (TMDB), guide,
  historique, synchro Drive, mises à jour.
- `app/src/main/java/com/fluxfr/tv/player/` : lecteur (Media3), direct décalé, vignettes, replay.
- `app/src/main/java/com/fluxfr/tv/ui/` : écrans Compose (TV et tactile).
- `tools/` : publication des versions, export de l'historique Prime Video.

Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB.

## Licence

[MIT](LICENSE)
