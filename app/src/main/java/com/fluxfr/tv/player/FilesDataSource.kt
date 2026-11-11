package com.fluxfr.tv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.fluxfr.tv.data.files.ByteRange
import com.fluxfr.tv.data.files.FilesStore
import com.fluxfr.tv.data.files.SmbClient
import com.fluxfr.tv.data.files.SmbPaths
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/**
 * Lecture d'un fichier d'un partage SMB par le lecteur : `prisme-smb://<id du partage>/<partage>/<chemin>`.
 * Les identifiants sont lus dans [FilesStore] à l'ouverture (jamais dans l'URI). Accès aléatoire (avance /
 * retour rapide) : chaque [open] se place à [DataSpec.position] et lit au plus [DataSpec.length] octets.
 * Lectures regroupées par blocs de [BLOCK] octets (moins d'allers-retours réseau).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SmbDataSource(private val ctx: Context) : BaseDataSource(/* isNetwork = */ true) {
    private var file: SmbRandomAccessFile? = null
    private var smbFile: jcifs.smb.SmbFile? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false
    private val block = ByteArray(BLOCK)
    private var blockLen = 0
    private var blockPos = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (shareId, segs) = SmbPaths.parseUri(dataSpec.uri.toString())
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val share = FilesStore.share(ctx, shareId)
            ?: throw com.fluxfr.tv.data.files.FilesIOException("Ce partage réseau a été supprimé de l'onglet Fichiers.")
        try {
            val f = SmbClient.resolve(share, segs, dir = false)
            smbFile = f
            val size = f.length()
            if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            val raf = SmbRandomAccessFile(f, "r")
            raf.seek(dataSpec.position)
            file = raf
            bytesRemaining = ByteRange.remaining(
                dataSpec.position, if (dataSpec.length == C.LENGTH_UNSET.toLong()) ByteRange.UNBOUNDED else dataSpec.length, size,
            )
        } catch (e: DataSourceException) {
            throw e
        } catch (t: Throwable) {
            throw SmbClient.ioError(t)
        }
        blockLen = 0
        blockPos = 0
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val raf = file ?: throw IOException("Source non ouverte")
        if (blockPos >= blockLen) {
            val want = ByteRange.chunk(BLOCK, bytesRemaining)
            val n = try { raf.read(block, 0, want) } catch (t: Throwable) { throw SmbClient.ioError(t) }
            if (n <= 0) return C.RESULT_END_OF_INPUT
            blockLen = n
            blockPos = 0
        }
        val n = minOf(length, blockLen - blockPos)
        System.arraycopy(block, blockPos, buffer, offset, n)
        blockPos += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        val f = file
        file = null
        val sf = smbFile
        smbFile = null
        blockLen = 0
        blockPos = 0
        try {
            runCatching { f?.close() }
            runCatching { sf?.close() }
        } finally {
            if (opened) { opened = false; transferEnded() }
        }
    }

    class Factory(private val ctx: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(ctx.applicationContext)
    }

    companion object {
        const val BLOCK = 512 * 1024
    }
}

/**
 * Aiguillage du lecteur : fichiers du téléphone / de la TV (file://, content://) et partages SMB vers leurs
 * sources ; tout le reste (IPTV HTTP / HLS, tampon du direct) vers [upstream], inchangé.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class FilesRoutingDataSource(
    private val ctx: Context,
    private val upstream: DataSource,
) : DataSource {
    private var local: DataSource? = null
    private var smb: DataSource? = null
    private var active: DataSource? = null
    private val listeners = ArrayList<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream.addTransferListener(transferListener)
        local?.addTransferListener(transferListener)
        smb?.addTransferListener(transferListener)
    }

    private fun localSource(): DataSource = local ?: DefaultDataSource(ctx, false).also { d ->
        listeners.forEach { d.addTransferListener(it) }
        local = d
    }

    private fun smbSource(): DataSource = smb ?: SmbDataSource(ctx).also { d ->
        listeners.forEach { d.addTransferListener(it) }
        smb = d
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = when (dataSpec.uri.scheme?.lowercase()) {
            SmbPaths.SCHEME -> smbSource()
            "file", "content", "android.resource" -> localSource()
            else -> upstream
        }
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: throw IOException("Source non ouverte")).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        val a = active
        active = null
        a?.close()
    }

    class Factory(private val ctx: Context, private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = FilesRoutingDataSource(ctx.applicationContext, upstream.createDataSource())
    }
}
