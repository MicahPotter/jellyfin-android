package org.jellyfin.mobile.downloads

import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import java.io.IOException
import kotlin.coroutines.resumeWithException

class FileDownloader(
    private val okHttpClient: OkHttpClient,
) {
    fun interface ProgressCallback {
        suspend fun onProgress(downloaded: Long, total: Long)

        companion object Empty : ProgressCallback {
            override suspend fun onProgress(downloaded: Long, total: Long) = Unit
        }
    }

    private suspend fun download(
        api: ApiClient,
        from: Uri,
        rangeStart: Long? = null,
    ): Response {
        val authorizationHeader = AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = api.accessToken,
        )

        val request = Request.Builder().apply {
            url(from.toString())

            header("Authorization", authorizationHeader)
            header("Accept-Encoding", "identity")
            rangeStart?.let { header("Range", "bytes=$rangeStart-") }
        }.build()

        val response = okHttpClient.newCall(request).await()

        // 416 (Requested Range Not Satisfiable) can happen when we've already fully downloaded the file
        if (response.code == 416 && rangeStart != null) return response

        // Throw for other unsuccessful responses
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Download failed (HTTP ${response.code})")
        }

        return response
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { cause, response, _ ->
                        response.close()
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            },
        )

        continuation.invokeOnCancellation {
            cancel()
        }
    }

    private fun Response.getContentRange() = when (code) {
        200 -> requireNotNull(header("Content-Length")).let(ContentRange::fromContentLengthHeader)
        206, 416 -> requireNotNull(header("Content-Range")).let(ContentRange::fromContentRangeHeader)
        else -> error("Invalid response code $code")
    }

    private suspend fun save(
        response: Response,
        to: ParcelFileDescriptor,
        progressCallback: ProgressCallback,
    ) = withContext(Dispatchers.IO) {
        val contentRange = response.getContentRange()

        val output = ParcelFileDescriptor.AutoCloseOutputStream(to)
        // A server may ignore Range and return the whole file. Discard the previous tail.
        if (response.code == 200) output.channel.truncate(0)
        output.channel.position(contentRange.start)

        val inputStream = response.body?.byteStream() ?: error("Response does not contain a body")
        inputStream.use { inputStream ->
            output.use { outputFile ->
                val buffer = ByteArray(10240)
                var totalRead = contentRange.start
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    coroutineContext.ensureActive()

                    outputFile.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalRead > contentRange.total) throw IOException("Download exceeds the expected size")

                    progressCallback.onProgress(totalRead, contentRange.total)
                }
                if (totalRead != contentRange.total) throw IOException("Download ended before the expected size")
            }
        }
    }

    suspend fun downloadAndSave(
        api: ApiClient,
        from: Uri,
        to: ParcelFileDescriptor,
        progressCallback: ProgressCallback = ProgressCallback.Empty,
    ) {
        to.use {
            val rangeStart = to.statSize.coerceAtLeast(0)
            val response = download(api, from, rangeStart)
            response.use {
                val range = response.getContentRange()
                when {
                    response.code == 416 && rangeStart == range.total -> {
                        // A complete file needs no response body written into it.
                        progressCallback.onProgress(range.total, range.total)
                    }
                    response.code == 416 -> {
                        // The remote file became smaller; start again instead of accepting stale bytes.
                        download(api, from).use { fresh -> save(fresh, to, progressCallback) }
                    }
                    response.code == 206 && range.start != rangeStart -> throw IOException("Unexpected resume offset")
                    else -> save(response, to, progressCallback)
                }
            }
        }
    }
}
