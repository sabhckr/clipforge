package com.clipforge.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class JobRow(
    val id: String,
    val name: String,
    val status: String,          // queued | running | completed | failed | cancelled
    val stage: String,
    val progress: Double,
    val error: String?,
    val errorDetail: String?,
    val settingsJson: String,
    val sourceRelpath: String,
    val sourceFilename: String,
    val sourceSize: Long,
    val sourceSha256: String,
    val sourceAssetId: String,
    val mediaJson: String,
    val planJson: String?,
    val encoderUsed: String?,
    val clipCount: Int,
    val attempts: Int,
    val createdAt: Double,
    val startedAt: Double?,
    val finishedAt: Double?,
) {
    val isActive: Boolean get() = status == "queued" || status == "running"
}

data class ClipRow(
    val id: String,
    val jobId: String,
    val idx: Int,
    val filename: String,
    val relpath: String,
    val thumbRelpath: String?,
    val start: Double,
    val end: Double,
    val duration: Double,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val sha256: String,
    val hasAudio: Boolean,
    val startKind: String,
    val endKind: String,
    val createdAt: Double,
)

data class ExportRow(
    val id: String,
    val jobId: String,
    val jobName: String,
    val clipIdsJson: String,
    val filename: String,
    val relpath: String,
    val sizeBytes: Long,
    val createdAt: Double,
    val fileDeleted: Boolean,
    val verificationOk: Boolean,
)

/** SQLite storage (WAL). All methods are thread-safe through SQLiteDatabase's own locking. */
class Database(context: Context, name: String? = "clipforge.db") :
    SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        if (databaseName != null) db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE jobs (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, status TEXT NOT NULL, stage TEXT NOT NULL,
                progress REAL NOT NULL DEFAULT 0, error TEXT, error_detail TEXT,
                settings_json TEXT NOT NULL, source_relpath TEXT NOT NULL, source_filename TEXT NOT NULL,
                source_size INTEGER NOT NULL, source_sha256 TEXT NOT NULL, source_asset_id TEXT NOT NULL,
                media_json TEXT NOT NULL, plan_json TEXT, encoder_used TEXT,
                clip_count INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0,
                created_at REAL NOT NULL, started_at REAL, finished_at REAL)""",
        )
        db.execSQL("CREATE INDEX idx_jobs_status ON jobs(status, created_at)")
        db.execSQL(
            """CREATE TABLE clips (
                id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
                idx INTEGER NOT NULL, filename TEXT NOT NULL, relpath TEXT NOT NULL, thumb_relpath TEXT,
                start REAL NOT NULL, "end" REAL NOT NULL, duration REAL NOT NULL,
                width INTEGER NOT NULL, height INTEGER NOT NULL, size_bytes INTEGER NOT NULL,
                sha256 TEXT NOT NULL, has_audio INTEGER NOT NULL, start_kind TEXT NOT NULL,
                end_kind TEXT NOT NULL, created_at REAL NOT NULL)""",
        )
        db.execSQL("CREATE INDEX idx_clips_job ON clips(job_id, idx)")
        db.execSQL(
            """CREATE TABLE exports (
                id TEXT PRIMARY KEY, job_id TEXT NOT NULL, job_name TEXT NOT NULL,
                clip_ids_json TEXT NOT NULL, filename TEXT NOT NULL, relpath TEXT NOT NULL,
                size_bytes INTEGER NOT NULL, created_at REAL NOT NULL,
                file_deleted INTEGER NOT NULL DEFAULT 0, verification_ok INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema; future migrations go here.
    }

    // ------------------------------------------------------------------- jobs
    fun insertJob(j: JobRow) {
        writableDatabase.insertOrThrow("jobs", null, ContentValues().apply {
            put("id", j.id); put("name", j.name); put("status", j.status); put("stage", j.stage)
            put("progress", j.progress); put("error", j.error); put("error_detail", j.errorDetail)
            put("settings_json", j.settingsJson); put("source_relpath", j.sourceRelpath)
            put("source_filename", j.sourceFilename); put("source_size", j.sourceSize)
            put("source_sha256", j.sourceSha256); put("source_asset_id", j.sourceAssetId)
            put("media_json", j.mediaJson); put("plan_json", j.planJson); put("encoder_used", j.encoderUsed)
            put("clip_count", j.clipCount); put("attempts", j.attempts); put("created_at", j.createdAt)
            put("started_at", j.startedAt); put("finished_at", j.finishedAt)
        })
    }

    /** Updates the given columns. Values may be String, Int, Long, Double or null. */
    fun updateJob(id: String, vararg fields: Pair<String, Any?>) {
        if (fields.isEmpty()) return
        val cv = ContentValues()
        for ((k, v) in fields) {
            require(k in JOB_COLUMNS) { "Unknown job column $k" }
            when (v) {
                null -> cv.putNull(k)
                is String -> cv.put(k, v)
                is Int -> cv.put(k, v)
                is Long -> cv.put(k, v)
                is Double -> cv.put(k, v)
                is Boolean -> cv.put(k, if (v) 1 else 0)
                else -> throw IllegalArgumentException("Unsupported value for $k")
            }
        }
        writableDatabase.update("jobs", cv, "id = ?", arrayOf(id))
    }

    fun getJob(id: String): JobRow? =
        readableDatabase.query("jobs", null, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) c.toJob() else null
        }

    fun listJobs(status: String? = null, limit: Int = 500): List<JobRow> =
        readableDatabase.query(
            "jobs", null, status?.let { "status = ?" }, status?.let { arrayOf(it) },
            null, null, "created_at DESC", limit.toString(),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toJob()) } }

    fun nextQueued(): JobRow? =
        readableDatabase.query("jobs", null, "status = 'queued'", null, null, null, "created_at ASC", "1")
            .use { c -> if (c.moveToFirst()) c.toJob() else null }

    fun deleteJob(id: String) {
        writableDatabase.delete("jobs", "id = ?", arrayOf(id))
    }

    // ------------------------------------------------------------------ clips
    fun insertClip(c: ClipRow) {
        writableDatabase.insertOrThrow("clips", null, ContentValues().apply {
            put("id", c.id); put("job_id", c.jobId); put("idx", c.idx); put("filename", c.filename)
            put("relpath", c.relpath); put("thumb_relpath", c.thumbRelpath); put("start", c.start)
            put("\"end\"", c.end); put("duration", c.duration); put("width", c.width); put("height", c.height)
            put("size_bytes", c.sizeBytes); put("sha256", c.sha256); put("has_audio", if (c.hasAudio) 1 else 0)
            put("start_kind", c.startKind); put("end_kind", c.endKind); put("created_at", c.createdAt)
        })
    }

    fun listClips(jobId: String): List<ClipRow> =
        readableDatabase.query("clips", null, "job_id = ?", arrayOf(jobId), null, null, "idx ASC")
            .use { c -> buildList { while (c.moveToNext()) add(c.toClip()) } }

    fun getClip(id: String): ClipRow? =
        readableDatabase.query("clips", null, "id = ?", arrayOf(id), null, null, null)
            .use { c -> if (c.moveToFirst()) c.toClip() else null }

    fun deleteClips(jobId: String) {
        writableDatabase.delete("clips", "job_id = ?", arrayOf(jobId))
    }

    data class Totals(val videos: Int, val clips: Int, val clipBytes: Long, val sourceBytes: Long)

    fun totals(): Totals {
        val db = readableDatabase
        val (videos, sourceBytes) = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(source_size), 0) FROM jobs", null)
            .use { c -> c.moveToFirst(); c.getInt(0) to c.getLong(1) }
        val (clips, clipBytes) = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM clips", null)
            .use { c -> c.moveToFirst(); c.getInt(0) to c.getLong(1) }
        return Totals(videos, clips, clipBytes, sourceBytes)
    }

    // ---------------------------------------------------------------- exports
    fun insertExport(e: ExportRow) {
        writableDatabase.insertOrThrow("exports", null, ContentValues().apply {
            put("id", e.id); put("job_id", e.jobId); put("job_name", e.jobName)
            put("clip_ids_json", e.clipIdsJson); put("filename", e.filename); put("relpath", e.relpath)
            put("size_bytes", e.sizeBytes); put("created_at", e.createdAt)
            put("file_deleted", if (e.fileDeleted) 1 else 0); put("verification_ok", if (e.verificationOk) 1 else 0)
        })
    }

    fun listExports(limit: Int = 200): List<ExportRow> =
        readableDatabase.query("exports", null, null, null, null, null, "created_at DESC", limit.toString())
            .use { c -> buildList { while (c.moveToNext()) add(c.toExport()) } }

    fun getExport(id: String): ExportRow? =
        readableDatabase.query("exports", null, "id = ?", arrayOf(id), null, null, null)
            .use { c -> if (c.moveToFirst()) c.toExport() else null }

    fun markExportDeleted(id: String) {
        writableDatabase.update("exports", ContentValues().apply { put("file_deleted", 1) }, "id = ?", arrayOf(id))
    }

    // --------------------------------------------------------------- settings
    fun getSetting(key: String): String? =
        readableDatabase.query("settings", arrayOf("value"), "key = ?", arrayOf(key), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun putSetting(key: String, value: String) {
        writableDatabase.insertWithOnConflict("settings", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------------------------------------------------------- mapping
    private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col))
    private fun Cursor.strOrNull(col: String): String? =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.dbl(col: String): Double = getDouble(getColumnIndexOrThrow(col))
    private fun Cursor.dblOrNull(col: String): Double? =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getDouble(it) }
    private fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))
    private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))

    private fun Cursor.toJob() = JobRow(
        id = str("id"), name = str("name"), status = str("status"), stage = str("stage"),
        progress = dbl("progress"), error = strOrNull("error"), errorDetail = strOrNull("error_detail"),
        settingsJson = str("settings_json"), sourceRelpath = str("source_relpath"),
        sourceFilename = str("source_filename"), sourceSize = long("source_size"),
        sourceSha256 = str("source_sha256"), sourceAssetId = str("source_asset_id"),
        mediaJson = str("media_json"), planJson = strOrNull("plan_json"), encoderUsed = strOrNull("encoder_used"),
        clipCount = int("clip_count"), attempts = int("attempts"), createdAt = dbl("created_at"),
        startedAt = dblOrNull("started_at"), finishedAt = dblOrNull("finished_at"),
    )

    private fun Cursor.toClip() = ClipRow(
        id = str("id"), jobId = str("job_id"), idx = int("idx"), filename = str("filename"),
        relpath = str("relpath"), thumbRelpath = strOrNull("thumb_relpath"), start = dbl("start"),
        end = dbl("end"), duration = dbl("duration"), width = int("width"), height = int("height"),
        sizeBytes = long("size_bytes"), sha256 = str("sha256"), hasAudio = int("has_audio") != 0,
        startKind = str("start_kind"), endKind = str("end_kind"), createdAt = dbl("created_at"),
    )

    private fun Cursor.toExport() = ExportRow(
        id = str("id"), jobId = str("job_id"), jobName = str("job_name"), clipIdsJson = str("clip_ids_json"),
        filename = str("filename"), relpath = str("relpath"), sizeBytes = long("size_bytes"),
        createdAt = dbl("created_at"), fileDeleted = int("file_deleted") != 0,
        verificationOk = int("verification_ok") != 0,
    )

    companion object {
        const val VERSION = 1
        private val JOB_COLUMNS = setOf(
            "name", "status", "stage", "progress", "error", "error_detail", "settings_json", "media_json",
            "plan_json", "encoder_used", "clip_count", "attempts", "started_at", "finished_at",
        )
    }
}
