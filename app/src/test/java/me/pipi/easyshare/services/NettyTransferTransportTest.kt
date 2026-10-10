package me.pipi.easyshare.services

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.pipi.easyshare.SessionSecurity
import me.pipi.easyshare.SessionTrustManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NettyTransferTransportTest {
    @Test(timeout = 30_000)
    fun http1TransferSupportsPinnedTlsDownloadsAndWebSockets() = runBlocking {
        val password = "test-session-password"
        val keyStore = buildKeyStore {
            certificate("session") {
                this.password = password
                domains = listOf("localhost")
            }
        }
        val certificate = keyStore.getCertificate("session") as X509Certificate
        val trustManager = SessionTrustManager(SessionSecurity.certificateSha256(certificate))
        val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
        val server = embeddedServer(Netty, serverConfig {
            developmentMode = false
            watchPaths = emptyList()
            module {
                install(ServerWebSockets)
                routing {
                    get("/download") { call.respondBytes(payload) }
                    webSocket("/websocket") {
                        val message = (incoming.receive() as Frame.Text).readText()
                        outgoing.send(Frame.Text("received:$message"))
                    }
                }
            }
        }, configure = {
            sslConnector(keyStore, "session", { password.toCharArray() }, { password.toCharArray() }) {
                host = "127.0.0.1"
                port = 0
            }
            // Matches the alliance transfer server's TCP-only configuration.
            enableHttp2 = false
        })
        val client = HttpClient(OkHttp) {
            install(ClientWebSockets)
            engine {
                config {
                    val sslContext = SSLContext.getInstance("TLSv1.2")
                    sslContext.init(null, arrayOf(trustManager), null)
                    sslSocketFactory(sslContext.socketFactory, trustManager)
                    connectTimeout(5, TimeUnit.SECONDS)
                    readTimeout(5, TimeUnit.SECONDS)
                }
            }
        }

        try {
            withTimeout(20_000) {
                server.start()
                val port = server.engine.resolvedConnectors().single().port
                assertArrayEquals(payload, client.get("https://localhost:$port/download").body<ByteArray>())
                client.webSocket("wss://localhost:$port/websocket") {
                    outgoing.send(Frame.Text("transfer-status"))
                    assertEquals("received:transfer-status", (incoming.receive() as Frame.Text).readText())
                }
            }
        } finally {
            client.close()
            server.stop(0, 1_000)
        }
    }
}
