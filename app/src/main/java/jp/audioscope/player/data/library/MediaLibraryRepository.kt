package jp.audioscope.player.data.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import jp.audioscope.player.model.AlbumGroup
import jp.audioscope.player.model.ArtistGroup
import jp.audioscope.player.model.FolderGroup
import jp.audioscope.player.model.FormatStatEntry
import jp.audioscope.player.model.LibraryFormatStats
import jp.audioscope.player.model.TrackItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaLibraryRepository(private val context: Context) {

    /**
     * Android 10〜17 の推奨 MediaStore 方式に従い、すべての外部ボリュームから音楽ファイルを検索する。
     */
    suspend fun scanDeviceAudioLibrary(): List<TrackItem> = withContext(Dispatchers.IO) {
        val results = ArrayList<TrackItem>()
        val seenUris = HashSet<String>()

        val volumeNames: Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context)
        } else {
            setOf(MediaStore.VOLUME_EXTERNAL)
        }

        val baseColumns = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            baseColumns.add(MediaStore.Audio.Media.BITRATE)
        }

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} >= 5000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        for (volume in volumeNames) {
            val collectionUri = MediaStore.Audio.Media.getContentUri(volume)
            runCatching {
                context.contentResolver.query(
                    collectionUri,
                    baseColumns.toTypedArray(),
                    selection,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val relPathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                    val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                    val bitrateCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                    } else -1
                    val sampleRateCol = cursor.getColumnIndex("samplerate")
                    val bitsPerSampleCol = cursor.getColumnIndex("bits_per_sample")

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val contentUri = ContentUris.withAppendedId(collectionUri, id)
                        if (!seenUris.add(contentUri.toString())) continue

                        val displayName = cursor.getString(nameCol) ?: "Unknown"
                        val rawTitle = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                            ?: displayName.substringBeforeLast('.')
                        val rawArtist = cursor.getString(artistCol)
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                            ?: "不明なアーティスト"
                        val rawAlbum = cursor.getString(albumCol)
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                            ?: "不明なアルバム"
                        val albumId = cursor.getLong(albumIdCol)
                        val durationMs = cursor.getLong(durationCol).coerceAtLeast(0L)
                        val sizeBytes = cursor.getLong(sizeCol).coerceAtLeast(0L)
                        val mimeType = cursor.getString(mimeCol) ?: guessMimeFromName(displayName)
                        val relPath = if (relPathCol >= 0) {
                            cursor.getString(relPathCol)?.trimEnd('/')?.ifBlank { "Music" } ?: "Music"
                        } else "Music"

                        val artworkUri = if (albumId > 0L) {
                            ContentUris.withAppendedId(
                                Uri.parse("content://media/external/audio/albumart"),
                                albumId
                            )
                        } else null

                        val bitrateBps = if (bitrateCol >= 0 && !cursor.isNull(bitrateCol)) {
                            cursor.getInt(bitrateCol).takeIf { it > 0 }
                        } else null
                        val sampleRateHz = if (sampleRateCol >= 0 && !cursor.isNull(sampleRateCol)) {
                            cursor.getInt(sampleRateCol).takeIf { it > 0 }
                        } else null
                        val bitsPerSample = if (bitsPerSampleCol >= 0 && !cursor.isNull(bitsPerSampleCol)) {
                            cursor.getInt(bitsPerSampleCol).takeIf { it > 0 }
                        } else null

                        results.add(
                            TrackItem(
                                id = id,
                                contentUri = contentUri,
                                title = rawTitle,
                                artist = rawArtist,
                                album = rawAlbum,
                                albumId = albumId,
                                artworkUri = artworkUri,
                                durationMs = durationMs,
                                sizeBytes = sizeBytes,
                                mimeType = mimeType,
                                displayName = displayName,
                                relativeFolder = relPath,
                                displayPathOrUri = "$relPath/$displayName",
                                dateAddedSec = cursor.getLong(dateCol),
                                trackNumber = if (trackCol >= 0 && !cursor.isNull(trackCol)) cursor.getInt(trackCol) else 0,
                                year = if (yearCol >= 0 && !cursor.isNull(yearCol)) cursor.getInt(yearCol) else 0,
                                mediaStoreBitrateBps = bitrateBps,
                                mediaStoreSampleRateHz = sampleRateHz,
                                mediaStoreBitsPerSample = bitsPerSample,
                                isNetworkStream = false
                            )
                        )
                    }
                }
            }
        }
        results
    }

    /**
     * 権限拒否時でも Storage Access Framework (ファイル選択 / フォルダー選択) から曲を読み込めるようにする。
     */
    suspend fun resolveSafDocumentUri(uri: Uri): TrackItem? = withContext(Dispatchers.IO) {
        runCatching {
            var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "AudioFile"
            var sizeBytes = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        displayName = cursor.getString(nameIdx)
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        sizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }
            val mimeType = context.contentResolver.getType(uri) ?: guessMimeFromName(displayName)
            TrackItem(
                id = uri.toString().hashCode().toLong(),
                contentUri = uri,
                title = displayName.substringBeforeLast('.'),
                artist = "ローカルファイル (SAF)",
                album = "個別選択ファイル",
                albumId = -1L,
                artworkUri = null,
                durationMs = 0L,
                sizeBytes = sizeBytes,
                mimeType = mimeType,
                displayName = displayName,
                relativeFolder = "SAF選択",
                displayPathOrUri = uri.toString(),
                dateAddedSec = System.currentTimeMillis() / 1000L,
                isNetworkStream = false
            )
        }.getOrNull()
    }

    suspend fun resolveSafTreeUri(treeUri: Uri): List<TrackItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TrackItem>()
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        fun walk(dir: DocumentFile, prefix: String) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    val subName = child.name ?: "Subfolder"
                    walk(child, if (prefix.isEmpty()) subName else "$prefix/$subName")
                } else if (child.isFile) {
                    val name = child.name ?: continue
                    val mime = child.type ?: guessMimeFromName(name)
                    if (isSupportedAudioFile(name, mime)) {
                        list.add(
                            TrackItem(
                                id = child.uri.toString().hashCode().toLong(),
                                contentUri = child.uri,
                                title = name.substringBeforeLast('.'),
                                artist = "SAFフォルダー",
                                album = prefix.ifBlank { rootDoc.name ?: "SAFフォルダー" },
                                albumId = -1L,
                                artworkUri = null,
                                durationMs = 0L,
                                sizeBytes = child.length().coerceAtLeast(0L),
                                mimeType = mime,
                                displayName = name,
                                relativeFolder = prefix.ifBlank { rootDoc.name ?: "SAF" },
                                displayPathOrUri = child.uri.toString(),
                                dateAddedSec = child.lastModified() / 1000L,
                                isNetworkStream = false
                            )
                        )
                    }
                }
            }
        }
        runCatching { walk(rootDoc, rootDoc.name ?: "") }
        list
    }

    fun createNetworkStreamTrack(url: String, customTitle: String? = null): TrackItem {
        val cleanUrl = url.trim()
        val filePart = Uri.parse(cleanUrl).lastPathSegment?.takeIf { it.isNotBlank() } ?: "Network Audio Stream"
        return TrackItem(
            id = cleanUrl.hashCode().toLong(),
            contentUri = Uri.parse(cleanUrl),
            title = customTitle?.takeIf { it.isNotBlank() } ?: filePart.substringBeforeLast('.'),
            artist = "ネットワークストリーム",
            album = Uri.parse(cleanUrl).host ?: "HTTPS Stream",
            albumId = -1L,
            artworkUri = null,
            durationMs = 0L,
            sizeBytes = 0L,
            mimeType = guessMimeFromName(filePart),
            displayName = filePart,
            relativeFolder = "Network",
            displayPathOrUri = cleanUrl,
            dateAddedSec = System.currentTimeMillis() / 1000L,
            isNetworkStream = true
        )
    }

    fun groupAlbums(tracks: List<TrackItem>): List<AlbumGroup> {
        return tracks.groupBy { "${it.albumId}_${it.album}" }
            .map { (_, list) ->
                val first = list.first()
                AlbumGroup(
                    albumId = first.albumId,
                    albumName = first.album,
                    artistName = first.artist,
                    artworkUri = first.artworkUri,
                    tracks = list.sortedWith(compareBy({ it.trackNumber }, { it.title }))
                )
            }
            .sortedBy { it.albumName.lowercase() }
    }

    fun groupArtists(tracks: List<TrackItem>): List<ArtistGroup> {
        return tracks.groupBy { it.artist }
            .map { (artist, list) ->
                ArtistGroup(
                    artistName = artist,
                    albumCount = list.map { it.album }.distinct().size,
                    tracks = list.sortedWith(compareBy({ it.album }, { it.trackNumber }, { it.title }))
                )
            }
            .sortedBy { it.artistName.lowercase() }
    }

    fun groupFolders(tracks: List<TrackItem>): List<FolderGroup> {
        return tracks.groupBy { it.relativeFolder }
            .map { (folder, list) ->
                FolderGroup(
                    folderPath = folder,
                    folderDisplayName = folder.substringAfterLast('/').ifBlank { folder },
                    tracks = list.sortedBy { it.displayName.lowercase() }
                )
            }
            .sortedBy { it.folderPath.lowercase() }
    }

    fun computeFormatStats(tracks: List<TrackItem>): LibraryFormatStats {
        if (tracks.isEmpty()) {
            return LibraryFormatStats(0, 0L, 0, 0, 0, emptyList())
        }
        val total = tracks.size
        val totalBytes = tracks.sumOf { it.sizeBytes }
        val grouped = tracks.groupBy { it.fileExtension.ifBlank { "OTHER" } }
            .map { (ext, list) ->
                FormatStatEntry(
                    formatName = ext,
                    trackCount = list.size,
                    totalBytes = list.sumOf { it.sizeBytes },
                    percentageOfLibrary = (list.size * 100f) / total.toFloat(),
                    losslessCount = list.count { it.isLikelyLossless },
                    hiResCount = list.count { it.isLikelyHiRes }
                )
            }
            .sortedByDescending { it.trackCount }

        val losslessTotal = tracks.count { it.isLikelyLossless }
        return LibraryFormatStats(
            totalTracks = total,
            totalBytes = totalBytes,
            losslessTracks = losslessTotal,
            lossyTracks = total - losslessTotal,
            hiResTracks = tracks.count { it.isLikelyHiRes },
            entries = grouped
        )
    }

    private fun isSupportedAudioFile(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/") || mime == "application/ogg" || mime == "application/x-flac") {
            return true
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("flac", "wav", "mp3", "m4a", "aac", "ogg", "oga", "opus", "alac", "aiff", "aif")
    }

    private fun guessMimeFromName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "alac" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            else -> "audio/*"
        }
    }
}
