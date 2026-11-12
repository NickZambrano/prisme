# Architecture

Une seule activité Compose (`MainActivity` → `ui/App.kt`) pour toute l'interface, et une activité séparée pour la
lecture (`player/PlayerActivity`). Code sous `app/src/main/java/com/fluxfr/tv/`.

## Paquets

### `data/` : données et règles

| Thème | Fichiers |
|---|---|
| Fournisseur IPTV | `XtreamClient` (API `player_api.php`, messages d'erreur de connexion), `M3uParser`, `Net` (JSON en flux) |
| Catalogue | `CatalogRepository` (téléchargement, filtrage, assemblage ; `load()`), `CatalogCache` (démarrage instantané), `Models` |
| Filtre « français » | `ContentFilter` (FR + HD/FHD/4K, d'après les noms de catégories et de titres), `MultiAudio` + `MetaEnricher` (films MULTI : vérification des pistes audio) |
| Titres | `CatalogMatcher` (titre + année depuis un nom brut), `DisplayTitle` (titre affiché), `Genres`, `Sagas` |
| Direct | `Epg` (guide), `Tnt` (chaînes TNT et numéros), `EventChannels` (chaînes « événement » dont le nom contient le match et l'heure), `Matches` (matchs des 24 h), `Reminders` |
| Recommandations | `Tmdb`, `TmdbLists`, `Trending` (Top 10 France via AlloCiné), `Reco`, `RecoIgnore` |
| Profil | `Profiles` (code parental, profil Enfants), `ProgressStore` (reprise, SQLite), `Favorites`, `RecentChannels`, `HiddenCategories`, `NewEpisodes` |
| Réglages et synchro | `Settings`, `SettingsSync` (entre TV de la maison), `DriveSync` + `GoogleAuth` (Google Drive) |
| Imports | `NetflixImport`, `PrimeExport` (l'asset `prime-export.js` est une copie de `tools/prime-export.js`) |
| Réseau local | `Peers` (autres TV Prisme), `LanHttp`, `LanAuth`, `PhoneSetupServer` (saisie de l'abonnement depuis le téléphone par QR code) |
| Mises à jour | `Updater` (téléchargement, vérification de signature, installation `PackageInstaller`), `RemoteUpdate` (vérification sur GitHub), `Changelog` |
| Fichiers | `files/` : `MediaFiles`, `LocalStorage` (USB / stockage), `SmbModel` + `SmbClient` (partages réseau), `FilesStore` (partages enregistrés, reprise) |
| Divers | `MiniJson`, `AllocineTrailer` (bandes-annonces VF), `ReplayApps`, `LegacyCleanup` |

### `player/` : lecture

- `PlayerActivity` : Media3 ExoPlayer (+ décodeur audio FFmpeg), panneaux audio / sous-titres / qualité, liste
  des chaînes, `ChannelPreview`.
- Direct en pause / retour en arrière avec **une seule** connexion au fournisseur : `TimeshiftRecorder` enregistre
  le flux MPEG-TS dans `TimeshiftBuffer`, `TsScanner` et `TimeIndex` permettent de s'y déplacer, `Timeshift` relie
  le tout au lecteur.
- `ProviderConnections` : point de passage de toutes les connexions au fournisseur (respecte le nombre de
  connexions simultanées de l'abonnement, `AccountLimits`).
- `Catchup` (replay du fournisseur), `SeekThumbnails`, `AutoFrameRate`, `NightMode`, `ZapLoadControl`,
  `FilesDataSource` (`prisme-smb://…`).

### `tvhome/` : intégration Google TV

Rangée système « Continuer à regarder » (`WatchNextSync`, `WatchNextReceiver`), suggestions de la recherche
vocale (`SearchSuggestProvider`), liens profonds `prisme://movie/<id>` (voir aussi `DeepLinks.kt`).

### `ui/` : écrans Compose

- `App.kt` : rail de gauche (`Section` : Recherche, Accueil, Films, Séries, TNT & Guide, Toutes les chaînes,
  Fichiers si activé, Réglages), dialogues globaux (profil, nouveautés).
- `HomeScreen` + `HomeRows` (règles pures) + `HomeLivePreview`, `BrowseRows` / `BrowseScreens` (Films, Séries),
  `MovieDetailScreen`, `SeriesDetailScreen`, `SearchScreen`, `GuideScreen`, `TntScreen`, `FilesScreen`,
  `ProfileScreens`, `SetupScreen`, `settings/` (sections et panneaux des Réglages).
- `Components.kt` : vignettes (`PosterCard`), rangées (`shelf`, `PlaceholderShelf`), navigation entre rangées
  (`RowNav`).
- `CardMenu.kt` : menu d'actions à l'appui long. `WhatsNew.kt` : fenêtre des nouveautés. `PhoneUi.kt` +
  `FormFactor.kt` : variante tactile (téléphone / tablette).

## Flux principaux

**Démarrage** : `CatalogCache` affiche tout de suite le dernier catalogue, puis `CatalogRepository.load()` le
retélécharge s'il a plus de 12 h. Les rangées TMDB / Top 10 arrivent ensuite ; elles sont affichées vides
(`PlaceholderShelf`) pendant leur chargement pour que les rangées ne « sautent » pas sous l'utilisateur.

**Filtre français** : les fournisseurs ne donnent que des noms (« FR ‖ Cinéma », « ┃FR┃ TF1 HD », « MULTI-LANG »…).
`ContentFilter` décide sur ces noms ; tout nouveau format d'abonnement s'ajoute là, avec un test
(`ContentFilterTest`, `EventChannelsTest`). Les films MULTI dont la VF n'est pas prouvée vont dans la catégorie
« Multi-langues (VF non garantie) » (réglage « Films et séries multi-langues »).

**Mise à jour automatique (version publique)** :

1. `RemoteUpdate.start()` (depuis `MainActivity`) lit
   `https://github.com/<update.repo>/releases/latest/download/prisme-public.json` 20 s après le démarrage, puis
   toutes les 6 h, et au retour dans l'appli si la dernière vérification date de plus de 30 min.
2. Si la version est plus récente et du même type (`Flavour`), le bandeau en haut de l'accueil l'annonce et l'APK
   est téléchargé dans `filesDir`, contrôlé (SHA-256 du manifeste, même signature, même paquet).
3. L'installation se fait quand plus aucune activité Prisme n'est visible (jamais pendant une lecture), sans
   confirmation sur Android 12+ si Prisme est l'installateur de la version en place ; sinon le bandeau propose
   « Installer ».
4. Au lancement suivant, `Changelog.pending()` affiche les nouveautés depuis la version précédente.

La version personnelle n'a pas d'adresse de mise à jour : elle se met à jour depuis une autre TV de la maison
(`Peers` + `Updater.onPeers`).

## Pièges connus

- **Défilement vertical** : laisser Compose suivre le focus donnait des rangées sautées et des saccades en appui
  rapide. `RowNav` (`ui/Components.kt`) gère seul le passage d'une rangée à l'autre : position fixe de la rangée
  active, animation de 240 ms si la cible est visible, saut direct en cas d'appuis rapprochés (< 300 ms). Toute
  liste de rangées doit utiliser `rememberRowNav` + `rowsListState()` + `NoVerticalScrollSpec`.
- **Onglet Fichiers** : pendant un changement de dossier, le focus est « garé » sur un élément invisible ; sans cela
  le rail de gauche se rouvre. L'ancien contenu reste affiché jusqu'à l'arrivée du nouveau (« Chargement… » après
  350 ms seulement).
- **Tests** : pas de `org.json` ni d'API Android dans `app/src/test` (bouchons qui échouent) ; utiliser `MiniJson`.
- **Fuseau horaire** : les heures des chaînes événement sont en heure de Paris (`Europe/Paris`), y compris le format
  américain « Mon DD hh:mm AM/PM ».
- **Identifiants dans les adresses** : les URL Xtream contiennent utilisateur et mot de passe. Ne jamais les
  journaliser ni les écrire dans la synchro Drive (`DriveSync` les exclut).
