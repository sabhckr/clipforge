package com.clipforge.app.engine

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import com.clipforge.app.core.DeviceKeyStore
import com.clipforge.app.core.Json
import com.clipforge.app.core.ProcessingSettings
import com.clipforge.app.core.Provenance
import com.clipforge.app.core.Segmenter
import com.clipforge.app.core.r3
import com.clipforge.app.data.ClipRow
import com.clipforge.app.data.Database
import com.clipforge.app.data.JobRow
import com.clipforge.app.media.CancelledException
import com.clipforge.app.media.ClipRenderer
import com.clipforge.app.media.InvalidMediaException
import com.clipforge.app.media.MediaInfo
import com.clipforge.app.media.MediaProbe
import com.clipforge.app.media.RenderException
import com.clipforge.app.media.SceneDetector
import com.clipforge.app.media.Thumbnails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Owner details stored in app settings (optional, included in manifests only when enabled). */
data class AppSettings(
    val ownerName: String = "",
    val ownerContact: String = "",
    val includeOwnerInManifest: Boolean = false,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "owner_name" to ownerName, "owner_contact" to ownerContact,
        "include_owner_in_manifest" to includeOwnerInManifest,
    )

    companion object {
        fun fromJson(m: Map<String, Any?>?): AppSettings = AppSettings(
            ownerName = m?.get("owner_name") as? String ?: "",
            ownerContact = m?.get("owner_contact") as? String ?: "",
            includeOwnerInManifest = m?.get("include_owner_in_manifest") as? Boolean ?: false,
        )
    }
}

class ImportException(message: String) : Exception(message)

/**
 * Runs projects one at a time on a background thread: inspect → detect scenes → plan →
 * render each clip → hash → sign. State lives in SQLite so the UI and crash recovery see
 * exactly what happened.
 */
class JobEngine(
    private val context: Context,
    val db: Database,
    val keys: DeviceKeyStore,
    val dataDir: File,
) {
    val sourcesDir = File(dataDir, "sources")
    val jobsDir = File(dataDir, "jobs")
    val exportsDir = File(dataDir, "exports")

    private val _changes = MutableStateFlow(0L)
    /** Incremented whenever stored state changes; the UI re-reads on each new value. */
    val changes: StateFlow<Long> = _changes

    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val workerRunning = AtomicBoolean(false)
    /** True while the worker thread is processing the queue. */
    val isBusy: Boolean get() = workerRunning.get()

    @Volatile var currentJobId: String? = null
        private set

    /** Called by the engine to start/stop the foreground service around active work. */
    var serviceController: ServiceController? = null

    interface ServiceController {
        fun onWorkStarted()
        fun onProgress(job: JobRow)
        fun onWorkFinished()
    }

    init {
        sourcesDir.mkdirs(); jobsDir.mkdirs(); exportsDir.mkdirs()
    }

    fun notifyChanged() {
        _changes.value = _changes.value + 1
    }

    fun jobDir(id: String) = File(jobsDir, id)
    fun manifestFile(id: String) = File(jobDir(id), "manifest.json")
    fun resolve(relpath: String): File {
        val f = File(dataDir, relpath).canonicalFile
        require(f.path.startsWith(dataDir.canonicalPath + File.separator)) { "Path escapes data directory" }
        return f
    }

    fun freeBytes(): Long = try { StatFs(dataDir.path).availableBytes } catch (e: Exception) { 0L }

    // ------------------------------------------------------------ settings
    fun processingDefaults(): ProcessingSettings =
        db.getSetting("processing")?.let {
            try { ProcessingSettings.fromJson(Json.parseObject(it)) } catch (e: Exception) { null }
        } ?: ProcessingSettings()

    fun saveProcessingDefaults(s: ProcessingSettings) {
        val errors = s.validate()
        require(errors.isEmpty()) { errors.joinToString(" ") }
        db.putSetting("processing", Json.canonical(s.toJson()))
        notifyChanged()
    }

    fun appSettings(): AppSettings =
        AppSettings.fromJson(db.getSetting("app")?.let { try { Json.parseObject(it) } catch (e: Exception) { null } })

    fun saveAppSettings(s: AppSettings) {
        db.putSetting("app", Json.canonical(s.toJson()))
        notifyChanged()
    }

    // --------------------------------------------------------------- import
    /**
     * Copies a picked video into app storage while hashing it, validates it by content and
     * queues a project. Runs on the caller's (background) thread.
     */
    fun importVideo(
        uri: Uri,
        name: String,
        settings: ProcessingSettings,
        isCancelled: () -> Boolean = { false },
        onProgress: (Double) -> Unit = {},
    ): JobRow {
        val errors = settings.validate()
        if (errors.isNotEmpty()) throw ImportException(errors.joinToString(" "))
        val cr = context.contentResolver
        var displayName = "video"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) displayName = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size >= 0 && freeBytes() - size < MIN_FREE_BYTES) {
            throw ImportException("Not enough free storage to import this video (${size / MB} MB needed " +
                "plus ${MIN_FREE_BYTES / MB} MB reserve).")
        }
        val assetId = Provenance.newId()
        val ext = displayName.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
        val dst = File(sourcesDir, if (ext.isEmpty()) assetId else "$assetId.$ext")
        val (sha, written) = try {
            val input = cr.openInputStream(uri) ?: throw ImportException("Could not open the selected file")
            input.use { inp ->
                dst.outputStream().use { out ->
                    Provenance.sha256Stream(object : java.io.FilterInputStream(inp) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (isCancelled()) throw CancelledException()
                            val n = super.read(b, off, len)
                            if (n > 0) out.write(b, off, n)
                            return n
                        }
                    }) { total -> if (size > 0) onProgress((total.toDouble() / size).coerceAtMost(1.0)) }
                }
            }
        } catch (e: Exception) {
            dst.delete()
            if (e is CancelledException || e is ImportException) throw e
            throw ImportException("Could not read the selected file: ${e.message}")
        }
        val media: MediaInfo = try {
            MediaProbe.probe(dst)
        } catch (e: InvalidMediaException) {
            dst.delete()
            throw ImportException(e.message ?: "Invalid or unsupported video")
        } catch (e: Exception) {
            dst.delete()
            throw ImportException("Invalid or unsupported video: ${e.message}")
        }
        if (media.duration + 1e-6 < settings.remainderMin) {
            dst.delete()
            throw ImportException(String.format(java.util.Locale.US,
                "Video is %.2fs long, shorter than the %.0fs minimum clip length.", media.duration, settings.remainderMin))
        }
        val job = JobRow(
            id = Provenance.newId(),
            name = name.trim().ifEmpty { displayName.substringBeforeLast('.').ifEmpty { "Untitled" } }.take(120),
            status = "queued", stage = "queued", progress = 0.0, error = null, errorDetail = null,
            settingsJson = Json.canonical(settings.toJson()),
            sourceRelpath = dst.relativeTo(dataDir).path,
            sourceFilename = displayName, sourceSize = written, sourceSha256 = sha, sourceAssetId = assetId,
            mediaJson = Json.canonical(media.toJson()), planJson = null, encoderUsed = null,
            clipCount = 0, attempts = 0, createdAt = Provenance.now(), startedAt = null, finishedAt = null,
        )
        db.insertJob(job)
        notifyChanged()
        kick()
        return job
    }

    // -------------------------------------------------------------- control
    /** Marks interrupted jobs for another attempt (or failure after too many) at startup. */
    fun recover() {
        for (j in db.listJobs(status = "running")) {
            if (j.attempts < MAX_ATTEMPTS) {
                db.updateJob(j.id, "status" to "queued", "stage" to "queued", "progress" to 0.0,
                    "error" to "Interrupted (app was closed or stopped); requeued automatically.")
            } else {
                db.updateJob(j.id, "status" to "failed", "stage" to "failed",
                    "error" to "Interrupted $MAX_ATTEMPTS times; not retried again.", "finished_at" to Provenance.now())
            }
        }
        notifyChanged()
        if (db.nextQueued() != null) kick()
    }

    fun cancel(id: String) {
        val job = db.getJob(id) ?: return
        when (job.status) {
            "queued" -> {
                db.updateJob(id, "status" to "cancelled", "stage" to "cancelled", "finished_at" to Provenance.now())
                notifyChanged()
            }
            "running" -> cancelFlags[id]?.set(true)
        }
    }

    fun retry(id: String) {
        val job = db.getJob(id) ?: return
        if (job.status != "failed" && job.status != "cancelled") return
        db.deleteClips(id)
        jobDir(id).deleteRecursively()
        db.updateJob(id, "status" to "queued", "stage" to "queued", "progress" to 0.0, "error" to null,
            "error_detail" to null, "attempts" to 0, "clip_count" to 0, "plan_json" to null,
            "encoder_used" to null, "started_at" to null, "finished_at" to null)
        notifyChanged()
        kick()
    }

    /** Deletes a project and every file it produced. Running projects must be cancelled first. */
    fun delete(id: String): Boolean {
        val job = db.getJob(id) ?: return false
        if (job.status == "running") return false
        jobDir(id).deleteRecursively()
        try { resolve(job.sourceRelpath).delete() } catch (_: Exception) {}
        db.deleteJob(id)
        notifyChanged()
        return true
    }

    /** Starts the worker thread if it is not already running. */
    fun kick() {
        if (!workerRunning.compareAndSet(false, true)) return
        try { serviceController?.onWorkStarted() } catch (e: Exception) {
            Log.w(TAG, "Foreground service could not start; processing continues in the app", e)
        }
        Thread({
            try {
                while (true) {
                    val job = db.nextQueued() ?: break
                    runJob(job)
                }
            } finally {
                workerRunning.set(false)
                try { serviceController?.onWorkFinished() } catch (_: Exception) {}
                // A job queued between the last check and the flag reset must not be stranded.
                if (db.nextQueued() != null) kick()
            }
        }, "clipforge-worker").start()
    }

    private fun runJob(job: JobRow) {
        val flag = AtomicBoolean(false)
        cancelFlags[job.id] = flag
        currentJobId = job.id
        db.updateJob(job.id, "status" to "running", "stage" to "inspecting", "progress" to 1.0,
            "attempts" to job.attempts + 1, "started_at" to Provenance.now(), "error" to null, "error_detail" to null)
        notifyChanged()
        try {
            pipeline(job, flag)
        } catch (e: CancelledException) {
            jobDir(job.id).deleteRecursively()
            db.deleteClips(job.id)
            db.updateJob(job.id, "status" to "cancelled", "stage" to "cancelled", "finished_at" to Provenance.now(),
                "clip_count" to 0)
        } catch (e: Throwable) {
            Log.e(TAG, "job ${job.id} failed", e)
            val detail = (e as? RenderException)?.detail ?: e.stackTraceToString().take(4000)
            db.updateJob(job.id, "status" to "failed", "stage" to "failed", "finished_at" to Provenance.now(),
                "error" to (e.message ?: e.javaClass.simpleName), "error_detail" to detail)
        } finally {
            cancelFlags.remove(job.id)
            currentJobId = null
            notifyChanged()
        }
    }

    private fun checkDisk() {
        val free = freeBytes()
        if (free < MIN_FREE_BYTES) {
            throw IllegalStateException("Not enough free storage (${free / MB} MB free, ${MIN_FREE_BYTES / MB} MB required)")
        }
    }

    private fun pipeline(job: JobRow, cancel: AtomicBoolean) {
        val id = job.id
        val settings = ProcessingSettings.fromJson(Json.parseObject(job.settingsJson))
        val src = resolve(job.sourceRelpath)
        val outDir = jobDir(id)
        val clipsDir = File(outDir, "clips")
        val thumbsDir = File(outDir, "thumbnails")
        outDir.deleteRecursively()
        db.deleteClips(id)
        clipsDir.mkdirs(); thumbsDir.mkdirs()

        var lastWrite = 0L
        fun progress(stage: String, pct: Double, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastWrite > 400) {
                lastWrite = now
                db.updateJob(id, "stage" to stage, "progress" to r1(pct.coerceIn(0.0, 100.0)))
                notifyChanged()
                db.getJob(id)?.let { j -> try { serviceController?.onProgress(j) } catch (_: Exception) {} }
            }
        }
        fun checkCancel() { if (cancel.get()) throw CancelledException() }

        // A–B. validate / inspect
        progress("inspecting", 1.0, force = true)
        if (!src.isFile) throw IllegalStateException("Source video is missing from storage")
        val media = MediaProbe.probe(src)
        checkDisk()
        checkCancel()

        // C. scene detection (deterministic; falls back to fixed intervals)
        val notes = ArrayList<String>()
        var sceneTimes: List<Double> = emptyList()
        var methodUsed = settings.segmentationMethod
        if (settings.segmentationMethod == "hybrid") {
            progress("detecting_scenes", SCENES_LO, force = true)
            try {
                sceneTimes = SceneDetector.detect(src, settings.sceneThreshold, media.duration, { cancel.get() }) { f ->
                    progress("detecting_scenes", SCENES_LO + (SCENES_HI - SCENES_LO) * f)
                }
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "scene detection failed; using fixed intervals", e)
                notes.add("Scene detection failed (${e.message ?: e.javaClass.simpleName}); fell back to fixed intervals.")
                methodUsed = "fixed (scene detection failed)"
            }
        }
        checkCancel()

        // D–G. plan
        progress("planning", RENDER_LO, force = true)
        val plan = Segmenter.plan(media.duration, sceneTimes, settings.segmentParams(),
            if (methodUsed == "hybrid") "hybrid" else "fixed")
        notes.addAll(plan.notes)
        if (plan.segments.isEmpty()) throw IllegalStateException("Segmentation produced no clips")
        val planDoc = linkedMapOf(
            "method_requested" to settings.segmentationMethod,
            "method_used" to methodUsed,
            "scene_detector" to "luma-grid ${SceneDetector.GRID_W}x${SceneDetector.GRID_H}, FFmpeg-style scene score",
            "scene_threshold" to settings.sceneThreshold,
            "scene_boundaries_detected" to sceneTimes.map { r3(it) },
            "scene_boundaries_used" to plan.sceneBoundariesUsed,
            "dropped_tail_seconds" to plan.droppedTail,
            "notes" to notes,
            "segments" to plan.segments.map { it.toJson() },
        )
        db.updateJob(id, "plan_json" to Json.canonical(planDoc), "media_json" to Json.canonical(media.toJson()))

        // H. render each segment independently
        val renderer = ClipRenderer(context)
        val (outW, outH) = settings.outputSize
        val total = plan.segments.sumOf { it.duration }.takeIf { it > 0 } ?: 1.0
        var done = 0.0
        val encoders = sortedSetOf<String>()
        for (seg in plan.segments) {
            checkCancel()
            checkDisk()
            val assetId = Provenance.newId()
            val filename = String.format(java.util.Locale.US, "clip_%04d.mp4", seg.index)
            val dst = File(clipsDir, filename)
            val base = done
            val result = renderer.render(src, dst, seg.start, seg.duration, settings, media, { cancel.get() }) { f ->
                val frac = (base + seg.duration * f) / total
                progress("rendering", RENDER_LO + (RENDER_HI - RENDER_LO) * frac)
            }
            result.videoEncoder?.let { encoders.add(it) }
            checkCancel()

            val out = MediaProbe.probe(dst)
            if (out.width != outW || out.height != outH) {
                throw RenderException("Rendered clip has unexpected size ${out.width}x${out.height}, expected ${outW}x$outH")
            }
            var thumbRel: String? = null
            if (settings.thumbnails) {
                val thumb = File(thumbsDir, String.format(java.util.Locale.US, "clip_%04d.jpg", seg.index))
                try {
                    Thumbnails.write(dst, thumb, minOf(1.0, out.duration / 4))
                    thumbRel = thumb.relativeTo(dataDir).path
                } catch (e: Exception) {
                    Log.w(TAG, "thumbnail failed", e)
                }
            }
            db.insertClip(ClipRow(
                id = assetId, jobId = id, idx = seg.index, filename = filename,
                relpath = dst.relativeTo(dataDir).path, thumbRelpath = thumbRel,
                start = seg.start, end = seg.end, duration = r3(out.duration),
                width = out.width, height = out.height, sizeBytes = dst.length(),
                sha256 = Provenance.sha256File(dst), hasAudio = out.hasAudio,
                startKind = seg.startKind, endKind = seg.endKind, createdAt = Provenance.now(),
            ))
            done += seg.duration
            progress("rendering", RENDER_LO + (RENDER_HI - RENDER_LO) * done / total, force = true)
        }

        // I–J. hashes + signed provenance manifest
        checkCancel()
        progress("signing", 97.0, force = true)
        db.updateJob(id, "encoder_used" to encoders.joinToString(", ").ifEmpty { "unknown" })
        writeManifest(db.getJob(id) ?: throw IllegalStateException("Job disappeared"))
        db.updateJob(id, "status" to "completed", "stage" to "completed", "progress" to 100.0,
            "clip_count" to plan.segments.size, "finished_at" to Provenance.now(), "error" to null, "error_detail" to null)
    }

    fun writeManifest(job: JobRow): Map<String, Any?> {
        val app = appSettings()
        val owner = if (app.includeOwnerInManifest && (app.ownerName.isNotBlank() || app.ownerContact.isNotBlank())) {
            linkedMapOf<String, Any?>("name" to app.ownerName, "contact" to app.ownerContact,
                "statement" to "Owner-declared information; not independently verified.")
        } else null
        val manifest = Manifests.build(job, db.listClips(job.id), owner)
        val signed = keys.withSigner { Provenance.signManifest(manifest, it) }
        val path = manifestFile(job.id)
        path.parentFile?.mkdirs()
        val tmp = File(path.parentFile, "manifest.json.tmp")
        tmp.writeText(Json.pretty(signed), Charsets.UTF_8)
        if (!tmp.renameTo(path)) throw IllegalStateException("Could not write manifest.json")
        return signed
    }

    /** Re-hashes a project's clips on disk and checks them against its signed manifest. */
    fun verifyOnDisk(job: JobRow, clipIds: Set<String>? = null): Map<String, Any?> {
        val mf = manifestFile(job.id)
        if (!mf.isFile) throw IllegalStateException("This project has no manifest yet")
        val doc = Json.parse(mf.readText(Charsets.UTF_8))
        val sig = Provenance.verifySignedManifest(doc, trusted = keys.publicKey())
        val files = ArrayList<Pair<String, String>>()
        val missing = ArrayList<Map<String, Any?>>()
        for (c in db.listClips(job.id)) {
            if (clipIds != null && c.id !in clipIds) continue
            val f = resolve(c.relpath)
            if (f.isFile) files.add("clips/${c.filename}" to Provenance.sha256File(f))
            else missing.add(linkedMapOf("name" to "clips/${c.filename}", "asset_id" to c.id))
        }
        val manifest = Json.obj(Json.obj(doc)?.get("manifest")) ?: emptyMap()
        return Provenance.buildReport(doc, sig, Provenance.matchFiles(manifest, files), missing)
    }

    companion object {
        private const val TAG = "ClipforgeEngine"
        const val MB = 1024L * 1024L
        const val MIN_FREE_BYTES = 500L * MB
        const val MAX_ATTEMPTS = 3
        const val SCENES_LO = 2.0
        const val SCENES_HI = 25.0
        const val RENDER_LO = 25.0
        const val RENDER_HI = 97.0

        private fun r1(x: Double) = Math.round(x * 10.0) / 10.0
    }
}
