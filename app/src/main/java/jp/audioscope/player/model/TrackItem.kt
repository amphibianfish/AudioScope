package jp.audioscope.player.model

import android.net.Uri

data class TrackItem(
    val id: Long,
    val contentUri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val artworkUri: Uri?,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val displayName: String,
    val relativeFolder: String,
    val displayPathOrUri: String,
    val dateAddedSec: Long,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val mediaStoreBitrateBps: Int? = null,
    val mediaStoreSampleRateHz: Int? = null,
    val mediaStoreBitsPerSample: Int? = null,
    val isNetworkStream: Boolean = false
) {
    val fileExtension: String
        get() = displayName.substringAfterLast('.', "").uppercase().ifBlank {
            mimeType.substringAfterLast('/').uppercase()
        }

    val isLikelyLossless: Boolean
        get() {
            val ext = fileExtension
            val mime = mimeType.lowercase()
            return ext in setOf("FLAC", "WAV", "ALAC", "APE", "WV", "AIFF", "AIF") ||
                mime.contains("flac") || mime.contains("wav") || mime.contains("alac") || mime.contains("raw")
        }

    val isLikelyHiRes: Boolean
        get() {
            val sr = mediaStoreSampleRateHz ?: 0
            val bps = mediaStoreBitsPerSample ?: 0
            return (sr > 48_000) || (bps > 16 && isLikelyLossless)
        }
}

data class AlbumGroup(
    val albumId: Long,
    val albumName: String,
    val artistName: String,
    val artworkUri: Uri?,
    val tracks: List<TrackItem>
)

data class ArtistGroup(
    val artistName: String,
    val albumCount: Int,
    val tracks: List<TrackItem>
)

data class FolderGroup(
    val folderPath: String,
    val folderDisplayName: String,
    val tracks: List<TrackItem>
)

enum class LibraryTab(val labelJp: String) {
    TRACKS("曲"),
    ALBUMS("アルバム"),
    ARTISTS("アーティスト"),
    FOLDERS("フォルダー"),
    PLAYLISTS("プレイリスト"),
    FAVORITES("お気に入り"),
    HISTORY("再生履歴")
}

enum class TrackSortOrder(val labelJp: String) {
    TITLE_ASC("曲名順"),
    ARTIST_ASC("アーティスト順"),
    ALBUM_ASC("アルバム順"),
    DATE_ADDED_DESC("追加日（新しい順）"),
    SIZE_DESC("ファイルサイズ順")
}

enum class RepeatModeState(val labelJp: String) {
    OFF("リピートOFF"),
    ALL("全曲リピート"),
    ONE("1曲リピート")
}
