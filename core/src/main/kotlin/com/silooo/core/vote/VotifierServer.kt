package com.silooo.core.vote

import com.silooo.core.db.Database
import io.ktor.server.application.Application
import io.ktor.server.application.log
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory

/**
 * Le serveur TCP de Votifier 2 : il reçoit les votes que Hubyy envoie. Une connexion, un vote : défi envoyé, message lu,
 * décodé par [Votifier2], remis à [onVote], réponse JSON, fermeture. Tout ce qui est règle du protocole est dans [Votifier2] ;
 * ici il n'y a que les sockets, les délais et les journaux.
 *
 * Le jeton ne passe qu'à [Votifier2.decode] ; il n'est écrit dans aucun journal. Un message refusé laisse une ligne
 * (la cause, l'adresse de l'émetteur), jamais son contenu. [onVote] qui échoue répond `Unavailable` : le vote n'est pas perdu
 * en silence, le site sait qu'il doit réessayer.
 */
class VotifierServer(
    settings: VotifierSettings,
    private val random: SecureRandom = SecureRandom(),
    /** Ce que le serveur fait d'un vote authentifié. Lève une exception si le vote n'a pas pu être enregistré. */
    private val onVote: suspend (VotifierVote) -> Unit,
) {
    private val port = settings.port
    private val token: ByteArray = settings.token.toByteArray(Charsets.UTF_8)
    private val connections = Semaphore(MAX_CONNECTIONS)

    @Volatile
    private var socket: ServerSocket? = null

    /** Le port réellement écouté (utile quand les réglages demandent le port 0). Seulement après [start]. */
    val localPort: Int get() = checkNotNull(socket) { "Le serveur Votifier n'est pas démarré." }.localPort

    /**
     * Ouvre le port, puis accepte les connexions dans [scope] jusqu'à son annulation, qui ferme la socket. L'ouverture a lieu avant
     * le retour : un port déjà pris lève [IOException] ici, à l'appelant d'en tirer la conséquence.
     */
    fun start(scope: CoroutineScope): Job {
        val server = ServerSocket(port)
        socket = server
        scope.launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                server.close()
            }
        }
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    server.accept()
                } catch (_: SocketException) {
                    break // la socket est fermée : l'application s'arrête
                }
                if (!connections.tryAcquire()) {
                    client.closeQuietly()
                    continue
                }
                launch {
                    try {
                        serve(client)
                    } finally {
                        connections.release()
                    }
                }
            }
        }
    }

    private suspend fun serve(client: Socket) {
        client.use {
            val reply = try {
                client.soTimeout = READ_TIMEOUT_MS
                val output = client.getOutputStream()
                val challenge = Votifier2.newChallenge(random)
                output.write(Votifier2.greeting(challenge))
                output.flush()

                val input = client.getInputStream()
                val header = input.readUpTo(Votifier2.HEADER_SIZE)
                val body = input.readUpTo(Votifier2.bodyLength(header))
                val vote = Votifier2.decode(header + body, token, challenge)
                onVote(vote)
                log.info("Votifier : vote de {} pour {} reçu.", vote.serviceName, vote.username)
                Votifier2.OK
            } catch (e: CancellationException) {
                throw e
            } catch (e: VotifierException) {
                log.warn("Votifier : message refusé ({}), émetteur {}.", e.failure.cause, client.remoteSocketAddress)
                Votifier2.errorReply(e.failure)
            } catch (_: SocketTimeoutException) {
                log.warn("Votifier : message trop lent, émetteur {}.", client.remoteSocketAddress)
                Votifier2.errorReply(VotifierFailure.TRUNCATED)
            } catch (e: IOException) {
                log.warn("Votifier : connexion perdue, émetteur {} : {}", client.remoteSocketAddress, e.message)
                return
            } catch (e: Exception) {
                log.warn("Votifier : un vote authentifié n'a pas pu être enregistré : {}", e.message)
                Votifier2.errorReply(VotifierFailure.UNAVAILABLE)
            }
            try {
                client.getOutputStream().write(reply)
                client.getOutputStream().flush()
            } catch (_: IOException) {
                // L'émetteur est parti avant la réponse : rien à faire de plus.
            }
        }
    }

    /** Lit jusqu'à [count] octets ; moins si l'émetteur ferme avant (le décodeur en tire alors `NoMagic` ou `Truncated`). */
    private fun InputStream.readUpTo(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(buffer, read, count - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // déjà fermée
        }
    }

    companion object {
        /**
         * Combien de connexions à la fois : une de plus est fermée sans réponse. Une limite technique, pas un chiffre du jeu : un
         * site de vote envoie un vote à la fois, et ce port est ouvert à tout Internet.
         */
        const val MAX_CONNECTIONS = 16

        /** Combien de temps l'émetteur a pour envoyer son message avant que la connexion soit coupée (5 secondes). */
        const val READ_TIMEOUT_MS = 5_000

        private val log = LoggerFactory.getLogger(VotifierServer::class.java)
    }
}

/**
 * Lance la réception des votes dans la portée de l'application Ktor : elle s'arrête avec elle. Un port déjà pris est noté et le
 * service démarre quand même (un vote manqué vaut mieux que le jeu à l'arrêt) ; Kr le voit dans le journal.
 */
fun Application.launchVotifier(settings: VotifierSettings, votes: VoteService, database: Database): VotifierServer? {
    val server = VotifierServer(settings) { vote -> votes.receive(database, IncomingVote(vote.username, vote.serviceName)) }
    return try {
        server.start(this)
        log.info("Votifier : à l'écoute sur le port {}.", server.localPort)
        server
    } catch (e: IOException) {
        log.error("Votifier : le port {} n'a pas pu être ouvert, les votes ne sont pas reçus : {}", settings.port, e.message)
        null
    }
}
