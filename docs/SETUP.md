# Environnement de développement

Tout l'outillage tient dans un seul dossier, `~/.androidtv-build`, sans installation système (pas de droits
administrateur requis, utile sur un PC d'entreprise). Android Studio n'est pas nécessaire, mais peut ouvrir le
projet tel quel.

## Ce qu'il faut

| Outil | Version | Emplacement attendu |
|---|---|---|
| JDK Temurin | 17.0.21+9 | `~/.androidtv-build/jdk-17.0.21+9` |
| SDK Android | plateforme `android-35`, build-tools `35.0.0`, platform-tools | `~/.androidtv-build/sdk` |
| Gradle | 8.11.1 (téléchargé par `./gradlew`) | automatique |
| Node.js | 18 ou plus (publication seulement) | dans le `PATH` |
| GitHub CLI | 2.x (publication seulement) | `~/.androidtv-build/gh/bin/gh.exe` |
| Git Bash | | pour `build-apk.sh` |

Versions des bibliothèques : AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2025.05.00, Media3 1.5.1 (+ décodeur FFmpeg
Jellyfin), jcifs-ng 2.1.10 (partages SMB). Voir `app/build.gradle.kts`.

## Installation sur un nouveau PC

1. **JDK** : archive zip de Temurin 17 (adoptium.net), décompressée dans `~/.androidtv-build/`.
2. **SDK Android** : « command line tools » (developer.android.com/studio#command-tools) dans
   `~/.androidtv-build/sdk/cmdline-tools/latest/`, puis :

   ```bash
   T="$HOME/.androidtv-build"; export JAVA_HOME="$T/jdk-17.0.21+9"
   "$T/sdk/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$T/sdk" \
     "platforms;android-35" "build-tools;35.0.0" "platform-tools"
   ```

3. **Le dépôt** :

   ```bash
   git clone https://github.com/NickZambrano/prisme.git
   cd prisme
   git config user.name NickZambrano
   git config user.email 10036214+NickZambrano@users.noreply.github.com
   echo "sdk.dir=$(cygpath -m "$HOME/.androidtv-build/sdk")" > local.properties
   cp secrets.properties.example secrets.properties   # puis remplir (voir plus bas)
   ```

   L'identité est réglée **dans le dépôt seulement** (`git config` sans `--global`) pour ne pas toucher à une
   configuration d'entreprise.
4. **Clé de signature** : recopier la sauvegarde de `debug.keystore` dans `~/.android/debug.keystore`. Empreinte
   attendue (SHA-256) :
   `E5:B5:84:9F:0E:DA:F2:C7:93:E2:16:94:FC:77:94:EC:A6:85:08:67:B7:BA:1C:79:87:31:AE:17:5A:07:46:A7`.
   Vérification :

   ```bash
   "$HOME/.androidtv-build/jdk-17.0.21+9/bin/keytool" -list -v -keystore "$HOME/.android/debug.keystore" -storepass android | grep "SHA 256"
   ```

   Si la clé est perdue, les TV déjà installées refuseront toute nouvelle version (il faudrait désinstaller Prisme
   sur chacune, donc perdre profils et historique non synchronisés).
5. **Contrôle** : `./build-apk.sh -q` puis les tests (ci-dessous).

## `secrets.properties`

Jamais versionné. Clés (modèle : `secrets.properties.example`) :

- `tmdb.apiKey` : clé API TMDB (recommandations, tendances, affiches).
- `google.clientId`, `google.clientSecret` : client OAuth Google « TV et périphériques à saisie limitée »
  (synchronisation Drive des profils et de l'historique).
- `update.repo=NickZambrano/prisme` : dépôt dont la dernière publication sert aux mises à jour automatiques.
- `fluxfr.server`, `fluxfr.user`, `fluxfr.pass` : uniquement pour l'ancienne version personnelle (`-Ppersonal`).

Une clé vide compile quand même : la fonction correspondante est simplement désactivée.

## Lancer Gradle à la main

`build-apk.sh` le fait déjà ; pour les autres tâches (tests, `assembleDebug`…), dans Git Bash :

```bash
T="$HOME/.androidtv-build"; mkdir -p "$T/tmp" "$T/out"
export JAVA_HOME="$T/jdk-17.0.21+9" PATH="$T/jdk-17.0.21+9/bin:$PATH"
export TMP="$(cygpath -w "$T/tmp")" TEMP="$(cygpath -w "$T/tmp")"
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$(cygpath -m "$T/tmp") -Djava.io.tmpdir=$(cygpath -m "$T/tmp")"
export FLUXFR_BUILD_DIR="$(cygpath -m "$T/out")"
./gradlew testDebugUnitTest -Pkotlin.incremental=false -q
```

Pourquoi :

- `FLUXFR_BUILD_DIR` : compilation dans un dossier court (limite de 260 caractères des chemins Windows).
- `TMP` / `java.io.tmpdir` / `unixdomain.tmpdir` : le dossier temporaire par défaut peut être bloqué ou trop long.
- `-Pkotlin.incremental=false` : évite des erreurs de cache de la compilation incrémentale Kotlin.

## Tester sur une TV

Activer le débogage réseau sur la TV (Options pour les développeurs), puis :

```bash
ADB="$HOME/.androidtv-build/sdk/platform-tools/adb.exe"
"$ADB" connect <ip-de-la-tv>:5555
"$ADB" -s <ip-de-la-tv>:5555 shell dumpsys activity activities | grep topResumedActivity   # rien en lecture ?
"$ADB" -s <ip-de-la-tv>:5555 install -r Prisme.apk
"$ADB" -s <ip-de-la-tv>:5555 shell cmd package compile -m speed-profile -f com.fluxfr.tv     # appli plus fluide
```

Toujours passer `-s` (plusieurs TV peuvent être connues d'adb). Sous Git Bash, préfixer par `MSYS_NO_PATHCONV=1`
les commandes dont un argument commence par `/`.

Note : une installation par adb fait d'adb « l'installateur » de Prisme. La mise à jour automatique sans
confirmation (Android 12+) ne marche que si Prisme s'est installé lui-même ; après un `adb install`, la version
suivante demandera donc une confirmation une fois (bandeau de l'accueil), puis redeviendra silencieuse.
