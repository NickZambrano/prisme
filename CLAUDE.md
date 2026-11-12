# Prisme : guide pour reprendre le développement

Lecteur IPTV Android TV (et téléphone / tablette), interface en français. Paquet `com.fluxfr.tv` (ancien nom
du projet : FluxFR, gardé dans le code). Kotlin + Jetpack Compose (`androidx.tv.material3`), lecteur Media3.

Documentation détaillée :

- [docs/SETUP.md](docs/SETUP.md) : installer l'environnement de compilation sur un PC (neuf ou non).
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) : carte du code, flux principaux, pièges connus.
- [docs/RELEASE.md](docs/RELEASE.md) : sortir une version (numéro, nouveautés, compilation, publication, contrôles).

## Commandes

Toutes depuis la racine du dépôt, en Git Bash. `build-apk.sh` règle seul JDK, dossiers temporaires et dossier de
sortie (`~/.androidtv-build`). Pour les autres commandes Gradle, voir l'environnement dans docs/SETUP.md.

```bash
./build-apk.sh -q                                          # APK release PUBLIC → ./Prisme.apk
./gradlew testDebugUnitTest -Pkotlin.incremental=false -q  # tests JVM (app/src/test)
node tools/publish-update.mjs Prisme.apk <versionCode> <versionName>   # publication GitHub (docs/RELEASE.md)
```

## Règles à respecter

1. **Aucun secret dans le dépôt** : ni clé TMDB, ni identifiant Google OAuth, ni identifiants IPTV, ni adresse
   e-mail. Ils vivent dans `secrets.properties` (ignoré par git) et sont injectés dans `BuildConfig`. Avant chaque
   commit, chercher dans `git diff --cached` les valeurs de `secrets.properties`.
2. **Seule la version publique se publie.** La compilation est publique par défaut ; `-Ppersonal` intègre les
   identifiants IPTV de `secrets.properties` et ne doit jamais quitter la maison. `publish-update.mjs` refuse tout
   APK dont la meta-data `prisme.flavour` n'est pas `public`.
3. **Signature** : l'APK release est signé avec `~/.android/debug.keystore` (alias `androiddebugkey`). Les TV
   installées n'acceptent une mise à jour que si elle porte la même signature : **sans ce fichier, plus aucune
   mise à jour automatique n'est possible**. En garder une copie (voir docs/SETUP.md).
4. **Chaque version** : augmenter `versionCode` et `versionName` dans `app/build.gradle.kts`, et ajouter une entrée
   `## <versionName>` en tête de `app/src/main/assets/changelog.txt` (un test le vérifie, et la publication le
   demande).
5. **Pas de données du fournisseur dans le dépôt** (exports de catalogue, listes de chaînes, historiques). Pour des
   tests, inventer des noms au même format.
6. Textes de l'interface et commentaires en **français**, tutoiement dans l'interface.
7. Ne jamais interrompre une lecture en cours sur une TV de test (vérifier `topResumedActivity` avec adb avant
   d'installer).

## Conventions de code

- Logique pure (filtres, analyse de noms, règles d'accueil, mise à jour…) dans des `object` sans dépendance Android,
  testée dans `app/src/test` (JUnit 4). Pour le JSON dans ces tests, utiliser `MiniJson` (le `org.json` d'Android
  n'est qu'un bouchon en test JVM).
- Opt-in Kotlin : écrire `@kotlin.OptIn(...)` (un simple `@OptIn` peut se résoudre vers l'annotation androidx).
- Navigation verticale entre rangées : passer par `RowNav` (`ui/Components.kt`) plutôt que de laisser Compose
  faire défiler (voir docs/ARCHITECTURE.md).
- Menus d'actions sur les vignettes : `CardAction` + appui long (`ui/CardMenu.kt`), pas d'action silencieuse.
