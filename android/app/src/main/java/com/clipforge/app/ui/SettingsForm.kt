package com.clipforge.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipforge.app.core.ProcessingSettings
import java.util.Locale
import kotlin.math.roundToInt

/** Every processing option, with the same ranges as the desktop app. */
@Composable
fun SettingsForm(s: ProcessingSettings, onChange: (ProcessingSettings) -> Unit) {
    Column {
        Label("Segmentation")
        ChoiceRow(ProcessingSettings.METHODS, s.segmentationMethod,
            { if (it == "hybrid") "Hybrid (scene-snapped)" else "Fixed interval" }) {
            onChange(s.copy(segmentationMethod = it))
        }

        SliderField("Target clip length", s.targetDuration, 5.0, 180.0, 1.0, "s", tag = "set_target") { v ->
            val min = minOf(s.minDuration, v)
            onChange(s.copy(targetDuration = v, minDuration = min, overlap = minOf(s.overlap, (min - 1).coerceAtLeast(0.0))))
        }
        SliderField("Minimum clip length", s.minDuration, 3.0, s.targetDuration, 1.0, "s") { v ->
            onChange(s.copy(minDuration = v, overlap = minOf(s.overlap, (v - 1).coerceAtLeast(0.0))))
        }
        if (s.segmentationMethod == "hybrid") {
            SliderField("Scene search window (±)", s.sceneWindow, 0.0, 30.0, 1.0, "s") { onChange(s.copy(sceneWindow = it)) }
            SliderField("Scene threshold", s.sceneThreshold, 0.05, 0.95, 0.05, "") { onChange(s.copy(sceneThreshold = it)) }
            Hint("Lower finds more cuts. 0.30 suits most edited footage.")
        }
        SliderField("Final remainder minimum", s.remainderMin, 1.0, minOf(60.0, s.targetDuration), 1.0, "s") {
            onChange(s.copy(remainderMin = it))
        }
        Label("Short final remainder")
        ChoiceRow(ProcessingSettings.REMAINDER, s.shortRemainder,
            { if (it == "drop") "Drop it" else "Merge into previous" }) { onChange(s.copy(shortRemainder = it)) }
        SliderField("Overlap between clips", s.overlap, 0.0, minOf(10.0, (s.minDuration - 1).coerceAtLeast(0.0)), 0.5, "s") {
            onChange(s.copy(overlap = it))
        }

        Spacer(Modifier.height(8.dp))
        Label("Aspect ratio")
        ChoiceRow(ProcessingSettings.ASPECTS, s.aspectRatio, {
            when (it) { "9:16" -> "9:16 vertical"; "1:1" -> "1:1 square"; else -> "16:9 wide" }
        }) { onChange(s.copy(aspectRatio = it)) }
        Label("Resolution")
        ChoiceRow(ProcessingSettings.RESOLUTIONS, s.resolution, { it }) { onChange(s.copy(resolution = it)) }
        Label("Fit")
        ChoiceRow(ProcessingSettings.FITS, s.fitMode, { if (it == "crop") "Crop to fill" else "Fit with bars" }) {
            onChange(s.copy(fitMode = it))
        }
        if (s.fitMode == "crop") {
            Label("Crop position")
            ChoiceRow(ProcessingSettings.CROPS, s.cropPosition, { it.replaceFirstChar { c -> c.uppercase() } }) {
                onChange(s.copy(cropPosition = it))
            }
            if (s.cropPosition == "custom") {
                SliderField("Custom offset", s.cropOffset, 0.0, 1.0, 0.05, "") { onChange(s.copy(cropOffset = it)) }
            }
        }
        Label("Quality")
        ChoiceRow(ProcessingSettings.QUALITIES, s.quality, { it.replaceFirstChar { c -> c.uppercase() } }) {
            onChange(s.copy(quality = it))
        }
        val (w, h) = s.outputSize
        Hint("Output: ${w}×$h H.264 + AAC MP4, about ${s.videoBitrate / 1_000_000.0} Mbit/s video.")
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Thumbnails", modifier = Modifier.weight(1f))
            Switch(checked = s.thumbnails, onCheckedChange = { onChange(s.copy(thumbnails = it)) })
        }
        val errors = s.validate()
        for (e in errors) Text(e, color = Danger, fontSize = 13.sp)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun SliderField(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    unit: String,
    tag: String? = null,
    onChange: (Double) -> Unit,
) {
    val hi = maxOf(max, min)
    val v = value.coerceIn(min, hi)
    val text = if (step >= 1.0) "${v.roundToInt()}$unit" else String.format(Locale.US, "%.2f%s", v, unit)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(text, fontSize = 13.sp)
    }
    val steps = if (hi > min) (((hi - min) / step).roundToInt() - 1).coerceIn(0, 400) else 0
    Slider(
        value = v.toFloat(),
        onValueChange = { f ->
            val snapped = (Math.round((f - min) / step) * step + min).coerceIn(min, hi)
            onChange(Math.round(snapped * 1000.0) / 1000.0)
        },
        valueRange = min.toFloat()..(if (hi > min) hi else min + 1.0).toFloat(),
        steps = steps,
        enabled = hi > min,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    )
}
