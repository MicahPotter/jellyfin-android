package org.jellyfin.mobile.player.queue

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.CheckResult
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadedMediaResolver
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.source.ExternalSubtitleStream
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.player.source.PlaybackDetails
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.api.operations.VideosApi
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration

class QueueManager(
    private val viewModel: PlayerViewModel,
) : KoinComponent {
    private val apiClient: ApiClient = get()
    private val videosApi: VideosApi = apiClient.videosApi
    private val mediaSourceResolver: MediaSourceResolver by inject()
    private val deviceProfileBuilder: DeviceProfileBuilder by inject()
    private val downloadedMedia: DownloadedMediaResolver by inject()
    private val deviceProfile = deviceProfileBuilder.getDeviceProfile()

    private var currentQueue: List<UUID> = emptyList()
    private var currentQueueIndex: Int = 0
    private var downloadOnlyQueue = false

    private var playbackRetries = 0
    private var lastPlaybackError = 0L

    private val _currentMediaSource: MutableLiveData<JellyfinMediaSource> = MutableLiveData()
    val currentMediaSource: LiveData<JellyfinMediaSource>
        get() = _currentMediaSource

    fun getCurrentMediaSourceOrNull(): JellyfinMediaSource? = currentMediaSource.value

    /**
     * Handle initial playback options from fragment.
     * Start of a playback session that can contain one or multiple played videos.
     *
     * @return an error of type [PlayerException] or null on success.
     */
    suspend fun initializePlaybackQueue(playOptions: PlayOptions): PlayerException? {
        currentQueue = playOptions.ids
        currentQueueIndex = playOptions.startIndex
        downloadOnlyQueue = playOptions.playFromDownloads == true
        resetPlaybackFallback()

        val itemId = when {
            currentQueue.isNotEmpty() -> currentQueue.getOrNull(currentQueueIndex)
            else -> playOptions.mediaSourceId?.toUUIDOrNull()
        } ?: return PlayerException.InvalidPlayOptions()

        return startPlayback(
            itemId = itemId,
            mediaSourceId = playOptions.mediaSourceId,
            startTime = playOptions.startPosition,
            audioStreamIndex = playOptions.audioStreamIndex,
            subtitleStreamIndex = playOptions.subtitleStreamIndex,
        )
    }

    /** Play/Resume and queue navigation all prefer a verified local copy before contacting the server. */
    private suspend fun startPlayback(
        itemId: UUID,
        mediaSourceId: String? = null,
        maxStreamingBitrate: Int? = null,
        startTime: Duration? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        playWhenReady: Boolean = true,
    ): PlayerException? {
        val saved = withContext(Dispatchers.IO) { downloadedMedia.find(itemId, mediaSourceId) }
        if (saved != null) return startDownloadPlayback(saved, startTime, audioStreamIndex, subtitleStreamIndex, playWhenReady)
        if (downloadOnlyQueue) return PlayerException.UnsupportedContent()
        return startRemotePlayback(itemId, mediaSourceId, maxStreamingBitrate, startTime, audioStreamIndex, subtitleStreamIndex, playWhenReady)
    }

    private suspend fun startDownloadPlayback(
        downloadFiles: DownloadFiles,
        startTime: Duration? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        playWhenReady: Boolean = true,
    ): PlayerException? {
        val (download, files) = downloadFiles
        val mainFile = files.find { it.type == DownloadFileType.ITEM } ?: return PlayerException.NetworkFailure()
        val sourceInfo = download.item.mediaSources?.firstOrNull() ?: return PlayerException.UnsupportedContent()

        val mediaSource = LocalJellyfinMediaSource(
            itemId = download.itemId,
            item = download.item,
            sourceInfo = sourceInfo,
            playSessionId = download.id.toString(),
            playbackDetails = PlaybackDetails(startTime, audioStreamIndex, subtitleStreamIndex),
            remoteFileUri = mainFile.uri,
        )
        startTime?.let { duration -> mediaSource.startTime = duration }
        // PlaybackDetails selects streams by their actual stream indices, including -1 for subtitles off.

        (getCurrentMediaSourceOrNull() as? RemoteJellyfinMediaSource)?.let { viewModel.stopTranscoding(it) }

        _currentMediaSource.value = mediaSource

        // Load new media source
        viewModel.load(mediaSource, prepareStreams(mediaSource), playWhenReady)

        return null
    }

    /**
     * Play a specific media item specified by [itemId] and [mediaSourceId].
     *
     * @return an error of type [PlayerException] or null on success.
     */
    private suspend fun startRemotePlayback(
        itemId: UUID,
        mediaSourceId: String?,
        maxStreamingBitrate: Int?,
        startTime: Duration? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        playWhenReady: Boolean = true,
        enableDirectPlay: Boolean? = null,
        enableDirectStream: Boolean? = null,
    ): PlayerException? {
        mediaSourceResolver.resolveMediaSource(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            deviceProfile = deviceProfile,
            maxStreamingBitrate = maxStreamingBitrate,
            startTime = startTime,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            enableDirectPlay = enableDirectPlay,
            enableDirectStream = enableDirectStream,
        ).onSuccess { jellyfinMediaSource ->
            // Ensure transcoding of the current element is stopped
            (getCurrentMediaSourceOrNull() as? RemoteJellyfinMediaSource)?.let { oldMediaSource ->
                viewModel.stopTranscoding(oldMediaSource)
            }

            _currentMediaSource.value = jellyfinMediaSource

            // Load new media source
            viewModel.load(jellyfinMediaSource, prepareStreams(jellyfinMediaSource), playWhenReady)
        }.onFailure { error ->
            // Should always be of this type, other errors are silently dropped
            return error as? PlayerException
        }
        return null
    }

    /**
     * Reinitialize current media source without changing settings
     */
    fun tryRestartPlayback() {
        with(getCurrentMediaSourceOrNull()) {
            when (this) {
                is LocalJellyfinMediaSource -> prepareStreams(this)
                is RemoteJellyfinMediaSource -> prepareStreams(this)
                null -> return
            }.let {
                viewModel.load(this, it, playWhenReady = true)
            }
        }
    }

    private fun resetPlaybackFallback() {
        playbackRetries = 0
        lastPlaybackError = 0L
        viewModel.cancelFallbackRetry()
    }

    /**
     * Retry playback with progressively degraded settings to work around source errors.
     *
     * Retry 1: no constraints — lets the server decide freely.
     * Retry 2: disable direct play, allowing the server to fall back to direct stream.
     * Retry 3: disable direct stream too, forcing the server to transcode.
     *
     * @param startPosition The position at which to resume playback after the retry.
     * @return true if a retry was initiated, false if retries are exhausted or not applicable.
     */
    suspend fun restartPlaybackWithFallback(startPosition: Duration): Boolean {
        val local = getCurrentMediaSourceOrNull() as? LocalJellyfinMediaSource
        if (local != null) {
            if (downloadOnlyQueue) return false
            val connectivity = get<Context>().getSystemService(ConnectivityManager::class.java)
            val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            if (network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) return false
            // A stored original may use a codec this device cannot decode. Keep normal online playback working.
            return startRemotePlayback(
                itemId = local.itemId,
                mediaSourceId = local.id,
                maxStreamingBitrate = null,
                startTime = startPosition,
                audioStreamIndex = local.selectedAudioStreamIndex,
                subtitleStreamIndex = local.selectedSubtitleStreamIndex,
                enableDirectPlay = false,
                enableDirectStream = false,
            ) == null
        }
        val currentMediaSource = getCurrentMediaSourceOrNull() as? RemoteJellyfinMediaSource ?: return false

        val now = System.currentTimeMillis()
        if (playbackRetries > 0 && now - lastPlaybackError > PLAYBACK_RETRY_RESET_MS) {
            Timber.i("Playback stabilized, resetting retry count from %d", playbackRetries)
            playbackRetries = 0
        }

        playbackRetries++
        lastPlaybackError = now

        if (playbackRetries > MAX_PLAYBACK_RETRIES) return false

        Timber.i("Retrying playback (attempt %d of %d)", playbackRetries, MAX_PLAYBACK_RETRIES)

        // If already transcoding, only retry once (for transient errors); flags are null on
        // retry 1 anyway, so no special-casing needed in the startRemotePlayback call.
        if (currentMediaSource.playMethod == PlayMethod.TRANSCODE && playbackRetries > 1) return false

        return startRemotePlayback(
            itemId = currentMediaSource.itemId,
            mediaSourceId = currentMediaSource.id,
            maxStreamingBitrate = currentMediaSource.maxStreamingBitrate,
            startTime = startPosition,
            audioStreamIndex = currentMediaSource.selectedAudioStreamIndex,
            subtitleStreamIndex = currentMediaSource.selectedSubtitleStreamIndex,
            playWhenReady = true,
            enableDirectPlay = if (playbackRetries > 1) false else null,
            enableDirectStream = if (playbackRetries > 2) false else null,
        ) == null
    }

    /**
     * Change the maximum bitrate to the specified value.
     */
    suspend fun changeBitrate(bitrate: Int?): Boolean {
        val currentMediaSource = getCurrentMediaSourceOrNull() as? RemoteJellyfinMediaSource ?: return false

        // Bitrate didn't change, ignore
        if (currentMediaSource.maxStreamingBitrate == bitrate) return true

        val currentPlayState = viewModel.getStateAndPause() ?: return false

        return startRemotePlayback(
            itemId = currentMediaSource.itemId,
            mediaSourceId = currentMediaSource.id,
            maxStreamingBitrate = bitrate,
            startTime = currentPlayState.position,
            audioStreamIndex = currentMediaSource.selectedAudioStreamIndex,
            subtitleStreamIndex = currentMediaSource.selectedSubtitleStreamIndex,
            playWhenReady = currentPlayState.playWhenReady,
        ) == null
    }

    fun hasPrevious(): Boolean = currentQueue.isNotEmpty() && currentQueueIndex > 0

    fun hasNext(): Boolean = currentQueue.isNotEmpty() && currentQueueIndex < currentQueue.lastIndex

    suspend fun previous(): Boolean {
        if (!hasPrevious()) return false

        resetPlaybackFallback()

        val previousIndex = currentQueueIndex - 1
        val error = startPlayback(
            itemId = currentQueue[previousIndex],
            maxStreamingBitrate = (getCurrentMediaSourceOrNull() as? RemoteJellyfinMediaSource)?.maxStreamingBitrate,
        )
        if (error != null) return false
        currentQueueIndex = previousIndex
        return true
    }

    suspend fun next(): Boolean {
        if (!hasNext()) return false

        resetPlaybackFallback()

        val nextIndex = currentQueueIndex + 1
        val error = startPlayback(
            itemId = currentQueue[nextIndex],
            maxStreamingBitrate = (getCurrentMediaSourceOrNull() as? RemoteJellyfinMediaSource)?.maxStreamingBitrate,
        )
        if (error != null) return false
        currentQueueIndex = nextIndex
        return true
    }

    /**
     * Builds the [MediaSource] to be played by ExoPlayer.
     *
     * @param source The [JellyfinMediaSource] object containing all necessary info about the item to be played.
     * @return A [MediaSource]. This can be the media stream of the correct type for the playback method or
     * a [MergingMediaSource] containing the mentioned media stream and all external subtitle streams.
     */
    @CheckResult
    private fun prepareStreams(source: LocalJellyfinMediaSource): MediaSource {
        return createDownloadVideoMediaSource(source.id, source.remoteFileUri)
    }

    private fun prepareStreams(source: RemoteJellyfinMediaSource): MediaSource {
        val subtitleConfigurations = createExternalSubtitleMediaSources(source)
        return createVideoMediaSource(source, subtitleConfigurations)
    }

    /**
     * Builds the [MediaSource] for the main media stream (video/audio/embedded subs).
     *
     * @param source The [JellyfinMediaSource] object containing all necessary info about the item to be played.
     * @return A [MediaSource]. The type of MediaSource depends on the playback method/protocol.
     */
    @CheckResult
    private fun createVideoMediaSource(
        source: JellyfinMediaSource,
        externalSubtitleConfigurations: List<MediaItem.SubtitleConfiguration>
    ): MediaSource {
        val sourceInfo = source.sourceInfo
        val (url, forcedMimeType) = when (source.playMethod) {
            PlayMethod.DIRECT_PLAY -> {
                when (sourceInfo.protocol) {
                    MediaProtocol.FILE -> {
                        val url = videosApi.getVideoStreamUrl(
                            itemId = source.itemId,
                            static = true,
                            playSessionId = source.playSessionId,
                            mediaSourceId = source.id,
                            deviceId = apiClient.deviceInfo.id,
                        )

                        url to null
                    }
                    MediaProtocol.HTTP -> {
                        val url = requireNotNull(sourceInfo.path)

                        url to MimeTypes.APPLICATION_M3U8
                    }
                    else -> throw IllegalArgumentException("Unsupported protocol ${sourceInfo.protocol}")
                }
            }
            PlayMethod.DIRECT_STREAM -> {
                val container = requireNotNull(sourceInfo.container) { "Missing direct stream container" }
                val url = videosApi.getVideoStreamByContainerUrl(
                    itemId = source.itemId,
                    container = container,
                    playSessionId = source.playSessionId,
                    mediaSourceId = source.id,
                    deviceId = apiClient.deviceInfo.id,
                )

                url to null
            }
            PlayMethod.TRANSCODE -> {
                val transcodingPath = requireNotNull(sourceInfo.transcodingUrl) { "Missing transcode URL" }
                val protocol = sourceInfo.transcodingSubProtocol
                require(protocol == MediaStreamProtocol.HLS) { "Unsupported transcode protocol '$protocol'" }
                val transcodingUrl = apiClient.createUrl(transcodingPath)

                transcodingUrl to MimeTypes.APPLICATION_M3U8
            }
        }

        // DefaultMediaSourceFactory
        val factory = get<MediaSource.Factory>()

        val mediaItem = MediaItem.Builder()
            .setMediaId(source.itemId.toString())
            .setUri(url)
            .setMimeType(forcedMimeType)
            .setSubtitleConfigurations(externalSubtitleConfigurations)
            .build()

        return factory.createMediaSource(mediaItem)
    }

    /**
     * Creates [MediaSource]s for all external subtitle streams in the [JellyfinMediaSource].
     *
     * @param source The [JellyfinMediaSource] object containing all necessary info about the item to be played.
     * @return The parsed MediaSources for the subtitles.
     */
    @CheckResult
    private fun createExternalSubtitleMediaSources(
        source: JellyfinMediaSource,
    ): List<MediaItem.SubtitleConfiguration> {
        return source.externalSubtitleStreams.map { stream ->
            val uri = apiClient.createUrl(stream.deliveryUrl).toUri()
            MediaItem.SubtitleConfiguration.Builder(uri).apply {
                setId("${ExternalSubtitleStream.ID_PREFIX}${stream.index}")
                setLabel(stream.displayTitle)
                setMimeType(stream.mimeType)
                setLanguage(stream.language)
            }.build()
        }.toList()
    }

    @CheckResult
    private fun createDownloadVideoMediaSource(mediaSourceId: String, fileUri: Uri): MediaSource {
        val factory: MediaSource.Factory = get()

        val mediaItem = MediaItem.Builder()
            .setMediaId(mediaSourceId)
            .setUri(fileUri)
            .setCustomCacheKey(fileUri.toString())
            .build()

        return factory.createMediaSource(mediaItem)
    }

    /**
     * Switch to the specified [audio stream][stream] and restart playback, for example while transcoding.
     *
     * @return true if playback was restarted with the new selection.
     */
    suspend fun selectAudioStreamAndRestartPlayback(stream: MediaStream): Boolean {
        require(stream.type == MediaStreamType.AUDIO)
        val currentPlayState = viewModel.getStateAndPause() ?: return false
        resetPlaybackFallback()

        when (val currentMediaSource = getCurrentMediaSourceOrNull()) {
            is LocalJellyfinMediaSource -> startPlayback(
                itemId = currentMediaSource.itemId,
                startTime = currentPlayState.position,
                audioStreamIndex = stream.index,
                subtitleStreamIndex = currentMediaSource.selectedSubtitleStreamIndex,
                playWhenReady = currentPlayState.playWhenReady,
            )
            is RemoteJellyfinMediaSource -> startRemotePlayback(
                itemId = currentMediaSource.itemId,
                mediaSourceId = currentMediaSource.id,
                maxStreamingBitrate = currentMediaSource.maxStreamingBitrate,
                startTime = currentPlayState.position,
                audioStreamIndex = stream.index,
                subtitleStreamIndex = currentMediaSource.selectedSubtitleStreamIndex,
                playWhenReady = currentPlayState.playWhenReady,
            )
            null -> return false
        }
        return true
    }

    /**
     * Switch to the specified [subtitle stream][stream] and restart playback,
     * for example because the selected subtitle has to be encoded into the video.
     *
     * @param stream The subtitle stream to select, or null to disable subtitles.
     * @return true if playback was restarted with the new selection.
     */
    suspend fun selectSubtitleStreamAndRestartPlayback(stream: MediaStream?): Boolean {
        require(stream == null || stream.type == MediaStreamType.SUBTITLE)
        val currentPlayState = viewModel.getStateAndPause() ?: return false
        resetPlaybackFallback()

        when (val mediaSource = getCurrentMediaSourceOrNull()) {
            is LocalJellyfinMediaSource -> startPlayback(
                itemId = mediaSource.itemId,
                startTime = currentPlayState.position,
                audioStreamIndex = mediaSource.selectedAudioStreamIndex,
                subtitleStreamIndex = stream?.index ?: -1, // -1 disables subtitles, null would select the default subtitle
                playWhenReady = currentPlayState.playWhenReady,
            )
            is RemoteJellyfinMediaSource -> startRemotePlayback(
                itemId = mediaSource.itemId,
                mediaSourceId = mediaSource.id,
                maxStreamingBitrate = mediaSource.maxStreamingBitrate,
                startTime = currentPlayState.position,
                audioStreamIndex = mediaSource.selectedAudioStreamIndex,
                subtitleStreamIndex = stream?.index ?: -1, // -1 disables subtitles, null would select the default subtitle
                playWhenReady = currentPlayState.playWhenReady,
            )
            null -> return false
        }
        return true
    }

    companion object {
        private const val MAX_PLAYBACK_RETRIES = 3
        private const val PLAYBACK_RETRY_RESET_MS = 30_000L
    }
}
