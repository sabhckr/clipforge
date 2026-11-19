package com.clipforge.app.engine

import android.os.Build
import com.clipforge.app.core.Json
import com.clipforge.app.core.Provenance
import com.clipforge.app.data.ClipRow
import com.clipforge.app.data.JobRow

/** Builds the provenance manifest with the same layout as the desktop engine. */
object Manifests {
    const val MEDIA3_VERSION = "1.5.1"

    fun engineDescription(): String = "Android Media3 $MEDIA3_VERSION (MediaCodec)"

    fun platformDescription(): String =
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); ${Build.MANUFACTURER} ${Build.MODEL}"

    fun build(
        job: JobRow,
        clips: List<ClipRow>,
        owner: Map<String, Any?>?,
        engine: String = engineDescription(),
        platform: String = platformDescription(),
        createdAt: Double = Provenance.now(),
    ): Map<String, Any?> {
        val media = Json.parseObject(job.mediaJson)
        val settings = Json.parseObject(job.settingsJson)
        val plan = job.planJson?.let { Json.parseObject(it) } ?: emptyMap()
        val manifest = linkedMapOf<String, Any?>(
            "manifest_version" to Provenance.MANIFEST_VERSION,
            "generator" to linkedMapOf(
                "name" to Provenance.APP_NAME,
                "version" to Provenance.APP_VERSION,
                "engine" to engine,
                "platform" to platform,
                "ai_used" to false,
            ),
            "project_id" to job.id,
            "project_name" to job.name,
            "created_at" to Provenance.iso(createdAt),
            "source" to linkedMapOf(
                "asset_id" to job.sourceAssetId,
                "sha256" to job.sourceSha256,
                "size_bytes" to job.sourceSize,
                "original_filename" to job.sourceFilename,
                "ingested_at" to Provenance.iso(job.createdAt),
                "media" to linkedMapOf<String, Any?>().apply {
                    for (k in listOf("container", "duration", "width", "height", "rotation", "fps",
                        "video_codec", "has_audio", "audio_codec")) {
                        put(k, media[k])
                    }
                },
            ),
            "processing" to linkedMapOf(
                "settings" to settings,
                "segmentation" to plan,
                "encoder_used" to job.encoderUsed,
            ),
            "outputs" to clips.map { c ->
                linkedMapOf(
                    "asset_id" to c.id,
                    "parent_asset_id" to job.sourceAssetId,
                    "relationship" to "derived_segment",
                    "index" to c.idx,
                    "path" to "clips/${c.filename}",
                    "sha256" to c.sha256,
                    "size_bytes" to c.sizeBytes,
                    "created_at" to Provenance.iso(c.createdAt),
                    "source_start" to c.start,
                    "source_end" to c.end,
                    "duration" to c.duration,
                    "width" to c.width,
                    "height" to c.height,
                    "has_audio" to c.hasAudio,
                    "boundary" to linkedMapOf("start" to c.startKind, "end" to c.endKind),
                )
            },
        )
        if (owner != null) manifest["owner"] = owner
        return manifest
    }
}
