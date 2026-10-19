package haruka.sync.debug

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.os.Bundle
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import haruka.di.harukaGraph
import haruka.sync.auth.DriveAuthResult
import haruka.sync.auth.GoogleDriveAuth
import haruka.sync.drive.DriveRemoteStore
import haruka.sync.remote.RemoteLayout
import haruka.sync.ui.SyncInterval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * Debug-only Drive CAS self-test (p3-remote/cas-check). Registered only in the debug manifest overlay
 * (app/src/haruka/manifest-debug), exported so it can be started from a shell:
 *
 * ```
 * adb shell am start -n app.haruka.dev/haruka.sync.debug.DriveCasSelfTestActivity
 * adb logcat -s HarukaCasCheck
 * ```
 *
 * It authorizes `drive.appdata` (showing Google's account picker / consent when needed), then runs
 * [DriveCasSelfTest] in the namespace `<applicationId>.cascheck`, apart from the dev sync remote, because the
 * check deletes every file in its namespace.
 *
 * With the `haruka_sync` extra it instead drives the real sync scheduler and closes (`adb logcat -s HarukaSync`):
 *
 * ```
 * adb shell am start -n app.haruka.dev/haruka.sync.debug.DriveCasSelfTestActivity --es haruka_sync now
 * adb shell am start -n app.haruka.dev/haruka.sync.debug.DriveCasSelfTestActivity --es haruka_sync enable \
 *     --ei interval 1 --ez wifi_only true --ez charging_only true
 * adb shell am start -n app.haruka.dev/haruka.sync.debug.DriveCasSelfTestActivity --es haruka_sync disable
 * ```
 */
class DriveCasSelfTestActivity : BaseActivity() {

    private val viewModel: DriveCasSelfTestViewModel by viewModels()

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onConsentResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_SYNC)?.let { action ->
            runSyncAction(action)
            finish()
            return
        }
        setComposeContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.safeDrawingPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Drive CAS self-test", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = viewModel::start, enabled = !viewModel.running) {
                        Text(if (viewModel.running) "Running…" else "Run")
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(viewModel.lines) { line ->
                            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.consentRequests.receiveAsFlow().collect {
                    consent.launch(IntentSenderRequest.Builder(it).build())
                }
            }
        }
        if (savedInstanceState == null) viewModel.start()
    }

    private fun runSyncAction(action: String) {
        val manager = harukaGraph.syncManager
        when (action) {
            "now" -> manager.syncNow()
            "enable" -> {
                manager.setWifiOnly(intent.getBooleanExtra("wifi_only", false))
                manager.setChargingOnly(intent.getBooleanExtra("charging_only", false))
                val hours = intent.getIntExtra("interval", SyncInterval.DEFAULT.hours)
                manager.setInterval(SyncInterval.entries.firstOrNull { it.hours == hours } ?: SyncInterval.DEFAULT)
                manager.setEnabled(true)
            }
            "disable" -> manager.setEnabled(false)
            else -> {
                Log.w(SYNC_TAG, "debug action unknown: $action")
                return
            }
        }
        Log.i(SYNC_TAG, "debug action=$action enqueued")
    }

    private companion object {
        const val EXTRA_SYNC = "haruka_sync"
        const val SYNC_TAG = "HarukaSync"
    }
}

class DriveCasSelfTestViewModel(application: Application) : AndroidViewModel(application) {

    private val auth = GoogleDriveAuth(application)
    private val namespace = "${application.packageName}.cascheck"

    // No cache and no logging interceptor (requests carry the bearer token); the race needs all its PUTs in flight.
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = DriveCasSelfTest.RACERS })
        .build()

    val lines = mutableStateListOf<String>()
    var running by mutableStateOf(false)
        private set

    /** Consent flows for the activity to launch; buffered so a request made while it is stopped is not lost. */
    val consentRequests = Channel<PendingIntent>(Channel.BUFFERED)

    fun start() {
        if (running) return
        running = true
        lines.clear()
        viewModelScope.launch {
            log("authorizing ${GoogleDriveAuth.DRIVE_APPDATA}")
            handle(auth.authorize())
        }
    }

    fun onConsentResult(result: ActivityResult) {
        viewModelScope.launch {
            if (result.resultCode != Activity.RESULT_OK) {
                log("FAIL authorization: consent flow ended with result code ${result.resultCode}")
                running = false
                return@launch
            }
            handle(auth.completeAuthorization(result.data))
        }
    }

    private suspend fun handle(result: DriveAuthResult) {
        when (result) {
            is DriveAuthResult.Authorized -> runChecks()
            is DriveAuthResult.NeedsUserAction -> {
                log("authorization needs the user: opening Google's account/consent screen")
                consentRequests.send(result.pendingIntent)
            }
            is DriveAuthResult.Failed -> {
                // ApiException status 10 (DEVELOPER_ERROR) means the APK's signing SHA-1 is not registered.
                log("FAIL authorization: ${result.cause}")
                running = false
            }
        }
    }

    private suspend fun runChecks() {
        log("authorized; namespace $namespace")
        try {
            val passed = DriveCasSelfTest(DriveRemoteStore(client, auth, RemoteLayout(namespace)), ::log).run()
            log("CAS-CHECK RESULT: ${if (passed) "PASS" else "FAIL"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("CAS-CHECK RESULT: ERROR $e")
        } finally {
            running = false
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        lines += line
    }

    private companion object {
        const val TAG = "HarukaCasCheck"
    }
}
