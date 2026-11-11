package com.fluxfr.tv.data.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import java.io.File

/** Volume de stockage de l'appareil : mémoire interne partagée, clé USB, disque, carte SD. */
data class StorageRoot(val label: String, val path: String, val removable: Boolean)

/**
 * Fichiers de l'appareil : volumes montés (java.io.File) et dossiers choisis avec le sélecteur Android
 * (Storage Access Framework, pour les supports que File ne sait pas lire sur Android 11+).
 * Toutes les méthodes lisent le disque : hors du fil principal.
 */
object LocalStorage {
    /** Permission de lecture des vidéos : READ_MEDIA_VIDEO (Android 13+), sinon READ_EXTERNAL_STORAGE. */
    val readPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasReadPermission(ctx: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, readPermission) == PackageManager.PERMISSION_GRANTED

    /** Mémoire interne puis supports amovibles montés (dédoublonnés par chemin). */
    fun roots(ctx: Context): List<StorageRoot> {
        val out = LinkedHashMap<String, StorageRoot>()
        val internal = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
        if (internal != null) out[internal] = StorageRoot("Mémoire interne", internal, removable = false)

        // Android 7+ : volumes déclarés (nom lisible « Clé USB SanDisk », état monté).
        if (Build.VERSION.SDK_INT >= 24) {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            val volumes = runCatching { sm?.storageVolumes.orEmpty() }.getOrDefault(emptyList())
            for (v in volumes) {
                val state = runCatching { v.state }.getOrNull()
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val path = volumePath(v) ?: continue
                if (v.isPrimary && internal != null && path == internal) continue
                val label = runCatching { v.getDescription(ctx) }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: File(path).name
                out[path] = StorageRoot(label, path, removable = v.isRemovable)
            }
        }
        // Toutes versions : dossiers de l'appli sur chaque volume (« /storage/XXXX-XXXX/Android/data/… »).
        val dirs = runCatching { ctx.getExternalFilesDirs(null).toList() }.getOrDefault(emptyList())
        for (d in dirs) {
            val root = d?.absolutePath?.let { MediaFiles.volumeRootOf(it) } ?: continue
            if (root in out) continue
            out[root] = StorageRoot(File(root).name.ifBlank { "Support externe" }, root, removable = true)
        }
        return out.values.toList()
    }

    private fun volumePath(v: StorageVolume): String? {
        if (Build.VERSION.SDK_INT >= 30) return runCatching { v.directory?.absolutePath }.getOrNull()
        // Android 7 à 10 : getPath() n'est pas publique mais présente (liste grise « autorisée »).
        return runCatching { StorageVolume::class.java.getMethod("getPath").invoke(v) as? String }.getOrNull()
    }

    /** Contenu d'un dossier ; null si Android refuse la lecture (permission, support protégé). */
    fun list(dir: File): List<FileEntry>? {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return null
        return files.map { f ->
            val isDir = f.isDirectory
            FileEntry(f.name, isDir, if (isDir) -1L else f.length(), f.absolutePath)
        }
    }

    /** Clé stable de reprise d'un fichier local. */
    fun progressKey(path: String) = "file:$path"

    // ---------------------------------------------------------------- Dossiers choisis (SAF)

    /** Nom affiché d'un dossier choisi. */
    fun treeLabel(ctx: Context, tree: Uri): String {
        val doc = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull()
        val name = doc?.let { u ->
            runCatching {
                ctx.contentResolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }
        return name?.takeIf { it.isNotBlank() } ?: (tree.lastPathSegment?.substringAfterLast(':') ?: "Dossier")
    }

    /** Toujours autorisé (permission conservée) ? */
    fun treeAccessible(ctx: Context, tree: Uri): Boolean =
        ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

    /**
     * Contenu d'un dossier choisi ; [docId] null = racine du dossier. [FileEntry.ref] = identifiant du document.
     * null si le dossier n'est plus accessible (support retiré, permission perdue).
     */
    fun listTree(ctx: Context, tree: Uri, docId: String?): List<FileEntry>? {
        val parent = docId ?: runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val children = runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent) }.getOrNull() ?: return null
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return runCatching {
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                val out = ArrayList<FileEntry>(c.count)
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (isDir || c.isNull(3)) -1L else c.getLong(3)
                    out += FileEntry(name, isDir, size, id)
                }
                out
            }
        }.getOrNull()
    }

    fun documentUri(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
}
