package com.clipforge.app.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.clipforge.app.core.Json
import com.clipforge.app.core.Provenance
import com.clipforge.app.data.ExportRow
import com.clipforge.app.engine.JobEngine
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Builds export ZIPs with the same CLIPFORGE_EXPORT/ layout as the desktop app, saves files to
 * shared storage through MediaStore (no storage permission needed on Android 10+), and verifies
 * manifests and ZIPs produced by either platform.
 */
class Exporter(private val context: Context, private val engine: JobEngine) {

    data class Built(val row: ExportRow, val file: File)

    fun buildExport(jobId: String, clipIds: List<String>? = null): Built {
        val db = engine.db
        val job = db.getJob(jobId) ?: throw IllegalArgumentException("Project not found")
        val all = db.listClips(jobId)
        val clips = if (clipIds.isNullOrEmpty()) all else {
            val wanted = clipIds.toSet()
            val picked = all.filter { it.id in wanted }
            val unknown = wanted.size - picked.size
            if (unknown > 0) throw IllegalArgumentException("$unknown selected clip(s) do not belong to this project")
            picked
        }
        if (clips.isEmpty()) throw IllegalArgumentException("No clips to export")
        val manifestFile = engine.manifestFile(jobId)
        if (!manifestFile.isFile) throw IllegalStateException("This project has no manifest yet")

        val report = engine.verifyOnDisk(job, clips.map { it.id }.toSet())
        val media = Json.parseObject(job.mediaJson)
        val selected = !clipIds.isNullOrEmpty()
        val project = linkedMapOf(
            "generator" to linkedMapOf("name" to Provenance.APP_NAME, "version" to Provenance.APP_VERSION),
            "project_id" to job.id,
            "project_name" to job.name,
            "exported_at" to Provenance.iso(Provenance.now()),
            "source" to linkedMapOf(
                "filename" to job.sourceFilename, "asset_id" to job.sourceAssetId, "sha256" to job.sourceSha256,
                "duration" to media["duration"], "width" to media["width"], "height" to media["height"],
            ),
            "settings" to Json.parseObject(job.settingsJson),
            "selection" to if (selected) "selected" else "all",
            "clips" to clips.map { c ->
                linkedMapOf(
                    "asset_id" to c.id, "file" to "clips/${c.filename}",
                    "thumbnail" to c.thumbRelpath?.let { "thumbnails/" + File(it).name },
                    "source_start" to c.start, "source_end" to c.end, "duration" to c.duration,
                    "width" to c.width, "height" to c.height, "sha256" to c.sha256,
                )
            },
            "notes" to if (selected) listOf("manifest.json covers every clip produced by this project; " +
                "a selected export contains a subset of them.") else emptyList(),
        )

        val exportId = Provenance.newId()
        val safeName = job.name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("").take(60).ifEmpty { "export" }
        val filename = "clipforge_${safeName}_${exportId.take(8)}.zip"
        val dst = File(engine.exportsDir, "$exportId.zip")
        val tmp = File(engine.exportsDir, "$exportId.zip.partial")
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                fun putText(name: String, text: String) {
                    zip.putNextEntry(ZipEntry("$ROOT/$name"))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                putText("project.json", Json.pretty(project))
                putText("manifest.json", manifestFile.readText(Charsets.UTF_8))
                for (c in clips) {
                    putStored(zip, "$ROOT/clips/${c.filename}", engine.resolve(c.relpath))
                    c.thumbRelpath?.let { rel ->
                        val thumb = engine.resolve(rel)
                        if (thumb.isFile) putStored(zip, "$ROOT/thumbnails/${thumb.name}", thumb)
                    }
                }
                putText("verification/public_key.pem", engine.keys.info().publicKeyPem)
                putText("verification/verification_report.json", Json.pretty(report))
            }
            if (!tmp.renameTo(dst)) throw IllegalStateException("Could not finalize the ZIP file")
        } finally {
            tmp.delete()
        }
        val ok = Json.obj(report["summary"])?.get("ok") == true
        val row = ExportRow(
            id = exportId, jobId = job.id, jobName = job.name,
            clipIdsJson = Json.canonical(clips.map { it.id }), filename = filename,
            relpath = dst.relativeTo(engine.dataDir).path, sizeBytes = dst.length(),
            createdAt = Provenance.now(), fileDeleted = false, verificationOk = ok,
        )
        db.insertExport(row)
        prune()
        engine.notifyChanged()
        return Built(row, dst)
    }

    /** Videos are already compressed: store them without deflate (needs size + CRC up front). */
    private fun putStored(zip: ZipOutputStream, name: String, file: File) {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = file.length()
            compressedSize = file.length()
            this.crc = crc.value
        }
        zip.putNextEntry(entry)
        file.inputStream().buffered().use { it.copyTo(zip, 1 shl 16) }
        zip.closeEntry()
    }

    /** Keeps only the newest [RETENTION] ZIP files on disk; history rows remain. */
    fun prune() {
        val live = engine.db.listExports(limit = 10_000).filter { !it.fileDeleted }
        for (e in live.drop(RETENTION)) {
            try { engine.resolve(e.relpath).delete() } finally { engine.db.markExportDeleted(e.id) }
        }
    }

    // ------------------------------------------------------- shared storage
    /** Copies a file into Downloads/CLIPFORGE (ZIPs, manifests, keys). Returns the new item's Uri. */
    fun saveToDownloads(file: File, displayName: String, mime: String): Uri =
        insertMedia(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), file, displayName, mime,
            "Download/CLIPFORGE")

    /** Copies a clip into Movies/CLIPFORGE so it appears in the gallery. */
    fun saveVideoToGallery(file: File, displayName: String): Uri =
        insertMedia(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), file, displayName,
            "video/mp4", "Movies/CLIPFORGE")

    private fun insertMedia(collection: Uri, file: File, displayName: String, mime: String, relPath: String): Uri {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(collection, values) ?: throw IllegalStateException("Could not create the file in shared storage")
        try {
            (cr.openOutputStream(uri) ?: throw IllegalStateException("Could not open shared storage for writing")).use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 16) }
            }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
        return uri
    }

    // --------------------------------------------------------- verification
    /**
     * Verifies a CLIPFORGE export ZIP (from this phone or the desktop app): checks the manifest
     * signature and re-hashes every clip in the archive.
     */
    fun verifyZip(input: InputStream): Map<String, Any?> {
        var manifestText: String? = null
        val files = ArrayList<Pair<String, String>>()
        ZipInputStream(input.buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                val rel = name.substringAfter("$ROOT/", name)
                when {
                    rel == "manifest.json" -> {
                        val bytes = readLimited(zin, MAX_MANIFEST_BYTES)
                        manifestText = String(bytes, Charsets.UTF_8)
                    }
                    rel.startsWith("clips/") -> {
                        val (sha, _) = Provenance.sha256Stream(NonClosing(zin))
                        files.add(rel to sha)
                    }
                }
            }
        }
        val text = manifestText ?: throw IllegalArgumentException("The ZIP has no manifest.json")
        return verifyManifestText(text, files)
    }

    /** Verifies manifest text plus already-hashed files (name, sha256). */
    fun verifyManifestText(text: String, files: List<Pair<String, String>>): Map<String, Any?> {
        val doc = try { Json.parse(text) } catch (e: Exception) {
            throw IllegalArgumentException("manifest.json is not valid JSON: ${e.message}")
        }
        val sig = Provenance.verifySignedManifest(doc, trusted = engine.keys.publicKey())
        // A manifest signed by another device is still checked against its own embedded key.
        val effective = if (!sig.trustedKey && sig.error == "Manifest was signed by a different key than the trusted key") {
            val own = Provenance.verifySignedManifest(doc)
            own.copy(error = if (own.valid) null else own.error)
        } else sig
        val manifest = Json.obj(Json.obj(doc)?.get("manifest")) ?: emptyMap()
        return Provenance.buildReport(doc, effective, Provenance.matchFiles(manifest, files))
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IllegalArgumentException("manifest.json is too large")
        }
        return out.toByteArray()
    }

    private class NonClosing(input: InputStream) : java.io.FilterInputStream(input) {
        override fun close() {}
    }

    companion object {
        const val ROOT = "CLIPFORGE_EXPORT"
        const val RETENTION = 20
        const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    }
}
