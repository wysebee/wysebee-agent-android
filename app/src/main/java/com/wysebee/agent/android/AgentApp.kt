package com.wysebee.agent.android

import android.app.Application
import android.os.Build
import android.provider.Settings
import com.wysebee.agent.android.core.Agent
import com.wysebee.agent.android.core.LinkClient
import com.wysebee.agent.android.core.StateStore
import com.wysebee.agent.android.device.AgentAccessibilityService
import com.wysebee.agent.android.device.KeystoreSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Holds the one agent and runs its loop for as long as the process lives. The process lives
 * while the window is open, and — the point — while the accessibility service is on: the system
 * keeps a bound accessibility service running, so no foreground service is needed.
 */
class AgentApp : Application() {
    lateinit var agent: Agent
        private set

    private val _serviceOn = MutableStateFlow(false)
    val serviceOn: StateFlow<Boolean> = _serviceOn.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        val dir = File(filesDir, "agent")
        agent = Agent(
            store = StateStore(File(dir, "state.json")),
            secrets = KeystoreSecrets(File(dir, "secret.bin")),
            link = LinkClient(LinkClient.client()),
            device = { AgentAccessibilityService.current },
            own = packageName,
            defaultCloud = BuildConfig.DEFAULT_CLOUD_URL,
            deviceName = deviceName(),
            version = BuildConfig.VERSION_NAME,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        agent.start()
        serviceChanged()
    }

    fun serviceChanged() {
        _serviceOn.value = AgentAccessibilityService.current != null
    }

    private fun deviceName(): String =
        (Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}").trim().take(32)
}
