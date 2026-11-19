package com.clipforge.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clipforge.app.TestSupport.FAST
import com.clipforge.app.TestSupport.asset
import com.clipforge.app.TestSupport.context
import com.clipforge.app.core.Json
import com.clipforge.app.core.Provenance
import com.clipforge.app.engine.ImportException
import com.clipforge.app.export.Exporter
import com.clipforge.app.media.MediaProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class PipelineTest {

    @Test
    fun fullPipelineProducesSignedSceneSnappedClips() {
        val engine = TestSupport.engine()
        val job = engine.importVideo(Uri.fromFile(asset("scenes.mp4")), "Pipeline test", FAST)
        assertEquals(Provenance.sha256File(asset("scenes.mp4")), job.sourceSha256)
        val done = TestSupport.waitForEnd(engine, job.id)
        assertEquals("error: ${done.error}\n${done.errorDetail}", "completed", done.status)
        assertNull(done.error)

        val clips = engine.db.listClips(job.id)
        assertEquals(listOf(0.0 to 8.0, 8.0 to 17.0, 17.0 to 26.0), clips.map { it.start to it.end })
        assertEquals(listOf("start", "scene", "scene"), clips.map { it.startKind })
        assertEquals(listOf("scene", "scene", "end"), clips.map { it.endKind })
        for (c in clips) {
            val f = engine.resolve(c.relpath)
            assertTrue(f.isFile)
            val m = MediaProbe.probe(f)
            assertEquals(720, m.width)
            assertEquals(1280, m.height)
            assertEquals("h264", m.videoCodec)
            assertTrue(m.hasAudio)
            assertEquals(c.end - c.start, m.duration, 0.35)
            assertEquals(Provenance.sha256File(f), c.sha256)
            assertTrue("thumbnail", c.thumbRelpath != null && engine.resolve(c.thumbRelpath!!).length() > 0)
        }

        val plan = Json.parseObject(done.planJson!!)
        assertEquals("hybrid", plan["method_used"])
        assertEquals(listOf(8.0, 17.0), plan["scene_boundaries_detected"])
        assertEquals(2L, (plan["scene_boundaries_used"] as Number).toLong())

        val report = engine.verifyOnDisk(done)
        assertEquals(true, Json.obj(report["summary"])!!["ok"])
        val doc = Json.parse(engine.manifestFile(job.id).readText())
        val sig = Provenance.verifySignedManifest(doc, trusted = engine.keys.publicKey())
        assertTrue(sig.valid)
        assertTrue(sig.trustedKey)
        val manifest = Json.obj(Json.obj(doc)!!["manifest"])!!
        assertEquals(false, Json.obj(manifest["generator"])!!["ai_used"])
        assertEquals(3, Json.arr(manifest["outputs"])!!.size)

        // Export ZIP: layout, and it verifies from the archive alone.
        val exporter = Exporter(context, engine)
        val built = exporter.buildExport(job.id)
        assertTrue(built.row.verificationOk)
        val names = ZipFile(built.file).use { z -> z.entries().toList().map { it.name }.toSet() }
        for (n in listOf("project.json", "manifest.json", "clips/clip_0001.mp4", "clips/clip_0003.mp4",
            "thumbnails/clip_0001.jpg", "verification/public_key.pem", "verification/verification_report.json")) {
            assertTrue("missing $n in $names", "CLIPFORGE_EXPORT/$n" in names)
        }
        val zipReport = built.file.inputStream().use { exporter.verifyZip(it) }
        val zs = Json.obj(zipReport["summary"])!!
        assertEquals(true, zs["ok"])
        assertEquals(3L, (zs["files_matched"] as Number).toLong())

        // Selected export carries only the chosen clips.
        val partial = exporter.buildExport(job.id, listOf(clips[1].id))
        val partialNames = ZipFile(partial.file).use { z -> z.entries().toList().map { it.name } }
        assertEquals(1, partialNames.count { it.startsWith("CLIPFORGE_EXPORT/clips/") })

        // Tampering: flip one byte of a clip -> verification reports it as modified.
        RandomAccessFile(engine.resolve(clips[0].relpath), "rw").use { raf ->
            raf.seek(raf.length() / 2)
            val b = raf.read()
            raf.seek(raf.length() / 2)
            raf.write(b xor 0xff)
        }
        val after = engine.verifyOnDisk(done)
        assertEquals(false, Json.obj(after["summary"])!!["ok"])
        assertEquals(1L, (Json.obj(after["summary"])!!["files_modified"] as Number).toLong())

        // A modified manifest fails the signature check.
        val mf = engine.manifestFile(job.id)
        mf.writeText(mf.readText().replace("Pipeline test", "Someone else"))
        assertFalse(Provenance.verifySignedManifest(Json.parse(mf.readText())).valid)

        assertTrue(engine.delete(job.id))
        assertFalse(engine.jobDir(job.id).exists())
    }

    @Test
    fun cancelStopsRenderingAndCleansUp() {
        val engine = TestSupport.engine()
        val settings = FAST.copy(targetDuration = 5.0, minDuration = 3.0, sceneWindow = 1.0, remainderMin = 2.0,
            segmentationMethod = "fixed")
        val job = engine.importVideo(Uri.fromFile(asset("scenes.mp4")), "Cancel test", settings)
        TestSupport.waitFor(120_000, "rendering to start") { engine.db.getJob(job.id)?.stage == "rendering" }
        engine.cancel(job.id)
        val end = TestSupport.waitForEnd(engine, job.id, 60_000)
        assertEquals("cancelled", end.status)
        assertTrue(engine.db.listClips(job.id).isEmpty())
        assertFalse(File(engine.jobDir(job.id), "clips").exists())

        engine.retry(job.id)
        val again = TestSupport.waitForEnd(engine, job.id)
        assertEquals("completed", again.status)
        assertEquals(5, engine.db.listClips(job.id).size)
    }

    @Test
    fun importRejectsNonVideo() {
        val engine = TestSupport.engine()
        val f = File(context.cacheDir, "fake.mp4").apply { writeText("definitely not a video") }
        try {
            engine.importVideo(Uri.fromFile(f), "bad", FAST)
            throw AssertionError("expected ImportException")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("Invalid or unsupported video"))
        }
        assertTrue(engine.db.listJobs().isEmpty())
        assertTrue(engine.sourcesDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun deviceKeyIsStableAndSigns() {
        val dir = File(context.cacheDir, "keys-" + System.nanoTime())
        val k1 = com.clipforge.app.core.DeviceKeyStore(dir)
        val signed = k1.withSigner { Provenance.signManifest(mapOf("a" to 1), it) }
        val k2 = com.clipforge.app.core.DeviceKeyStore(dir)
        assertTrue(k1.publicKey().contentEquals(k2.publicKey()))
        val r = Provenance.verifySignedManifest(signed, trusted = k2.publicKey())
        assertTrue(r.valid)
        assertEquals(39, k2.info().fingerprint.length)
        assertFalse(File(dir, "ed25519_seed.sealed").readBytes().size == 32) // stored sealed, not raw
    }
}
