package org.akanework.gramophone

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import org.robolectric.RuntimeEnvironment
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Size
import coil3.toCoilUri
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.akanework.gramophone.logic.GramophoneAlbumArtProvider
import org.akanework.gramophone.logic.utils.CoilArtPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Squishy: List thumbnails come from the system (MediaStore). Samsung on Android 13 ignores a
 * cover.jpg next to the audio file there, while the app's own lookup (big player) finds it.
 * If the system has no thumbnail, the app must fall back to its own lookup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ThumbnailFallbackTest {

    private val context = RuntimeEnvironment.getApplication()
    private val songFile = File("/storage/emulated/0/Musik/Nena/Nena/01 - 99 Luftballons.mp3")
    private val smallOptions get() = Options(context, Size(100, 100))

    @Test
    fun smallSongCoverCarriesFallbackWithSongFile() {
        val uri = GramophoneAlbumArtProvider.buildSongUri(42, songFile).toCoilUri()
        val data = CoilArtPipeline.ThumbnailMapper().map(uri, smallOptions)
        assertNotNull(data)
        assertEquals(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 42),
            data!!.uri)
        assertEquals(CoilArtPipeline.LoadAudioCoverData(42, songFile), data.fallback)
    }

    @Test
    fun bigSongCoverIsNotAThumbnail() {
        val uri = GramophoneAlbumArtProvider.buildSongUri(42, songFile).toCoilUri()
        assertNull(CoilArtPipeline.ThumbnailMapper().map(uri, Options(context, Size(2000, 2000))))
    }

    @Test
    fun missingSystemThumbnailFallsBackToOwnLookup() = runBlocking {
        val fallback = CoilArtPipeline.LoadAudioCoverData(42, songFile)
        val expected = SourceFetchResult(ImageSource(Buffer(), okio.FileSystem.SYSTEM),
            null, DataSource.DISK)
        var asked: CoilArtPipeline.LoadAudioCoverData? = null
        val loader = ImageLoader.Builder(context).components {
            add(Fetcher.Factory<CoilArtPipeline.LoadAudioCoverData> { data, _, _ ->
                Fetcher { asked = data; expected }
            })
        }.build()
        // No MediaStore provider in Robolectric: the system thumbnail fails like on the tablet.
        val data = CoilArtPipeline.LoadThumbnailData(
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 42), fallback)
        val result = CoilArtPipeline.ThumbnailFetcherFactory()
            .create(data, smallOptions, loader).fetch()
        assertSame(expected, result)
        assertEquals(fallback, asked)
    }

    @Test
    fun withoutFallbackTheErrorStays() {
        val loader = ImageLoader.Builder(context).build()
        val data = CoilArtPipeline.LoadThumbnailData(
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 42))
        try {
            runBlocking {
                CoilArtPipeline.ThumbnailFetcherFactory().create(data, smallOptions, loader).fetch()
            }
            fail("expected an exception")
        } catch (_: Exception) {
        }
    }
}
