package com.wysebee.agent.android.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wysebee.agent.android.AgentApp
import com.wysebee.agent.android.R
import com.wysebee.agent.android.core.Agent
import com.wysebee.agent.android.core.App
import com.wysebee.agent.android.core.CloudAddress
import com.wysebee.agent.android.core.Reconnect
import com.wysebee.agent.android.core.Scanned
import com.wysebee.agent.android.core.Sensitive
import com.wysebee.agent.android.device.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext

private val Purple = Color(0xFF7B5CD6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AgentApp
        // Play services downloads its QR code scanner on first use, so a first scan would fail
        // while it does: ask for it now, while the phone is not paired yet. Nothing if it is there.
        if (app.agent.state.link == null) {
            val scanner = GmsBarcodeScanning.getClient(this)
            runCatching { ModuleInstall.getClient(this).installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build()) }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Color(0xFF15121C), surface = Color(0xFF1F1A2A))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen(app, openAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
                }
            }
        }
    }
}

@Composable
private fun Screen(app: AgentApp, openAccessibility: () -> Unit) {
    val agent = app.agent
    val status by agent.status.collectAsStateWithLifecycle()
    val serviceOn by app.serviceOn.collectAsStateWithLifecycle()
    val s = status.state
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // This build's own name ("Wysebee Agent", "… Dev"): it is also the name to find in
        // Settings › Accessibility.
        val name = stringResource(R.string.app_name)
        Text(name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text("Lets one Wysebee agent see this phone's screen and tap, type, swipe and open apps, in the apps you allow here.", color = Color.LightGray, fontSize = 14.sp)

        status.message?.let { msg ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(onClick = agent::dismissMessage) { Text("OK") }
                }
            }
        }

        Section("Accessibility service") {
            if (serviceOn) {
                Text("On: the agent can see and touch the screen (in allowed apps only).", fontSize = 14.sp)
            } else {
                Text("Off: the agent can do nothing on this phone until you turn on $name in Settings › Accessibility.", fontSize = 14.sp)
                Button(onClick = openAccessibility) { Text("Open accessibility settings") }
            }
        }

        when {
            s.link != null -> Paired(agent, status.connected)
            s.pending != null -> Section("Pairing") {
                Text("In Wysebee, under Devices, type this code:", fontSize = 14.sp)
                Text(s.pending.code.chunked(4).joinToString("-"), fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("For a new agent named \"${s.pending.name}\". The code works for 10 minutes.", color = Color.LightGray, fontSize = 13.sp)
                OutlinedButton(onClick = agent::cancelPairing) { Text("Cancel") }
            }
            status.scanned != null -> Scanned(agent, status.scanned!!, status.busy, status.reconnect)
            else -> {
                Unpaired(agent, status.busy)
                Server(agent)
            }
        }

        if (s.link != null) Apps(app, agent)

        if (s.link != null || s.pending != null) Text("Wysebee Cloud: ${s.cloudUrl(agent.defaultCloud)}", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        content()
    }
}

/** Not paired: scan Wysebee's QR code (no name needed yet), or type a name and pair with a code. */
@Composable
private fun Unpaired(agent: Agent, busy: Boolean) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    Section("Pair with Wysebee") {
        Text("In Wysebee, open Your devices › Pair a device › Android phone, and scan the QR code it shows.", fontSize = 14.sp)
        Button(enabled = !busy, onClick = {
            scope.launch {
                when (val scanned = scanQr(context)) {
                    is Scan.Read -> agent.scan(scanned.text)
                    is Scan.Failed -> agent.say(scanned.why)
                    Scan.Cancelled -> {}
                }
            }
        }) { Text("Scan QR code") }
        Text("Or pair with a code: type a name for a new agent, tap Pair with a code, and type the code this phone shows in Wysebee.", color = Color.LightGray, fontSize = 13.sp)
        OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Agent name") })
        OutlinedButton(enabled = !busy && name.isNotBlank(), onClick = { scope.launch { agent.startPairing(name) } }) {
            Text(if (busy) "Pairing…" else "Pair with a code")
        }
    }
}

/** A QR code scanned: the server it pairs with, said plainly, and the agent's name to type. */
@Composable
private fun Scanned(agent: Agent, scanned: Scanned, busy: Boolean, reconnect: Reconnect?) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    Section("Pair with ${CloudAddress.host(scanned.cloud)}") {
        Text("Server: ${scanned.cloud}", fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        // Whose QR code it is (2026-11-22), for the person to check it is their own account.
        val account = scanned.account
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF2A2338), RoundedCornerShape(8.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Wysebee account", color = Color.LightGray, fontSize = 12.sp)
            when {
                account == null -> Text("This server did not say whose QR code this is.", color = Color(0xFFE0A050), fontSize = 14.sp)
                account.name.isEmpty() && account.email.isEmpty() -> Text("Wysebee does not know this account's name yet.", fontSize = 14.sp)
                else -> {
                    if (account.name.isNotEmpty()) Text(account.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (account.email.isNotEmpty()) Text(account.email, fontSize = 14.sp)
                }
            }
        }
        Text("Pair only if this is your account: its agent will see and operate the apps you allow here.", color = Color.LightGray, fontSize = 13.sp)
        if (scanned.cloud != agent.defaultCloud) {
            Text(
                "This is not Wysebee's own server. Pair only with a server you trust.",
                color = Color(0xFFE0A050), fontSize = 13.sp,
            )
        }
        Text("Type a name for the agent that will use this phone.", fontSize = 14.sp)
        OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Agent name") })
        if (reconnect != null) {
            // The name is taken by an agent of this kind with no phone now.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !busy, onClick = { scope.launch { agent.reconnect() } }) { Text("Reconnect to ${reconnect.agentLabel}") }
                OutlinedButton(enabled = !busy, onClick = agent::declineReconnect) { Text("Use another name") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !busy && name.isNotBlank(), onClick = { scope.launch { agent.pairScanned(name) } }) {
                Text(if (busy) "Pairing…" else "Pair")
            }
            OutlinedButton(enabled = !busy, onClick = agent::cancelScan) { Text("Cancel") }
        }
    }
}

/** The server this phone pairs with: Wysebee's own unless the person types another. */
@Composable
private fun Server(agent: Agent) {
    val s by agent.status.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    // From the state collected here, so the line follows every change.
    val url = s.state.cloudUrl(agent.defaultCloud)
    Section("Server") {
        Text(url, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        if (s.state.server != null) Text("Not Wysebee's own server (${agent.defaultCloud}).", color = Color(0xFFE0A050), fontSize = 13.sp)
        Text("Scanning a QR code pairs with the server that shows it.", color = Color.LightGray, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { editing = true }) { Text("Change server") }
            if (s.state.server != null) TextButton(onClick = agent::resetServer) { Text("Use Wysebee's") }
        }
    }
    if (editing) {
        var typed by remember { mutableStateOf(url) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The Wysebee Cloud this phone pairs with, an https address.", fontSize = 14.sp)
                    OutlinedTextField(typed, { typed = it.trim(); error = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Address") }, isError = error != null)
                    error?.let { Text(it, color = Color(0xFFE0A050), fontSize = 13.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { error = agent.setServer(typed); if (error == null) editing = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

private sealed interface Scan {
    data class Read(val text: String?) : Scan
    data class Failed(val why: String) : Scan
    data object Cancelled : Scan
}

/** Google Play services' QR code scanner: its own camera screen, QR codes only. */
private suspend fun scanQr(context: Context): Scan = suspendCancellableCoroutine { cont ->
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { if (cont.isActive) cont.resume(Scan.Read(it.rawValue)) }
        .addOnCanceledListener { if (cont.isActive) cont.resume(Scan.Cancelled) }
        .addOnFailureListener {
            if (!cont.isActive) return@addOnFailureListener
            cont.resume(
                when ((it as? MlKitException)?.errorCode) {
                    MlKitException.CODE_SCANNER_CANCELLED -> Scan.Cancelled
                    // Play services fetches the scanner on first use (asked for when the app opened).
                    MlKitException.UNAVAILABLE, MlKitException.CODE_SCANNER_UNAVAILABLE ->
                        Scan.Failed("Google Play services is still downloading its QR code scanner. Try again in a minute, or pair with a code.")
                    MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD ->
                        Scan.Failed("Google Play services is too old for the QR code scanner: update it from the Play Store, or tap Pair with a code.")
                    else -> Scan.Failed("The QR code scanner could not open (${it.message ?: "Google Play services"}). Tap Pair with a code instead.")
                },
            )
        }
}

@Composable
private fun Paired(agent: Agent, connected: Boolean) {
    val scope = rememberCoroutineScope()
    val s by agent.status.collectAsStateWithLifecycle()
    val link = s.state.link ?: return
    var confirm by remember { mutableStateOf(false) }
    Section("Paired") {
        Text("@${link.agentName} — ${link.agentLabel}", fontSize = 15.sp)
        Text(if (connected) "Connected to Wysebee" else "Not connected — trying again", color = if (connected) Color(0xFF7BD88F) else Color(0xFFE0A050), fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Let Wysebee operate this phone", Modifier.weight(1f), fontSize = 14.sp)
            Switch(checked = !s.state.paused, onCheckedChange = { agent.setPaused(!it) })
        }
        OutlinedButton(onClick = { confirm = true }) { Text("Unpair") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Unpair this phone?") },
        text = { Text("@${link.agentName} stays in Wysebee, without a phone. The apps you allowed are forgotten.") },
        confirmButton = { TextButton(onClick = { confirm = false; scope.launch { agent.unpair() } }) { Text("Unpair") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Apps(app: AgentApp, agent: Agent) {
    val s by agent.status.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    Section("Apps the agent may operate") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("System UI", fontSize = 14.sp)
                Text(
                    "The notification shade and quick settings. The agent can then read every app's notifications — codes your bank texts you too — and switch Wi-Fi, Bluetooth or airplane mode. Never the lock screen.",
                    color = Color.LightGray, fontSize = 12.sp,
                )
            }
            Switch(checked = s.state.systemUi, onCheckedChange = agent::setSystemUi, modifier = Modifier.padding(start = 12.dp))
        }
        if (s.state.allowed.isEmpty()) Text("None yet. The agent can open, read and touch only the apps listed here.", fontSize = 14.sp, color = Color.LightGray)
        s.state.allowed.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, fontSize = 14.sp)
                    Text(a.pkg, fontSize = 12.sp, color = Color.LightGray)
                    if (Sensitive.isSensitive(a.pkg, a.label)) Text(NO_SCREENSHOTS, fontSize = 12.sp, color = Color(0xFFE0A050))
                }
                TextButton(onClick = { agent.disallow(a.pkg) }) { Text("Remove") }
            }
        }
        Button(onClick = { picking = true }) { Text("Add apps…") }
    }
    if (picking) AppPicker(
        app,
        allowed = s.state.allowed.map { it.pkg }.toSet(),
        onAdd = { agent.allow(it); picking = false },
        onClose = { picking = false },
    )
}

private const val NO_SCREENSHOTS = "Banking, payment or password app: never screenshotted"

/** Every app that can be opened, with a checkbox each: the person ticks several, then adds them. */
@Composable
private fun AppPicker(app: AgentApp, allowed: Set<String>, onAdd: (List<App>) -> Unit, onClose: () -> Unit) {
    var apps by remember { mutableStateOf<List<App>?>(null) }
    var query by remember { mutableStateOf("") }
    var ticked by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AgentAccessibilityService.launchable(app.packageManager).filter { it.pkg != app.packageName } }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Allow apps") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search") })
                Spacer(Modifier.height(8.dp))
                val shown = apps.orEmpty().filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.pkg }) { a ->
                        val already = a.pkg in allowed
                        val on = already || a.pkg in ticked
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = on, enabled = !already, role = Role.Checkbox) {
                                ticked = if (it) ticked + a.pkg else ticked - a.pkg
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The row toggles it: the box itself only shows the state.
                            Checkbox(checked = on, onCheckedChange = null, enabled = !already, modifier = Modifier.padding(end = 12.dp))
                            Column {
                                Text(a.label, fontSize = 15.sp)
                                Text(if (already) "Already allowed" else a.pkg, fontSize = 12.sp, color = Color.LightGray)
                                if (Sensitive.isSensitive(a.pkg, a.label)) Text(NO_SCREENSHOTS, fontSize = 12.sp, color = Color(0xFFE0A050))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ticked.isNotEmpty(), onClick = { onAdd(apps.orEmpty().filter { it.pkg in ticked }) }) {
                Text(if (ticked.isEmpty()) "Add" else "Add ${ticked.size}")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
