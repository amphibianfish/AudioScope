package jp.audioscope.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import jp.audioscope.player.AudioScopeApplication
import jp.audioscope.player.ui.screens.AudioScopeAppRoot
import jp.audioscope.player.ui.theme.AudioScopeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AudioScopeViewModel by viewModels {
        val app = application as AudioScopeApplication
        AudioScopeViewModel.provideFactory(app.container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        refreshPermissionFlags()
        handleIncomingAudioIntent(intent)

        setContent {
            val audioPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {
                refreshPermissionFlags()
            }

            val btPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {
                refreshPermissionFlags()
            }

            val safFileLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    viewModel.importSafSingleFile(uri, playImmediately = true)
                }
            }

            val safFolderLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { treeUri ->
                if (treeUri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    viewModel.importSafFolder(treeUri)
                }
            }

            AudioScopeTheme {
                AudioScopeAppRoot(
                    viewModel = viewModel,
                    onRequestAudioPermission = {
                        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_AUDIO
                        } else {
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        audioPermLauncher.launch(perm)
                    },
                    onRequestBluetoothPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            refreshPermissionFlags()
                        }
                    },
                    onOpenSafFile = {
                        safFileLauncher.launch(arrayOf("audio/*", "application/ogg", "application/x-flac"))
                    },
                    onOpenSafFolder = {
                        safFolderLauncher.launch(null)
                    },
                    onOpenDeveloperOptions = {
                        openSystemScreenSafely(
                            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                            Settings.ACTION_DEVICE_INFO_SETTINGS
                        )
                    },
                    onOpenBluetoothSettings = {
                        openSystemScreenSafely(
                            Settings.ACTION_BLUETOOTH_SETTINGS,
                            Settings.ACTION_SETTINGS
                        )
                    },
                    onShareText = { text ->
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "AudioScope 音質診断レポート")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        startActivity(Intent.createChooser(sendIntent, "音質情報を共有"))
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingAudioIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        refreshPermissionFlags()
        viewModel.onAppForegroundChanged(true)
    }

    override fun onStop() {
        viewModel.onAppForegroundChanged(false)
        super.onStop()
    }

    private fun handleIncomingAudioIntent(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_VIEW) {
            val uri = incoming.data ?: return
            viewModel.importSafSingleFile(uri, playImmediately = true)
        }
    }

    private fun refreshPermissionFlags() {
        val audioPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val hasAudio = ContextCompat.checkSelfPermission(this, audioPerm) == PackageManager.PERMISSION_GRANTED
        val hasBt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        viewModel.updatePermissions(hasAudio, hasBt)
    }

    private fun openSystemScreenSafely(primaryAction: String, fallbackAction: String) {
        val opened = runCatching {
            startActivity(Intent(primaryAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (!opened) {
            runCatching {
                startActivity(Intent(fallbackAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
