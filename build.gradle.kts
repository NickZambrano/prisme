plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

// Windows limite les chemins à 260 caractères : on compile dans un dossier court.
val shortBuildRoot = System.getenv("FLUXFR_BUILD_DIR")
if (shortBuildRoot != null) {
    allprojects { layout.buildDirectory.set(file("$shortBuildRoot/${project.name}")) }
}
