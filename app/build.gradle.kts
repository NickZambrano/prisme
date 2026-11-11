import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String) = "\"" + (secrets.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.fluxfr.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fluxfr.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 29
        versionName = "3.5"
        // Version publique (gradle -Ppublic) : aucun identifiant IPTV intégré, chacun configure le sien.
        // Version PUBLIQUE par défaut (aucun identifiant IPTV intégré). L'ancienne version personnelle, avec les
        // identifiants de secrets.properties, n'est plus produite qu'avec -Ppersonal (ne jamais la publier).
        val publicBuild = !project.hasProperty("personal")
        fun cred(key: String) = if (publicBuild) "\"\"" else secret(key)
        // Type de build, lisible aussi dans le manifeste d'un APK (meta-data prisme.flavour) : une TV
        // n'installe jamais l'autre type (même applicationId, même clé, même versionCode).
        val flavour = if (publicBuild) "public" else "personal"
        buildConfigField("String", "FLAVOUR", "\"$flavour\"")
        manifestPlaceholders["prismeFlavour"] = flavour
        buildConfigField("String", "DEFAULT_SERVER", cred("fluxfr.server"))
        buildConfigField("String", "DEFAULT_USER", cred("fluxfr.user"))
        buildConfigField("String", "DEFAULT_PASS", cred("fluxfr.pass"))
        buildConfigField("String", "TMDB_API_KEY", secret("tmdb.apiKey"))
        buildConfigField("String", "GOOGLE_CLIENT_ID", secret("google.clientId"))
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", secret("google.clientSecret"))
        // Mise à jour automatique (version publique seulement) : fichier de version de la dernière publication
        // GitHub du dépôt public (tools/publish-update.mjs). Vide pour la version personnelle (mises à jour
        // entre TV de la maison).
        val updateRepo = secrets.getProperty("update.repo").orEmpty().trim()
        buildConfigField(
            "String", "UPDATE_MANIFEST_URL",
            if (publicBuild && updateRepo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
                "\"https://github.com/$updateRepo/releases/latest/download/prisme-public.json\""
            else "\"\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signé avec la clé de debug pour pouvoir l'installer directement (sideload).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Décodeur audio FFmpeg pour Media3 (paquet prêt à l emploi publié par Jellyfin).
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    // Rangée « Continuer à regarder » (Watch Next) de l'écran d'accueil Google TV.
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    // Profils de démarrage (Compose, etc.) appliqués aussi aux APK installés à la main : appli plus fluide.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Onglet « Fichiers » : partages réseau Windows / NAS (SMB 2/3, liste des partages d'un hôte).
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")

    testImplementation("junit:junit:4.13.2")
}
