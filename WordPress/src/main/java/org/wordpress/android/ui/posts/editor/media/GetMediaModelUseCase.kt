package org.wordpress.android.ui.posts.editor.media

import android.net.Uri
import dagger.Reusable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.wordpress.android.fluxc.model.MediaModel
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.store.MediaStore
import org.wordpress.android.modules.BG_THREAD
import org.wordpress.android.util.FluxCUtilsWrapper
import org.wordpress.android.util.MediaUtilsWrapper
import java.io.File
import javax.inject.Inject
import javax.inject.Named

/**
 * Helper class for retrieving/creating MediaModel from the provided data.
 */
@Reusable
class GetMediaModelUseCase @Inject constructor(
    private val fluxCUtilsWrapper: FluxCUtilsWrapper,
    private val mediaUtilsWrapper: MediaUtilsWrapper,
    private val mediaStore: MediaStore,
    @Named(BG_THREAD) private val bgDispatcher: CoroutineDispatcher
) {
    suspend fun loadMediaModelFromDb(mediaModelLocalIds: Iterable<Int>): List<MediaModel> {
        return withContext(bgDispatcher) {
            mediaModelLocalIds
                    .mapNotNull {
                        mediaStore.getMediaWithLocalId(it)
                    }
        }
    }

    suspend fun loadMediaModelFromDb(site: SiteModel, mediaModelsRemoteIds: Iterable<Long>): List<MediaModel> {
        return withContext(bgDispatcher) {
            mediaModelsRemoteIds
                    // TODO should we show a toast or log a message when getSiteMediaWithId returns null?
                    .mapNotNull {
                        mediaStore.getSiteMediaWithId(site, it)
                    }
        }
    }

    suspend fun createMediaModelFromUri(localSiteId: Int, uri: Uri): CreateMediaModelsResult {
        return createMediaModelFromUri(localSiteId, listOf(uri))
    }

    suspend fun createMediaModelFromUri(localSiteId: Int, uris: List<Uri>): CreateMediaModelsResult {
        return withContext(bgDispatcher) {
            uris
                    .map { uri ->
                        if (verifyFileExists(uri)) {
                            createNewMediaModel(localSiteId, uri)
                        } else {
                            null
                        }
                    }
                    .toList()
                    .let {
                        CreateMediaModelsResult(
                                mediaModels = it.filterNotNull(),
                                loadingSomeMediaFailed = it.contains(null)
                        )
                    }
        }
    }

    private fun createNewMediaModel(
        localSiteId: Int,
        uri: Uri
    ): MediaModel? {
        val mimeType = mediaUtilsWrapper.getMimeType(uri)
        return fluxCUtilsWrapper.mediaModelFromLocalUri(uri, mimeType, localSiteId)?.let { media ->
            setThumbnailIfAvailable(media, uri)
            media
        }
    }

    private fun setThumbnailIfAvailable(
        media: MediaModel,
        uri: Uri
    ) {
        if (mediaUtilsWrapper.isVideoMimeType(media.mimeType)) {
            media.thumbnailUrl = createVideoThumbnail(uri)
        }
    }

    private fun createVideoThumbnail(uri: Uri): String? {
        val path = mediaUtilsWrapper.getRealPathFromURI(uri)
        return path?.let { mediaUtilsWrapper.getVideoThumbnail(it) }
    }

    private fun verifyFileExists(uri: Uri): Boolean {
        return mediaUtilsWrapper.getRealPathFromURI(uri)?.let { path -> File(path).exists() }
                ?: false
    }

    data class CreateMediaModelsResult(
        val mediaModels: List<MediaModel>,
        val loadingSomeMediaFailed: Boolean
    )
}
