@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clipforge.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.clipforge.app.engine.JobEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ theme
val Lime = Color(0xFFC6F432)
val Graphite = Color(0xFF121417)
val Panel = Color(0xFF1B1E23)
val PanelHigh = Color(0xFF252931)
val Muted = Color(0xFF9AA3AF)
val Danger = Color(0xFFFF6B6B)
val Ok = Color(0xFF4ADE80)
val Warn = Color(0xFFFBBF24)

private val Scheme = darkColorScheme(
    primary = Lime,
    onPrimary = Graphite,
    secondary = Color(0xFF8BD3FF),
    onSecondary = Graphite,
    background = Graphite,
    onBackground = Color(0xFFE8EAED),
    surface = Graphite,
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Panel,
    onSurfaceVariant = Muted,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = PanelHigh,
    surfaceContainerLow = Panel,
    outline = Color(0xFF3A3F48),
    error = Danger,
)

@Composable
fun ClipforgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

// ------------------------------------------------------------- formatting
fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

fun fmtDuration(sec: Double): String {
    val total = sec.coerceAtLeast(0.0)
    val m = (total / 60).toInt()
    val s = total - m * 60
    return if (m > 0) String.format(Locale.US, "%d:%04.1f", m, s) else String.format(Locale.US, "%.1fs", s)
}

fun fmtTime(sec: Double): String {
    val t = sec.coerceAtLeast(0.0)
    val h = (t / 3600).toInt()
    val m = ((t % 3600) / 60).toInt()
    val s = t % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%06.3f", h, m, s)
    else String.format(Locale.US, "%d:%06.3f", m, s)
}

fun fmtDate(ts: Double): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date((ts * 1000).toLong()))

// ----------------------------------------------------------------- state
/** Re-runs [query] off the main thread whenever the engine reports a change. */
@Composable
fun <T> rememberQuery(engine: JobEngine, initial: T, vararg keys: Any?, query: () -> T): State<T> {
    val version by engine.changes.collectAsState()
    return produceState(initial, version, *keys) {
        value = withContext(Dispatchers.IO) { query() }
    }
}

@Composable
fun rememberThumbnail(file: File?): State<ImageBitmap?> = produceState<ImageBitmap?>(null, file?.path) {
    value = if (file == null || !file.isFile) null else withContext(Dispatchers.IO) {
        try { BitmapFactory.decodeFile(file.path)?.asImageBitmap() } catch (e: Exception) { null }
    }
}

// ------------------------------------------------------------ components
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val color = when (status) {
        "completed" -> Ok
        "failed" -> Danger
        "running" -> Lime
        "cancelled" -> Muted
        else -> Warn
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(status, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun KeyValue(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(0.4f))
        Text(
            value, fontSize = 13.sp, modifier = Modifier.weight(0.6f),
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
fun Thumbnail(file: File?, modifier: Modifier = Modifier) {
    val bmp by rememberThumbnail(file)
    Box(modifier.background(PanelHigh, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Text("no preview", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (o in options) {
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

// --------------------------------------------------------------- sharing
fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

fun shareFile(context: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: ActivityNotFoundException) {
        toast(context, "No app can receive this file")
    }
}
