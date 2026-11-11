# Media3, Compose et Coil fournissent leurs propres règles.

# ---- Onglet « Fichiers » : partages réseau (jcifs-ng)
# jcifs-ng crée des messages SMB, des fournisseurs d'authentification et sa configuration par réflexion.
-keep class jcifs.** { *; }
-dontwarn jcifs.**
# BouncyCastle (MD4 / RC4 du NTLM, AES-CMAC / CCM du SMB3) : algorithmes enregistrés par nom de classe dans
# BouncyCastleProvider, chargés par réflexion.
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**
# slf4j sans implémentation (journaux muets) ; servlet / Kerberos de jcifs-ng inutilisés sur Android.
-dontwarn org.slf4j.**
-dontwarn javax.servlet.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn org.ietf.jgss.**
