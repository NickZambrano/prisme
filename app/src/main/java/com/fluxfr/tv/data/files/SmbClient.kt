package com.fluxfr.tv.data.files

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import java.io.IOException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Accès aux partages SMB (bibliothèque jcifs-ng) : SMB 2.0.2 à 3.1.1 par défaut, SMB1 seulement si le partage
 * l'autorise ([SmbShare.allowSmb1], vieux NAS). Délais d'environ 10 s. Toutes les méthodes font du réseau :
 * à appeler sur Dispatchers.IO / le fil du lecteur, jamais sur le fil principal.
 * Aucun identifiant n'est journalisé (jcifs-ng journalise via slf4j, sans implémentation dans l'appli : muet).
 */
object SmbClient {
    private val contexts = ConcurrentHashMap<String, CIFSContext>()

    /** Partage modifié ou supprimé : son contexte (connexions, identifiants) est oublié. */
    fun forget(shareId: String) {
        contexts.remove(shareId)?.let { c -> runCatching { c.close() } }
    }

    private fun config(share: SmbShare): Properties = Properties().apply {
        setProperty("jcifs.smb.client.minVersion", if (share.allowSmb1) "SMB1" else "SMB202")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")
        setProperty("jcifs.smb.client.disableSMB1", if (share.allowSmb1) "false" else "true")
        setProperty("jcifs.smb.client.connTimeout", "10000")
        setProperty("jcifs.smb.client.responseTimeout", "10000")
        setProperty("jcifs.smb.client.soTimeout", "30000")
        setProperty("jcifs.smb.client.sessionTimeout", "30000")
        setProperty("jcifs.netbios.soTimeout", "3000")
        setProperty("jcifs.netbios.retryTimeout", "3000")
        // Noms NetBIOS (« NAS ») : DNS puis diffusion sur le réseau local.
        setProperty("jcifs.resolveOrder", "DNS,BCAST")
        // Pas de requêtes DFS vers un contrôleur de domaine : partages domestiques.
        setProperty("jcifs.smb.client.dfs.disabled", "true")
        // Invité refusé par le serveur avec un mot de passe : pas de bascule silencieuse en invité.
        setProperty("jcifs.smb.client.allowGuestFallback", if (share.guest) "true" else "false")
    }

    fun context(share: SmbShare): CIFSContext = contexts.getOrPut(share.id) {
        val base = BaseContext(PropertyConfiguration(config(share)))
        if (share.guest) base.withGuestCrendentials()
        else base.withCredentials(NtlmPasswordAuthenticator(share.domain.trim(), share.user.trim(), share.password))
    }

    /** Ressource SMB : hôte puis chaque segment ajouté un par un (noms avec espaces, « # », « % »…). */
    fun resolve(share: SmbShare, segs: List<String>, dir: Boolean): SmbFile {
        val ctx = context(share)
        var f = SmbFile(SmbPaths.hostUrl(share.host), ctx)
        segs.forEachIndexed { i, s ->
            val last = i == segs.lastIndex
            f = SmbFile(f, if (last && !dir) s else "$s/")
        }
        return f
    }

    /**
     * Contenu d'un dossier ; [segs] vide = partages de l'hôte (partages cachés « $ » écartés).
     * [FileEntry.ref] = chemin complet (segments séparés par « / »).
     */
    fun list(share: SmbShare, segs: List<String>): List<FileEntry> {
        val dir = resolve(share, segs, dir = true)
        val children = dir.listFiles()
        return children.mapNotNull { c ->
            try {
                val name = c.name.trimEnd('/')
                if (segs.isEmpty() && name.endsWith("$")) return@mapNotNull null
                val isDir = segs.isEmpty() || c.isDirectory
                FileEntry(name, isDir, if (isDir) -1L else c.length(), (segs + name).joinToString("/"))
            } catch (_: SmbException) {
                null
            } finally {
                runCatching { c.close() }
            }
        }.also { runCatching { dir.close() } }
    }

    /** Essai de connexion (« Tester ») : liste le dossier de départ ; null si tout va bien, sinon le message. */
    fun test(share: SmbShare): String? = try {
        list(share, share.rootSegments)
        null
    } catch (t: Throwable) {
        forget(share.id)
        message(t)
    }

    /** Message français d'une erreur SMB (identifiants, partage, hôte…). */
    fun message(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(8).toList()
        val status = chain.firstNotNullOfOrNull { (it as? SmbException)?.ntStatus?.takeIf { s -> s != 0 } } ?: 0
        return SmbErrors.describe(status, chain.map { it.javaClass.simpleName }, chain.mapNotNull { it.message })
    }

    /** IOException portant le message français (lecteur). */
    fun ioError(t: Throwable): IOException = if (t is FilesIOException) t else FilesIOException(message(t), t)
}

/** Erreur d'accès à un fichier (partage réseau) dont le message, en français, s'affiche tel quel dans le lecteur. */
class FilesIOException(message: String, cause: Throwable? = null) : IOException(message, cause)
