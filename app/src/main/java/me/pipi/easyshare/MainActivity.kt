package me.pipi.easyshare

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.pipi.easyshare.ui.main.MainScreen
import me.pipi.easyshare.ui.main.MainViewModel
import me.pipi.easyshare.ui.theme.EasyShareTheme
import me.pipi.easyshare.utils.missingTransferPermissions
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { granted -> !granted }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.permission_not_granted),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()
        enableEdgeToEdge()

        setContent {
            EasyShareTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val chooseReceivePath = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    try {
                        contentResolver.takePersistableUriPermission(uri, grantFlags)
                    } catch (error: SecurityException) {
                        Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_LONG).show()
                        return@rememberLauncherForActivityResult
                    }
                    state.receivePath
                        ?.let(android.net.Uri::parse)
                        ?.takeIf { it != uri }
                        ?.let { oldUri ->
                            runCatching {
                                contentResolver.releasePersistableUriPermission(oldUri, grantFlags)
                            }
                        }
                    viewModel.setReceivePath(uri)
                }

                MainScreen(
                    state = state,
                    onReceiverChanged = viewModel::setReceiverEnabled,
                    onDeviceNameChanged = viewModel::setDeviceName,
                    onBrandChanged = viewModel::setBrand,
                    onChooseReceivePath = { chooseReceivePath.launch(null) },
                    onEnhancedModeChanged = viewModel::setEnhancedMode,
                    onCaptureLogs = ::captureAndShareLogs,
                )
            }
        }
    }

    private fun requestMissingPermissions() {
        val permissions = missingTransferPermissions(includeNotifications = true)

        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun captureAndShareLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val logDir = File(cacheDir, "logs").apply { mkdirs() }
                val logFile = File(logDir, "logcat.txt")
                logFile.outputStream().use { output ->
                    val process = Runtime.getRuntime().exec("logcat -d")
                    try {
                        process.inputStream.copyTo(output)
                    } finally {
                        process.destroy()
                    }
                }
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${BuildConfig.APPLICATION_ID}.fileProvider",
                    logFile,
                )
                val shareIntent = Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .setType("text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(Dispatchers.Main) {
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.capture_logs)))
                }
            } catch (exception: Exception) {
                Log.e("LogcatCapture", "Failed to save logs", exception)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.log_capture_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}
