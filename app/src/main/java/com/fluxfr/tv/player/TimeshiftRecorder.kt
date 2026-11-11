package com.fluxfr.tv.player

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Enregistre le direct (une seule connexion au fournisseur) dans un [TimeshiftBuffer], sur un thread
 * dédié. Coupure réseau : reconnexion automatique (1, 2, 4, 8 puis 15 s), le tampon est conservé.
 * Échec définitif seulement si aucune donnée n'a jamais été reçue (3 tentatives), si le flux n'est pas
 * du MPEG-TS ou si le tampon ne peut plus être écrit (disque plein) : le lecteur repasse alors en lecture
 * directe.
 *
 * Les URL contiennent les identifiants du compte : elles ne sont jamais écrites dans le journal.
 */
class TimeshiftRecorder(
    private val url: String,
    private val userAgent: String,
    val buffer: TimeshiftBuffer,
    /** Enregistreur précédent : on attend qu'il ait fermé sa connexion (comptes à une seule connexion). */
    @Volatile private var previous: TimeshiftRecorder?,
) {
    @Volatile var stopped = false
        private set
    @Volatile var receivedData = false
        private set
    @Volatile private var connection: HttpURLConnection? = null
    private val thread = Thread({ run() }, "Timeshift")

    val isAlive: Boolean get() = thread.isAlive

    fun start() {
        thread.isDaemon = true
        thread.start()
    }

    /** Arrête l'enregistrement (la connexion est fermée hors du thread principal). */
    fun stop() {
        if (stopped) return
        stopped = true
        thread.interrupt()
        val c = connection
        if (c != null) Thread({ runCatching { c.disconnect() } }, "TimeshiftClose").start()
    }

    /** Attend la fin du thread d'enregistrement (au plus [ms]). */
    fun join(ms: Long) {
        runCatching { thread.join(ms) }
    }

    private fun run() {
        previous?.let { p ->
            p.stop()
            p.join(3_000)
        }
        previous = null
        // Abonnement à une connexion : le lecteur doit avoir lâché le flux précédent (replay, chaîne HLS).
        if (!stopped) ProviderConnections.beforeRecorderOpen { stopped }
        val scanner = TsScanner(
            onKeyframe = { off -> buffer.addKeyframe(off) },
            onPhase = { p -> buffer.setPacketPhase(p) },
        )
        val chunk = ByteArray(64 * 1024)
        var failures = 0
        while (!stopped) {
            var error: IOException? = null
            try {
                val c = open()
                connection = c
                if (stopped) break
                val type = c.contentType?.lowercase().orEmpty()
                if ("mpegurl" in type || "text/html" in type) {
                    throw TimeshiftSourceException("Flux non MPEG-TS", notTs = true)
                }
                val input: InputStream = c.inputStream
                var first = true
                while (!stopped) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    if (first && !receivedData && TsScanner.looksLikePlaylist(chunk, n)) {
                        throw TimeshiftSourceException("Flux non MPEG-TS", notTs = true)
                    }
                    first = false
                    val before = scanner.bytesScanned
                    scanner.feed(chunk, 0, n)
                    if (!scanner.everSynced && before + n > NOT_TS_AFTER_BYTES) {
                        throw TimeshiftSourceException("Flux non MPEG-TS", notTs = true)
                    }
                    buffer.append(chunk, 0, n)
                    receivedData = true
                    failures = 0
                }
            } catch (e: IOException) {
                error = e
            } catch (e: RuntimeException) {
                error = IOException(e.message ?: "Erreur d'enregistrement")
            } finally {
                val c = connection
                connection = null
                if (c != null) runCatching { c.disconnect() }
            }
            if (stopped) break
            // Écriture locale impossible (disque plein) : se reconnecter ne servirait à rien.
            if (error is TimeshiftWriteException) {
                Log.w(TAG, "Enregistrement du direct arrêté : écriture du tampon impossible (${error.cause?.message})")
                buffer.fail(error)
                return
            }
            failures++
            val notTs = (error as? TimeshiftSourceException)?.notTs == true
            if (notTs || (!receivedData && failures >= 3)) {
                Log.w(TAG, "Enregistrement du direct impossible : ${error?.message ?: "fin du flux"}")
                buffer.fail(error ?: TimeshiftSourceException("Le flux est indisponible"))
                return
            }
            Log.i(TAG, "Direct interrompu (${error?.javaClass?.simpleName ?: "fin du flux"}), reconnexion n°$failures")
            val wait = BACKOFF_MS[minOf(failures - 1, BACKOFF_MS.size - 1)]
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                if (stopped) break
            }
        }
    }

    /** Même en-têtes que le lecteur (User-Agent, redirections http ↔ https suivies). */
    private fun open(): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Encoding", "identity")
            }
            connection = c
            if (stopped) { runCatching { c.disconnect() }; throw IOException("Arrêté") }
            val code = c.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw IOException("Redirection invalide")
                current = URL(URL(current), loc).toString()
                redirects++
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw TimeshiftSourceException("Le flux est indisponible (erreur $code)", httpCode = code)
            }
            return c
        }
    }

    private companion object {
        const val TAG = "FluxTimeshift"
        const val NOT_TS_AFTER_BYTES = 512L * 1024
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    }
}
