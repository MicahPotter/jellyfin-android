package org.jellyfin.mobile.bridge

import android.webkit.JavascriptInterface
import kotlinx.coroutines.channels.Channel
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.downloads.DownloadedMediaResolver
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.interaction.PlayerEvent
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import kotlin.time.Duration.Companion.milliseconds

@Suppress("unused")
class NativePlayer(
    private val appPreferences: AppPreferences,
    private val activityEventHandler: ActivityEventHandler,
    private val playerEventChannel: Channel<PlayerEvent>,
    private val downloadedMedia: DownloadedMediaResolver,
) {

    @JavascriptInterface
    fun isEnabled() = appPreferences.videoPlayerType == VideoPlayerType.EXO_PLAYER

    @JavascriptInterface
    fun hasDownload(itemId: String, mediaSourceId: String): Boolean {
        val id = itemId.toUUIDOrNull() ?: return false
        return downloadedMedia.find(id, mediaSourceId.takeIf(String::isNotEmpty)) != null
    }

    @JavascriptInterface
    fun loadPlayer(args: String) {
        PlayOptions.fromJson(args)?.let { options ->
            activityEventHandler.emit(ActivityEvent.LaunchNativePlayer(options))
        }
    }

    @JavascriptInterface
    fun pausePlayer() {
        playerEventChannel.trySend(PlayerEvent.Pause)
    }

    @JavascriptInterface
    fun resumePlayer() {
        playerEventChannel.trySend(PlayerEvent.Resume)
    }

    @JavascriptInterface
    fun stopPlayer() {
        playerEventChannel.trySend(PlayerEvent.Stop)
    }

    @JavascriptInterface
    fun destroyPlayer() {
        playerEventChannel.trySend(PlayerEvent.Destroy)
    }

    @JavascriptInterface
    fun seekTicks(ticks: Long) {
        playerEventChannel.trySend(PlayerEvent.Seek(ticks.ticks))
    }

    @JavascriptInterface
    fun seekMs(ms: Long) {
        playerEventChannel.trySend(PlayerEvent.Seek(ms.milliseconds))
    }

    @JavascriptInterface
    fun setVolume(volume: Int) {
        playerEventChannel.trySend(PlayerEvent.SetVolume(volume))
    }
}
