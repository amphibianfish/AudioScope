package jp.audioscope.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.audioscope.player.data.db.PlaylistWithTracks
import jp.audioscope.player.model.AlbumGroup
import jp.audioscope.player.model.ArtistGroup
import jp.audioscope.player.model.ByteUnitFormatter
import jp.audioscope.player.model.FolderGroup
import jp.audioscope.player.model.LibraryTab
import jp.audioscope.player.model.TrackItem
import jp.audioscope.player.model.TrackSortOrder
import jp.audioscope.player.ui.AudioScopeUiState
import jp.audioscope.player.ui.components.AlbumArtworkCard
import jp.audioscope.player.ui.theme.ChampagneGold
import jp.audioscope.player.ui.theme.LosslessCyan
import jp.audioscope.player.ui.theme.ObsidianSurfaceElevated
import jp.audioscope.player.ui.theme.OutlineSubtle
import jp.audioscope.player.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    uiState: AudioScopeUiState,
    onRequestAudioPermission: () -> Unit,
    onOpenSafFile: () -> Unit,
    onOpenSafFolder: () -> Unit,
    onPlayStreamUrl: (url: String, title: String?) -> Unit,
    onRescanLibrary: () -> Unit,
    onSelectTab: (LibraryTab) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleHiRes: () -> Unit,
    onToggleLossless: () -> Unit,
    onSelectSortOrder: (TrackSortOrder) -> Unit,
    onPlayTrackFromList: (List<TrackItem>, Int) -> Unit,
    onAddToQueue: (TrackItem) -> Unit,
    onToggleFavorite: (TrackItem) -> Unit,
    onCreatePlaylist: (String, TrackItem?) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onAddTrackToPlaylist: (Long, TrackItem) -> Unit,
    onPlayPlaylist: (PlaylistWithTracks, Int) -> Unit,
    onClearHistory: () -> Unit,
    onSelectForCompare: (TrackItem, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var trackPendingPlaylist by remember { mutableStateOf<TrackItem?>(null) }
    var showStreamUrlDialog by remember { mutableStateOf(false) }
    var selectedAlbumDetail by remember { mutableStateOf<AlbumGroup?>(null) }
    var selectedArtistDetail by remember { mutableStateOf<ArtistGroup?>(null) }
    var selectedFolderDetail by remember { mutableStateOf<FolderGroup?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "AudioScope",
                    style = MaterialTheme.typography.headlineSmall,
                    color = ChampagneGold
                )
                Text(
                    text = "全 ${uiState.allTracks.size} 曲 • Hi-Res ${uiState.formatStats.hiResTracks} 曲",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenSafFile) {
                    Icon(Icons.Rounded.AudioFile, contentDescription = "音声ファイルを開く")
                }
                IconButton(onClick = onOpenSafFolder) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = "フォルダーを追加")
                }
                IconButton(onClick = { showStreamUrlDialog = true }) {
                    Icon(Icons.Rounded.Language, contentDescription = "HTTPSストリーム再生")
                }
                IconButton(onClick = onRescanLibrary) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "ライブラリ再スキャン")
                }
            }
        }

        // Search & Filter Bar
        OutlinedTextField(
            value = uiState.filter.searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            placeholder = { Text("曲名・アーティスト・アルバム・形式(FLAC等)で検索") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (uiState.filter.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "クリア")
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            FilterChip(
                selected = uiState.filter.hiResOnly,
                onClick = onToggleHiRes,
                label = { Text("ハイレゾのみ") }
            )
            FilterChip(
                selected = uiState.filter.losslessOnly,
                onClick = onToggleLossless,
                label = { Text("ロスレスのみ (FLAC/WAV/ALAC)") }
            )
            Box {
                FilterChip(
                    selected = false,
                    onClick = { showSortMenu = true },
                    leadingIcon = { Icon(Icons.Rounded.Sort, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(uiState.filter.sortOrder.labelJp) }
                )
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    TrackSortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(order.labelJp) },
                            onClick = {
                                onSelectSortOrder(order)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
        }

        // Permission Notice Card (Non-blocking: SAF still works if denied!)
        if (!uiState.hasAudioReadPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .border(1.dp, ChampagneGold.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "端末内の音楽ライブラリ自動検索について",
                        style = MaterialTheme.typography.titleMedium,
                        color = ChampagneGold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "MediaStoreから端末内の音楽ファイルを自動検索するには「音楽とオーディオ」権限が必要です。権限を許可しない場合でも、右上のファイル/フォルダーアイコン（Storage Access Framework）から個別に曲を開いて再生・音質解析できます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onRequestAudioPermission) {
                            Text("権限を許可して自動検索")
                        }
                        OutlinedButton(onClick = onOpenSafFile) {
                            Text("ファイルを直接選ぶ")
                        }
                    }
                }
            }
        }

        // Category Tabs
        PrimaryScrollableTabRow(
            selectedTabIndex = uiState.filter.selectedTab.ordinal,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.background,
            divider = { HorizontalDivider(color = OutlineSubtle) }
        ) {
            LibraryTab.entries.forEach { tab ->
                Tab(
                    selected = uiState.filter.selectedTab == tab,
                    onClick = {
                        selectedAlbumDetail = null
                        selectedArtistDetail = null
                        selectedFolderDetail = null
                        onSelectTab(tab)
                    },
                    text = { Text(tab.labelJp) }
                )
            }
        }

        if (uiState.isScanningLibrary) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ChampagneGold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("音楽ライブラリをスキャン中...", color = TextSecondary)
                }
            }
            return@Column
        }

        // Tab Content
        when (uiState.filter.selectedTab) {
            LibraryTab.TRACKS -> TrackListContent(
                tracks = uiState.filteredTracks,
                favoriteUriSet = uiState.favoriteUriSet,
                onPlayTrack = { idx -> onPlayTrackFromList(uiState.filteredTracks, idx) },
                onAddToQueue = onAddToQueue,
                onToggleFavorite = onToggleFavorite,
                onRequestAddToPlaylist = { trackPendingPlaylist = it },
                onSelectForCompare = onSelectForCompare
            )

            LibraryTab.ALBUMS -> {
                val detail = selectedAlbumDetail
                if (detail != null) {
                    GroupDrilldownHeader(
                        title = detail.albumName,
                        subtitle = "${detail.artistName} • ${detail.tracks.size} 曲",
                        onBack = { selectedAlbumDetail = null }
                    )
                    TrackListContent(
                        tracks = detail.tracks,
                        favoriteUriSet = uiState.favoriteUriSet,
                        onPlayTrack = { idx -> onPlayTrackFromList(detail.tracks, idx) },
                        onAddToQueue = onAddToQueue,
                        onToggleFavorite = onToggleFavorite,
                        onRequestAddToPlaylist = { trackPendingPlaylist = it },
                        onSelectForCompare = onSelectForCompare
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 156.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.albumGroups, key = { "${it.albumId}_${it.albumName}" }) { album ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedAlbumDetail = album }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    AlbumArtworkCard(
                                        artworkUri = album.artworkUri,
                                        title = album.albumName,
                                        cornerRadius = 12.dp,
                                        isHiRes = album.tracks.any { it.isLikelyHiRes },
                                        isLossless = album.tracks.any { it.isLikelyLossless },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(140.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = album.albumName,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${album.artistName} (${album.tracks.size}曲)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            LibraryTab.ARTISTS -> {
                val detail = selectedArtistDetail
                if (detail != null) {
                    GroupDrilldownHeader(
                        title = detail.artistName,
                        subtitle = "${detail.albumCount} アルバム • ${detail.tracks.size} 曲",
                        onBack = { selectedArtistDetail = null }
                    )
                    TrackListContent(
                        tracks = detail.tracks,
                        favoriteUriSet = uiState.favoriteUriSet,
                        onPlayTrack = { idx -> onPlayTrackFromList(detail.tracks, idx) },
                        onAddToQueue = onAddToQueue,
                        onToggleFavorite = onToggleFavorite,
                        onRequestAddToPlaylist = { trackPendingPlaylist = it },
                        onSelectForCompare = onSelectForCompare
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.artistGroups, key = { it.artistName }) { artist ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedArtistDetail = artist }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Person, contentDescription = null, tint = ChampagneGold)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(artist.artistName, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${artist.albumCount} アルバム • ${artist.tracks.size} 曲",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LibraryTab.FOLDERS -> {
                val detail = selectedFolderDetail
                if (detail != null) {
                    GroupDrilldownHeader(
                        title = detail.folderDisplayName,
                        subtitle = "${detail.folderPath} (${detail.tracks.size} 曲)",
                        onBack = { selectedFolderDetail = null }
                    )
                    TrackListContent(
                        tracks = detail.tracks,
                        favoriteUriSet = uiState.favoriteUriSet,
                        onPlayTrack = { idx -> onPlayTrackFromList(detail.tracks, idx) },
                        onAddToQueue = onAddToQueue,
                        onToggleFavorite = onToggleFavorite,
                        onRequestAddToPlaylist = { trackPendingPlaylist = it },
                        onSelectForCompare = onSelectForCompare
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.folderGroups, key = { it.folderPath }) { folder ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFolderDetail = folder }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = LosslessCyan)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(folder.folderDisplayName, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${folder.folderPath} • ${folder.tracks.size} 曲",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LibraryTab.PLAYLISTS -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("カスタムプレイリスト (${uiState.playlists.size})", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { showCreatePlaylistDialog = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("新規作成")
                        }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.playlists, key = { it.playlist.playlistId }) { pl ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(Icons.Rounded.PlaylistPlay, contentDescription = null, tint = ChampagneGold)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(pl.playlist.name, style = MaterialTheme.typography.titleMedium)
                                                Text("${pl.tracks.size} 曲", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                            }
                                        }
                                        Row {
                                            if (pl.tracks.isNotEmpty()) {
                                                TextButton(onClick = { onPlayPlaylist(pl, 0) }) {
                                                    Text("すべて再生")
                                                }
                                            }
                                            IconButton(onClick = { onDeletePlaylist(pl.playlist.playlistId) }) {
                                                Icon(Icons.Rounded.Delete, contentDescription = "削除", tint = TextSecondary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LibraryTab.FAVORITES -> {
                val favTracks = uiState.favorites.mapNotNull { fav ->
                    uiState.allTracks.firstOrNull { it.contentUri.toString() == fav.trackUri }
                }
                TrackListContent(
                    tracks = favTracks,
                    favoriteUriSet = uiState.favoriteUriSet,
                    emptyLabel = "お気に入り登録された曲がまだありません。",
                    onPlayTrack = { idx -> onPlayTrackFromList(favTracks, idx) },
                    onAddToQueue = onAddToQueue,
                    onToggleFavorite = onToggleFavorite,
                    onRequestAddToPlaylist = { trackPendingPlaylist = it },
                    onSelectForCompare = onSelectForCompare
                )
            }

            LibraryTab.HISTORY -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (uiState.history.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onClearHistory) {
                                Text("履歴をクリア")
                            }
                        }
                    }
                    val df = remember { SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN) }
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.history, key = { it.historyId }) { h ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val match = uiState.allTracks.firstOrNull { it.contentUri.toString() == h.trackUri }
                                        if (match != null) onPlayTrackFromList(listOf(match), 0)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(h.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${h.artist} • ${h.codecSummary}", style = MaterialTheme.typography.labelSmall, color = LosslessCyan)
                                    }
                                    Text(
                                        text = df.format(Date(h.playedAtEpochMs)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Create Playlist Dialog
    if (showCreatePlaylistDialog) {
        var plName by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("新規プレイリスト作成") },
            text = {
                OutlinedTextField(
                    value = plName,
                    onValueChange = { plName = it },
                    singleLine = true,
                    label = { Text("プレイリスト名") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCreatePlaylist(plName, null)
                        showCreatePlaylistDialog = false
                    }
                ) { Text("作成") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("キャンセル") }
            }
        )
    }

    // Add Track to Playlist Dialog
    trackPendingPlaylist?.let { targetTrack ->
        var newName by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { trackPendingPlaylist = null },
            title = { Text("「${targetTrack.title}」をプレイリストに追加") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.playlists.forEach { pl ->
                        OutlinedButton(
                            onClick = {
                                onAddTrackToPlaylist(pl.playlist.playlistId, targetTrack)
                                trackPendingPlaylist = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${pl.playlist.name} (${pl.tracks.size}曲)")
                        }
                    }
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("または新規プレイリスト名を入力") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        onCreatePlaylist(newName, targetTrack)
                        trackPendingPlaylist = null
                    }
                ) { Text("新規作成して追加") }
            },
            dismissButton = {
                TextButton(onClick = { trackPendingPlaylist = null }) { Text("閉じる") }
            }
        )
    }

    // Network HTTPS Stream Dialog (for testing network playback & TrafficStats)
    if (showStreamUrlDialog) {
        var urlText by rememberSaveable { mutableStateOf("https://") }
        var customTitle by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showStreamUrlDialog = false },
            title = { Text("ネットワーク音声ストリーム再生") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "HTTPS経由のFLAC / WAV / MP3 / AAC / OGGストリームURLを指定すると、再生と同時にNETWORKモニターでリアルタイム受信速度を確認できます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        singleLine = true,
                        label = { Text("音声URL (https://...)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        singleLine = true,
                        label = { Text("表示タイトル（任意）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onPlayStreamUrl(urlText, customTitle)
                        showStreamUrlDialog = false
                    }
                ) { Text("再生開始") }
            },
            dismissButton = {
                TextButton(onClick = { showStreamUrlDialog = false }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun GroupDrilldownHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurfaceElevated)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        OutlinedButton(onClick = onBack) {
            Text("一覧へ戻る")
        }
    }
}

@Composable
private fun TrackListContent(
    tracks: List<TrackItem>,
    favoriteUriSet: Set<String>,
    emptyLabel: String = "表示できる音楽ファイルがありません。右上のファイル/フォルダーボタンまたは再スキャンをお試しください。",
    onPlayTrack: (Int) -> Unit,
    onAddToQueue: (TrackItem) -> Unit,
    onToggleFavorite: (TrackItem) -> Unit,
    onRequestAddToPlaylist: (TrackItem) -> Unit,
    onSelectForCompare: (TrackItem, Boolean) -> Unit
) {
    if (tracks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(tracks, key = { _, item -> item.contentUri.toString() }) { index, track ->
            var menuExpanded by remember { mutableStateOf(false) }
            val isFav = favoriteUriSet.contains(track.contentUri.toString())

            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayTrack(index) }
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArtworkCard(
                        artworkUri = track.artworkUri,
                        title = track.title,
                        cornerRadius = 10.dp,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${track.artist} • ${track.album}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = track.fileExtension,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (track.isLikelyHiRes) ChampagneGold
                                else if (track.isLikelyLossless) LosslessCyan
                                else TextSecondary
                            )
                            if (track.sizeBytes > 0L) {
                                Text(
                                    text = ByteUnitFormatter.formatBytes(track.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            if (track.durationMs > 0L) {
                                Text(
                                    text = formatDurationMmSs(track.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    IconButton(onClick = { onToggleFavorite(track) }) {
                        Icon(
                            imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "お気に入り",
                            tint = if (isFav) ChampagneGold else TextSecondary
                        )
                    }

                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "メニュー")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("今すぐ再生") },
                                onClick = {
                                    menuExpanded = false
                                    onPlayTrack(index)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("再生キュー末尾に追加") },
                                onClick = {
                                    menuExpanded = false
                                    onAddToQueue(track)
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) },
                                text = { Text("プレイリストに追加") },
                                onClick = {
                                    menuExpanded = false
                                    onRequestAddToPlaylist(track)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("音質比較 [スロットA] に設定") },
                                onClick = {
                                    menuExpanded = false
                                    onSelectForCompare(track, true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("音質比較 [スロットB] に設定") },
                                onClick = {
                                    menuExpanded = false
                                    onSelectForCompare(track, false)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatDurationMmSs(durationMs: Long): String {
    val totalSec = (durationMs.coerceAtLeast(0L)) / 1000L
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
