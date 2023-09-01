package me.rhunk.snapenhance.core.download

import android.content.Intent
import android.os.Bundle
import me.rhunk.snapenhance.ModContext
import me.rhunk.snapenhance.bridge.DownloadCallback
import me.rhunk.snapenhance.core.download.data.DashOptions
import me.rhunk.snapenhance.core.download.data.DownloadMediaType
import me.rhunk.snapenhance.core.download.data.DownloadMetadata
import me.rhunk.snapenhance.core.download.data.DownloadRequest
import me.rhunk.snapenhance.core.download.data.InputMedia
import me.rhunk.snapenhance.core.download.data.MediaEncryptionKeyPair
import me.rhunk.snapenhance.data.ContentType

class DownloadManagerClient (
    private val context: ModContext,
    private val metadata: DownloadMetadata,
    private val callback: DownloadCallback
) {
    companion object {
        const val DOWNLOAD_REQUEST_EXTRA = "request"
        const val DOWNLOAD_METADATA_EXTRA = "metadata"
    }

    private fun enqueueDownloadRequest(request: DownloadRequest) {
        context.bridgeClient.enqueueDownload(Intent().apply {
            putExtras(Bundle().apply {
                putString(DOWNLOAD_REQUEST_EXTRA, context.gson.toJson(request))
                putString(DOWNLOAD_METADATA_EXTRA, context.gson.toJson(metadata))
            })
        }, callback)
    }

    fun downloadDashMedia(playlistUrl: String, offsetTime: Long, duration: Long?) {
        enqueueDownloadRequest(
            DownloadRequest(
                inputMedias = arrayOf(
                    InputMedia(
                    content = playlistUrl,
                    type = DownloadMediaType.REMOTE_MEDIA
                )
                ),
                dashOptions = DashOptions(offsetTime, duration),
                flags = DownloadRequest.Flags.IS_DASH_PLAYLIST
            )
        )
    }

    fun downloadSingleMedia(
        mediaData: String,
        mediaType: DownloadMediaType,
        encryption: MediaEncryptionKeyPair? = null,
        messageContentType: ContentType? = null
    ) {
        enqueueDownloadRequest(
            DownloadRequest(
                inputMedias = arrayOf(
                    InputMedia(
                        content = mediaData,
                        type = mediaType,
                        encryption = encryption,
                        messageContentType = messageContentType?.name
                    )
                )
            )
        )
    }

    fun downloadMediaWithOverlay(
        original: InputMedia,
        overlay: InputMedia,
    ) {
        enqueueDownloadRequest(
            DownloadRequest(
                inputMedias = arrayOf(original, overlay),
                flags = DownloadRequest.Flags.MERGE_OVERLAY
            )
        )
    }
}