package org.jellyfin.mobile.downloads

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Exercises actual Android file descriptors and HTTP bodies, including interrupted transfers. */
class FileDownloaderTest {
    private data class Reply(val code: Int, val body: String, val range: String? = null, val length: Int = body.length)

    private fun download(initial: String, replies: List<Reply>, check: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("download-test", ".bin", context.cacheDir)
        file.writeText(initial)
        val server = ServerSocket(0)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                server.use {
                    for (reply in replies) {
                        server.accept().use { socket ->
                            val input = socket.getInputStream().bufferedReader()
                            while (!input.readLine().isNullOrEmpty()) Unit
                            val header = buildString {
                                append(
                                    "HTTP/1.1 ${reply.code} Response\r\nContent-Length: ${reply.length}\r\nConnection: close\r\n"
                                )
                                reply.range?.let { append("Content-Range: $it\r\n") }
                                append("\r\n")
                            }
                            socket.getOutputStream().write((header + reply.body).toByteArray())
                        }
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        val api = createJellyfin {
            this.context = context
            clientInfo = ClientInfo("Download tests", "1")
        }.createApi(baseUrl = "http://127.0.0.1:${server.localPort}")
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            runBlocking {
                FileDownloader(OkHttpClient()).downloadAndSave(api, Uri.parse("${api.baseUrl}/media"), descriptor)
            }
            worker.join(2000)
            assertNull(failure.get())
            check(file)
        } finally {
            server.close()
            file.delete()
        }
    }

    @Test
    fun alreadyCompleteResponseDoesNotOverwriteMediaWithItsErrorBody() {
        download("abcd", listOf(Reply(416, "Range not satisfiable", "bytes */4"))) {
            assertArrayEquals("abcd".toByteArray(), it.readBytes())
        }
    }

    @Test
    fun ignoredRangeTruncatesTheOldFileTail() {
        download("abcdefgh", listOf(Reply(200, "1234"))) { assertEquals("1234", it.readText()) }
    }

    @Test
    fun partialContentResumesAtTheCorrectOffset() {
        download("abcd", listOf(Reply(206, "efgh", "bytes 4-7/8"))) { assertEquals("abcdefgh", it.readText()) }
    }

    @Test
    fun aSmallerRemoteFileStartsAgain() {
        download("abcdefgh", listOf(Reply(416, "", "bytes */4"), Reply(200, "1234"))) {
            assertEquals("1234", it.readText())
        }
    }

    @Test
    fun interruptedResponseCannotComplete() {
        assertThrows(IOException::class.java) {
            download("", listOf(Reply(200, "abcd", length = 8))) { error("Truncated download was accepted") }
        }
    }

    @Test
    fun unexpectedResumeOffsetCannotOverwriteLocalMedia() {
        assertThrows(IOException::class.java) {
            download("abcd", listOf(Reply(206, "abcdefgh", "bytes 0-7/8"))) { error("Wrong offset was accepted") }
        }
    }
}
