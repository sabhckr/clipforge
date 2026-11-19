package com.clipforge.app.ui.screens

import android.media.MediaCodecList
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipforge.app.BuildConfig
import com.clipforge.app.ClipforgeApp
import com.clipforge.app.core.ProcessingSettings
import com.clipforge.app.engine.AppSettings
import com.clipforge.app.engine.Manifests
import com.clipforge.app.ui.KeyValue
import com.clipforge.app.ui.Muted
import com.clipforge.app.ui.SectionCard
import com.clipforge.app.ui.SettingsForm
import com.clipforge.app.ui.fmtBytes
import com.clipforge.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Names of H.264 encoders on this device, hardware first. */
fun h264Encoders(): List<String> {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", ignoreCase = true) } }
    val hw = list.filter { it.isHardwareAccelerated }
    val sw = list - hw.toSet()
    return hw.map { it.name + " (hardware)" } + sw.map { it.name + " (software)" }
}

@Composable
fun SettingsScreen(app: ClipforgeApp) {
    val engine = app.engine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var processing by remember { mutableStateOf<ProcessingSettings?>(null) }
    var owner by remember { mutableStateOf<AppSettings?>(null) }
    var encoders by remember { mutableStateOf<List<String>>(emptyList()) }
    var free by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            processing = engine.processingDefaults()
            owner = engine.appSettings()
            encoders = try { h264Encoders() } catch (e: Exception) { emptyList() }
            free = engine.freeBytes()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("screen_settings"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val p = processing
        if (p != null) {
            SectionCard(title = "Default processing settings") {
                Text("Used for new projects. You can still change them per project.", color = Muted, fontSize = 13.sp)
                SettingsForm(p) { processing = it }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = p.validate().isEmpty(), onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { engine.saveProcessingDefaults(p) }
                                toast(context, "Defaults saved")
                            } catch (e: Exception) {
                                toast(context, e.message ?: "Could not save")
                            }
                        }
                    }, modifier = Modifier.testTag("settings_save")) { Text("Save defaults") }
                    OutlinedButton(onClick = { processing = ProcessingSettings() }) { Text("Reset") }
                }
            }
        }

        val o = owner
        if (o != null) {
            SectionCard(title = "Owner details (optional)") {
                Text("Written into new manifests only if enabled. They are your own statement and are not " +
                    "independently verified.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(o.ownerName, { owner = o.copy(ownerName = it.take(200)) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(o.ownerContact, { owner = o.copy(ownerContact = it.take(200)) },
                    label = { Text("Contact (email or website)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Include in manifests", modifier = Modifier.weight(1f))
                    Switch(o.includeOwnerInManifest, { owner = o.copy(includeOwnerInManifest = it) })
                }
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { engine.saveAppSettings(o) }
                        toast(context, "Owner details saved")
                    }
                }) { Text("Save owner details") }
            }
        }

        SectionCard(title = "System") {
            KeyValue("App version", BuildConfig.VERSION_NAME)
            KeyValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            KeyValue("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            KeyValue("Engine", Manifests.engineDescription())
            KeyValue("Free space", fmtBytes(free))
            KeyValue("H.264 encoders", if (encoders.isEmpty()) "none found" else encoders.joinToString("\n"))
            KeyValue("Network", "not used (no internet permission)")
        }
    }
}
