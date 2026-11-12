# Sortir une version

Exemple : passer de 3.5.2 (versionCode 31) à 3.5.3 (versionCode 32).

## 1. Numéro de version

`app/build.gradle.kts` :

```kotlin
versionCode = 32
versionName = "3.5.3"
```

`versionCode` doit **toujours augmenter** : c'est lui que comparent les TV pour savoir s'il y a une mise à jour.

## 2. Nouveautés

En tête de `app/src/main/assets/changelog.txt`, au format :

```
## 3.5.3
- Ce qui change, en une phrase, du point de vue de l'utilisateur
- …
```

Ce texte s'affiche sur la TV après la mise à jour et devient la note de version sur GitHub. Le test
`ChangelogTest` échoue si la première entrée ne correspond pas à `versionName`.

## 3. Tests et compilation

```bash
./gradlew testDebugUnitTest -Pkotlin.incremental=false -q   # avec l'environnement de docs/SETUP.md
./build-apk.sh -q                                           # → Prisme.apk (version publique)
```

## 4. Contrôle des secrets

Aucune valeur de `secrets.properties` (sauf `update.repo`) ne doit apparaître dans le code. Les clés TMDB et
Google sont, elles, forcément dans l'APK : c'est normal (une appli cliente ne peut pas les cacher). On vérifie en
revanche que **les identifiants IPTV n'y sont pas** :

```bash
unzip -p Prisme.apk 'classes*.dex' | grep -a -c "<mot de passe IPTV>"   # doit afficher 0
```

## 5. Essai sur une TV (conseillé)

Installer sur une TV de test qui n'est pas en lecture (docs/SETUP.md, « Tester sur une TV »), vérifier l'écran
modifié.

## 6. Publication

```bash
export PATH="$HOME/.androidtv-build/gh/bin:$PATH"
gh auth status                      # doit être le compte personnel NickZambrano
node tools/publish-update.mjs Prisme.apk 32 3.5.3
```

Le script refuse un APK qui n'est pas « public » ou dont la version ne correspond pas, crée la publication `v3.5.3`
avec `Prisme.apk` et `prisme-public.json`, et la marque « latest ». En cas d'erreur sur les fichiers d'une
publication déjà créée : `node tools/publish-update.mjs --replace Prisme.apk 32 3.5.3`.

Vérification :

```bash
curl -sL https://github.com/NickZambrano/prisme/releases/latest/download/prisme-public.json
```

## 7. Code source

```bash
git add -A && git status            # Prisme.apk et secrets.properties ne doivent PAS apparaître
git commit -m "Prisme 3.5.3 : …"
git push
```

Pousser avec le compte personnel. Si git utilise un autre compte par défaut (PC d'entreprise), passer par
GitHub CLI pour cette commande seulement, sans changer la configuration globale :

```bash
git -c credential.helper= -c "credential.helper=!$(cygpath -m "$HOME/.androidtv-build/gh/bin/gh.exe") auth git-credential" push
```

## Ensuite, sur les TV

Les TV en version publique trouvent la mise à jour au plus tard 6 h après (ou 30 min après un retour dans
Prisme depuis la 3.5.2), la téléchargent, puis l'installent dès que Prisme quitte l'écran. Réglages → Mise à jour →
« Rechercher une mise à jour » force la vérification.

Première installation sur une nouvelle TV : application « Downloader », adresse
`https://github.com/NickZambrano/prisme/releases/latest/download/Prisme.apk`.
